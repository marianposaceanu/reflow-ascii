import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { createDemoServer } from '../scripts/serve.mjs';

const server = createDemoServer().listen(0, '127.0.0.1');
await once(server, 'listening');
const base = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
const errors = [];
let passed = 0;
const check = (name, value) => { assert.ok(value, name); passed++; console.log(`PASS ${name}`); };

async function waitForDiagram(page, selector) {
  await page.waitForFunction(selector => {
    const pre = document.querySelector(selector);
    return pre?.dataset.asciiLayout && pre.dataset.asciiLayout !== 'fallback';
  }, selector);
  await page.evaluate(() => document.fonts.ready);
  await page.waitForFunction(selector => {
    const pre = document.querySelector(selector);
    return pre.scrollWidth <= pre.clientWidth + 1;
  }, selector);
}

try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 960 } });
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(base);
  await waitForDiagram(page, '#playground-diagram');
  check('desktop chooses wide layout', await page.locator('#playground-diagram').getAttribute('data-ascii-layout') === 'wide');

  await page.getByRole('button', { name: 'Phone', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('#playground-diagram').dataset.asciiLayout === 'stacked');
  check('container resize reflows without window resize', await page.locator('#playground-diagram').getAttribute('data-ascii-layout') === 'stacked');
  await waitForDiagram(page, '#playground-diagram');

  await page.getByRole('button', { name: 'Responsive: on', exact: true }).click();
  check('destroy restores scrollable fallback', await page.locator('#playground-diagram').evaluate(pre => !pre.dataset.asciiLayout && pre.scrollWidth > pre.clientWidth));
  await page.getByRole('button', { name: 'Responsive: off', exact: true }).click();
  await waitForDiagram(page, '#playground-diagram');

  await page.selectOption('#example', 'decision');
  check('generator selects a graph preset', (await page.locator('#playground-diagram').textContent()).includes('Is the build'));
  await page.locator('#editor summary').click();
  await page.locator('#graph-source').fill('{:nodes [{:id :hello :label "A diagram I made"}]}');
  await page.getByRole('button', { name: 'Render diagram', exact: true }).click();
  check('generator renders an authored EDN model', (await page.locator('#playground-diagram').textContent()).includes('A diagram I made'));
  const lastGoodDiagram = await page.locator('#playground-diagram').textContent();
  await page.locator('#graph-source').fill('{:nodes []}');
  await page.getByRole('button', { name: 'Render diagram', exact: true }).click();
  check('invalid edits preserve the previous model', await page.locator('#editor-error').isVisible() && await page.locator('#playground-diagram').textContent() === lastGoodDiagram);
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'HTML', exact: true }).click();
  const download = await downloadPromise;
  await mkdir(new URL('../test-results/', import.meta.url), { recursive: true });
  const exportPath = fileURLToPath(new URL('../test-results/exported-diagram.html', import.meta.url));
  await download.saveAs(exportPath);
  const exported = await browser.newPage({ viewport: { width: 320, height: 800 } });
  await exported.goto(new URL('../test-results/exported-diagram.html', import.meta.url).href);
  await waitForDiagram(exported, '#diagram');
  check('generated HTML works offline and reflows', (await exported.locator('#diagram').textContent()).includes('A diagram I made'));
  await exported.close();

  for (const width of [320, 360, 390, 768, 1280]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto(`${base}/examples/article.html`);
    await waitForDiagram(page, '#swap-diagram');
    const result = await page.locator('#swap-diagram').evaluate(pre => ({
      fits: pre.scrollWidth <= pre.clientWidth + 1,
      text: pre.textContent,
      font: getComputedStyle(pre).fontSize,
      accessible: pre.getAttribute('aria-label')
    }));
    check(`article fits at ${width}px`, result.fits);
    check(`article keeps both branches at ${width}px`, result.text.includes('yes') && result.text.includes('no'));
    check(`article has a meaningful accessible description at ${width}px`, result.accessible.includes('yes: kill it'));
  }

  await page.setViewportSize({ width: 390, height: 980 });
  await page.goto(`${base}/examples/article.html#swap-diagram`);
  await waitForDiagram(page, '#swap-diagram');
  const fontBefore = await page.locator('#swap-diagram').evaluate(pre => getComputedStyle(pre).fontSize);
  await page.getByRole('button', { name: 'Show original ASCII', exact: true }).click();
  const fontAfter = await page.locator('#swap-diagram').evaluate(pre => getComputedStyle(pre).fontSize);
  check('enhancement does not shrink the font', fontBefore === fontAfter);
  check('article toggle restores its exact original drawing', (await page.locator('#swap-diagram').textContent()).includes('+-----------------------------------+  yes  +---------------------+'));
  await page.getByRole('button', { name: 'Enable responsive layout', exact: true }).click();
  await waitForDiagram(page, '#swap-diagram');

  const lifecycle = await page.evaluate(async () => {
    const { enhance, enhanceAll } = await import('/dist/reflow-ascii.js');
    const graph = { nodes: [{ id: 'a', label: 'Keep all of these words in a readable box' }] };
    const pre = document.createElement('pre');
    pre.setAttribute('data-ascii', '');
    pre.setAttribute('aria-label', 'Original description');
    pre.style.cssText = 'width:280px;font-size:14px;letter-spacing:0px;padding:12px';
    const code = document.createElement('code');
    const span = document.createElement('span');
    span.textContent = 'original fallback';
    code.append(span);
    pre.append(code);
    document.body.append(pre);
    let errors = 0;
    pre.addEventListener('ascii:error', () => errors++);
    const controller = enhance(pre, graph);
    const firstColumns = Number(pre.dataset.asciiColumns);
    pre.style.fontSize = '22px';
    controller.refresh();
    const largerTextColumns = Number(pre.dataset.asciiColumns);
    const fitAfterFontChange = pre.scrollWidth <= pre.clientWidth + 1;
    const beforeInvalid = pre.textContent;
    let threw = false;
    try { controller.update({ nodes: [] }); } catch { threw = true; }
    const survivedInvalid = threw && pre.textContent === beforeInvalid;
    controller.update({ nodes: [{ id: 'safe', label: '<img src=x onerror=alert(1)>' }] });
    const safeText = !pre.querySelector('img');
    controller.destroy();
    controller.destroy();
    const restored = code.firstChild === span && pre.textContent === 'original fallback'
      && pre.getAttribute('aria-label') === 'Original description' && !pre.dataset.asciiLayout;
    pre.remove();

    const fixture = document.createElement('div');
    fixture.innerHTML = '<pre data-ascii-source="bad-model">bad fallback</pre><script type="application/json" id="bad-model">oops</script><pre data-ascii data-ascii-source="good-model">good fallback</pre><script type="application/json" id="good-model">{"nodes":[{"id":"x","label":"Good"}]}</script>';
    document.body.append(fixture);
    let failures = 0;
    const controllers = enhanceAll(fixture, { onError: () => failures++ });
    const isolatedErrors = controllers.length === 1 && failures === 1
      && fixture.firstChild.textContent === 'bad fallback';
    controllers.forEach(item => item.destroy());
    fixture.remove();
    return { largerTextColumns, firstColumns, fitAfterFontChange, survivedInvalid, safeText, restored, isolatedErrors, errors };
  });
  check('font changes recalculate character budgets', lifecycle.largerTextColumns < lifecycle.firstColumns && lifecycle.fitAfterFontChange);
  check('invalid updates preserve the rendered diagram', lifecycle.survivedInvalid);
  check('labels are text, never executable HTML', lifecycle.safeText);
  check('destroy restores original node identities and attributes', lifecycle.restored);
  check('one invalid model does not stop enhanceAll', lifecycle.isolatedErrors);
  check('lifecycle has no rendering errors', lifecycle.errors === 0);

  await page.evaluate(async () => {
    const { enhance } = await import('/dist/reflow-ascii.js');
    const pre = document.createElement('pre');
    pre.id = 'hidden-test';
    pre.dataset.ascii = '';
    pre.style.cssText = 'display:none;width:250px;font:14px monospace;padding:10px';
    pre.textContent = 'hidden fallback';
    document.body.append(pre);
    enhance(pre, { nodes: [{ id: 'visible', label: 'Now visible' }] });
    pre.style.display = 'block';
  });
  await waitForDiagram(page, '#hidden-test');
  check('initially hidden diagrams enhance when revealed', (await page.locator('#hidden-test').textContent()).includes('Now visible'));

  const noJS = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 980 } });
  const fallback = await noJS.newPage();
  await fallback.goto(`${base}/examples/article.html`);
  check('real no-JS article preserves horizontal scrolling', await fallback.locator('#swap-diagram').evaluate(pre => !pre.dataset.asciiLayout && pre.scrollWidth > pre.clientWidth));
  check('real no-JS article contains the complete diagram', (await fallback.locator('#swap-diagram').textContent()).includes('out of application'));
  await noJS.close();

  const blocked = await browser.newPage({ viewport: { width: 390, height: 980 } });
  await blocked.route('**/dist/reflow-ascii.js', route => route.abort());
  await blocked.goto(`${base}/examples/article.html`);
  check('failed library download leaves the fallback intact', await blocked.locator('#swap-diagram').evaluate(pre => !pre.dataset.asciiLayout && pre.scrollWidth > pre.clientWidth && pre.textContent.includes('compressor')));
  await blocked.close();

  const offline = await browser.newPage({ viewport: { width: 390, height: 980 } });
  offline.on('pageerror', error => errors.push(error.message));
  await offline.goto(new URL('../examples/article-standalone.html', import.meta.url).href);
  await waitForDiagram(offline, '#swap-diagram');
  check('standalone article works from file://', await offline.locator('#swap-diagram').getAttribute('data-ascii-layout') === 'stacked');
  await offline.close();

  await mkdir(new URL('../test-results/', import.meta.url), { recursive: true });
  await page.goto(`${base}/examples/article.html#swap-diagram`);
  await waitForDiagram(page, '#swap-diagram');
  await page.locator('#swap-diagram').screenshot({ path: fileURLToPath(new URL('../test-results/article-mobile.png', import.meta.url)) });
  await page.setViewportSize({ width: 1280, height: 1050 });
  await page.goto(base);
  await waitForDiagram(page, '#playground-diagram');
  await page.screenshot({ path: fileURLToPath(new URL('../test-results/playground-desktop.png', import.meta.url)), fullPage: true });
  check('no uncaught browser errors', errors.length === 0);
  console.log(`\n${passed} browser checks passed.`);
} finally {
  await browser.close();
  server.close();
}
