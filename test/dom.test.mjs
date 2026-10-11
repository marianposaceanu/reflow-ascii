// These are DOM/lifecycle tests with controlled measurements, not visual browser tests.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { Window } from 'happy-dom';
import { enhance, enhanceAll, renderMarkup } from '../dist/reflow-ascii.js';

const window = new Window({
  url: 'http://localhost:4173/',
  settings: {
    enableJavaScriptEvaluation: false,
    disableJavaScriptFileLoading: true,
    disableCSSFileLoading: true,
    navigation: { disableMainFrameNavigation: true }
  }
});
globalThis.window = window;
globalThis.document = window.document;
globalThis.CustomEvent = window.CustomEvent;
const { document } = window;
const observers = new Set();
window.ResizeObserver = class {
  constructor(callback) { this.callback = callback; }
  observe(element) { this.element = element; observers.add(this); }
  disconnect() { observers.delete(this); }
};
Object.defineProperty(window.HTMLElement.prototype, 'clientWidth', {
  get() { return this.style.display === 'none' ? 0 : Number(this.dataset.testWidth ?? 800); }
});
window.HTMLElement.prototype.getBoundingClientRect = function () {
  const fontSize = parseFloat(window.getComputedStyle(this).fontSize) || 14;
  const width = this.textContent.length * fontSize * 0.6;
  return { width, height: fontSize, x: 0, y: 0, left: 0, right: width, top: 0, bottom: fontSize };
};
const style = document.createElement('style');
style.textContent = await readFile(new URL('../src/style.css', import.meta.url), 'utf8');
document.head.append(style);
const graph = JSON.parse(await readFile(new URL('../examples/macos-swap.json', import.meta.url), 'utf8'));
let checks = 0;
const check = (name, value) => { assert.ok(value, name); checks++; console.log(`PASS ${name}`); };
const flush = () => new Promise(resolve => setTimeout(resolve, 35));
const notify = () => { for (const observer of observers) observer.callback([]); };

try {
  document.body.innerHTML = renderMarkup(graph, { id: 'test', columns: 100 });
  const pre = document.querySelector('pre');
  pre.style.cssText = 'font-size:14px;padding:16px';
  pre.dataset.testWidth = '900';
  const originalCode = pre.firstChild;
  const originalTextNode = originalCode.firstChild;
  const originalText = pre.textContent;
  const originalLabel = pre.getAttribute('aria-label');
  let renderEvents = 0;
  pre.addEventListener('ascii:render', () => renderEvents++);
  const controller = enhance(pre, graph);
  check('compiled enhancer selects wide layout', pre.dataset.asciiLayout === 'wide');
  check('render event is emitted', renderEvents === 1);
  const observerCount = observers.size;
  enhance(pre, graph);
  check('repeated enhancement reuses its observer', observers.size === observerCount);

  for (const width of [320, 360, 390, 768, 1280]) {
    pre.dataset.testWidth = String(width);
    notify();
    await flush();
    const budget = Number(pre.dataset.asciiColumns);
    check(`measured budget fits text at simulated ${width}px`, pre.textContent.split('\n').every(line => line.length <= budget));
  }
  pre.dataset.testWidth = '320';
  controller.refresh();
  check('narrow containers choose a stacked graph', pre.dataset.asciiLayout === 'stacked');
  const oldBudget = Number(pre.dataset.asciiColumns);
  pre.style.fontSize = '20px';
  controller.refresh();
  check('font changes produce a smaller column budget', Number(pre.dataset.asciiColumns) < oldBudget);
  const beforeError = pre.textContent;
  assert.throws(() => controller.update({ nodes: [] }));
  check('invalid update preserves existing text', pre.textContent === beforeError);
  controller.update({ nodes: [{ id: 'a', label: '<img src=x onerror=alert(1)>' }] });
  check('unsafe-looking labels remain plain text', !pre.querySelector('img') && pre.textContent.includes('<img'));

  pre.dataset.testWidth = '40';
  controller.refresh();
  check('impossibly narrow containers restore the static fallback', pre.dataset.asciiLayout === 'fallback' && pre.textContent === originalText);
  controller.destroy();
  controller.destroy();
  check('destroy restores node identities', pre.firstChild === originalCode && originalCode.firstChild === originalTextNode);
  check('destroy restores all managed attributes', !pre.dataset.asciiLayout && !pre.dataset.asciiColumns && pre.getAttribute('aria-label') === originalLabel);
  check('destroy disconnects observers', observers.size === 0);

  pre.dataset.testWidth = '320';
  pre.style.display = 'none';
  const hidden = enhance(pre, graph);
  check('hidden diagrams keep their initial text', pre.textContent === originalText);
  pre.style.display = 'block';
  notify();
  await flush();
  check('revealing a diagram triggers enhancement', pre.dataset.asciiLayout === 'stacked');
  hidden.destroy();

  document.body.innerHTML = '<pre data-ascii-source="bad">Keep me</pre><script type="application/json" id="bad">bad JSON</script>' + renderMarkup(graph, { id: 'good' });
  let failures = 0;
  const all = enhanceAll(document, { onError: () => failures++ });
  check('malformed JSON is isolated to one diagram', failures === 1 && all.length === 1 && document.querySelector('pre').textContent === 'Keep me');
  all.forEach(item => item.destroy());

  const articleSource = await readFile(new URL('../examples/article.source.html', import.meta.url), 'utf8');
  const article = await readFile(new URL('../examples/article.html', import.meta.url), 'utf8');
  const sourceDocument = new window.DOMParser().parseFromString(articleSource, 'text/html');
  const exampleDocument = new window.DOMParser().parseFromString(article, 'text/html');
  check('article preserves the exact original ASCII fallback', sourceDocument.querySelector('pre').textContent === exampleDocument.querySelector('#swap-diagram').textContent);
  check('article preserves every other code block', [...sourceDocument.querySelectorAll('pre')].slice(1).every((node, i) => node.textContent === [...exampleDocument.querySelectorAll('pre')][i + 1].textContent));
  check('article includes its model and enhancer', !!exampleDocument.querySelector('#swap-model') && article.includes('src="article.js"'));

  // Test the actual compiled ClojureScript authoring application.
  document.open();
  document.write(await readFile(new URL('../index.html', import.meta.url), 'utf8'));
  document.close();
  const outputs = [];
  const realCreateURL = URL.createObjectURL;
  URL.createObjectURL = blob => { outputs.push(blob); return `blob:test-${outputs.length}`; };
  const realClick = window.HTMLAnchorElement.prototype.click;
  window.HTMLAnchorElement.prototype.click = function () {};
  await import('../examples/generated/generator.js');
  check('compiled generator initializes', !document.querySelector('#render-graph').disabled && !!document.querySelector('#playground-diagram').dataset.asciiLayout);
  const editor = document.querySelector('#graph-source');
  editor.value = '{:nodes [{:id :my/start :label "A diagram I made"}]}';
  document.querySelector('#render-graph').click();
  check('EDN authoring updates the preview', document.querySelector('#playground-diagram').textContent.includes('A diagram I made'));
  const goodText = document.querySelector('#playground-diagram').textContent;
  editor.value = '{:nodes []}';
  document.querySelector('#render-graph').click();
  check('invalid editor input keeps the last good diagram', !document.querySelector('#editor-error').hidden && document.querySelector('#playground-diagram').textContent === goodText);
  for (const format of ['ascii', 'edn', 'json', 'html']) document.querySelector(`[data-export="${format}"]`).click();
  const texts = await Promise.all(outputs.map(blob => blob.text()));
  check('ASCII export matches the preview', texts[0] === `${goodText}\n`);
  check('EDN export retains graph structure', texts[1].includes(':my/start'));
  check('JSON export preserves namespaced IDs', JSON.parse(texts[2]).nodes[0].id === 'my/start');
  const exportedDocument = new window.DOMParser().parseFromString(texts[3], 'text/html');
  check('HTML export includes real ASCII and its model', exportedDocument.querySelector('pre').textContent.includes('A diagram I made') && !!exportedDocument.querySelector('script[type="application/json"]'));
  check('HTML export includes the compiled library inline', texts[3].includes('data:text/javascript;base64,'));
  URL.createObjectURL = realCreateURL;
  window.HTMLAnchorElement.prototype.click = realClick;
  check('generator observer count stays bounded after edits', observers.size === 1);
  document.querySelector('#toggle').click();
  check('generator toggle restores static mode', !document.querySelector('#playground-diagram').dataset.asciiLayout && observers.size === 0);

  console.log(`\n${checks} DOM/export checks passed. Measurements were simulated; visual browser QA is separate.`);
} finally {
  await window.happyDOM.close();
}
