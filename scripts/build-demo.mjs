import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describeDiagram, renderMarkup } from '../dist/reflow-ascii.js';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = file => readFile(resolve(root, file), 'utf8');
const write = (file, text) => writeFile(resolve(root, file), text);
const escape = text => text.replace(/[&<>"']/g, char => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' })[char]);
const graph = JSON.parse(await read('examples/macos-swap.json'));
const bundle = await read('dist/reflow-ascii.js');
const base64Bundle = Buffer.from(bundle).toString('base64');
const bundleURL = `data:text/javascript;base64,${base64Bundle}`;
const template = await read('examples/playground.template.html');
const playground = template
  .replace('{{DIAGRAM}}', renderMarkup(graph, { id: 'playground-diagram', columns: 100 }))
  .replace('{{RUNTIME}}', base64Bundle);
await write('index.html', playground);
const generatorBundle = await read('examples/generated/generator.js');
const generatorURL = `data:text/javascript;base64,${Buffer.from(generatorBundle).toString('base64')}`;
const generatorStandalone = playground
  .replace('<link rel="stylesheet" href="src/style.css">', `<style>${await read('src/style.css')}</style>`)
  .replace('<link rel="stylesheet" href="examples/playground.css">', `<style>${await read('examples/playground.css')}</style>`)
  .replace('<script type="module" src="examples/generated/generator.js"></script>', `<script type="module">import '${generatorURL}';</script>`)
  .replace('href="examples/article.html#swap-diagram"', 'href="examples/article-standalone.html#swap-diagram"');
await write('generator-standalone.html', generatorStandalone);

let article = await read('examples/article.source.html');
const json = JSON.stringify(graph).replace(/</g, '\\u003c');
article = article.replace('<meta name="robots" content="index, follow">', '<meta name="robots" content="noindex, nofollow">');
article = article.replace('</head>', '<link rel="stylesheet" href="../src/style.css">\n<link rel="stylesheet" href="article.css">\n</head>');
article = article.replace(/<body([^>]*)>/, `<body$1>
<aside class="example-banner" aria-label="Local integration example">
<p><strong>reflow-ascii integration example.</strong> The complete article with its first diagram enhanced. <a href="../index.html">Open the width playground</a> · <a href="https://ark.marianposaceanu.com/macos-swap-exhaustion-paused-apps">Original article</a></p>
<button id="article-toggle" type="button" hidden>Show original ASCII</button><small id="article-status">Original ASCII is available without JavaScript.</small>
</aside>`);
let foundDiagram = false;
article = article.replace(/<pre>([\s\S]*?)<\/pre>/, (_, contents) => {
  foundDiagram = true;
  return `<pre id="swap-diagram" data-ascii data-ascii-source="swap-model" tabindex="0" role="img" aria-label="${escape(describeDiagram(graph))}">${contents}</pre>\n<script type="application/json" id="swap-model">${json}</script>`;
});
if (!foundDiagram) throw new Error('The source article no longer contains the expected diagram');
article = article.replace('</body>', '<script type="module" src="article.js"></script>\n</body>');
// Keep navigation links pointing to the real article site, not missing local pages.
article = article.replace(/<a\b([^>]*?)href="([^"#][^"]*)"/g, (match, attributes, href) => {
  if (/^(?:[a-z]+:|\/\/|\.\.\/index\.html)/i.test(href)) return match;
  return `<a${attributes}href="${new URL(href, 'https://ark.marianposaceanu.com/').href}"`;
});
await write('examples/article.html', article);

// A second copy runs directly from a ZIP extraction, without a local web server.
const inlineModule = (await read('examples/article.js')).replace('../dist/reflow-ascii.js', bundleURL);
let siteCSS = await read('examples/assets/site.css');
const fontPaths = [...siteCSS.matchAll(/url\("(\.\.\/fonts\/[^"?]+)"\)/g)];
for (const [, path] of fontPaths) {
  const bytes = await readFile(resolve(root, 'examples/assets', path));
  siteCSS = siteCSS.replaceAll(`url("${path}")`, `url("data:font/woff2;base64,${bytes.toString('base64')}")`);
}
let standalone = article
  .replace(/<link rel="preload"[^>]*>/g, '')
  .replace(/<link rel="icon"[^>]*>/g, '')
  .replace(/<link rel="stylesheet" href="assets\/site\.css[^\"]*">/, `<style>${siteCSS}</style>`)
  .replace('<link rel="stylesheet" href="../src/style.css">', `<style>${await read('src/style.css')}</style>`)
  .replace('<link rel="stylesheet" href="article.css">', `<style>${await read('examples/article.css')}</style>`)
  .replace(/<script src="assets\/site\.js[^\"]*" defer><\/script>/, `<script src="data:text/javascript;base64,${Buffer.from(await read('examples/assets/site.js')).toString('base64')}" defer></script>`)
  .replace('<script type="module" src="article.js"></script>', `<script type="module">${inlineModule}</script>`);
const imagePath = 'assets/articles/macos-swap-exhaustion-paused-apps/paused-window-full-memory.avif';
const artwork = await readFile(resolve(root, 'examples', imagePath));
standalone = standalone.replace(/src="assets\/articles\/macos-swap-exhaustion-paused-apps\/paused-window-full-memory\.avif[^\"]*"/,
  `src="data:image/avif;base64,${artwork.toString('base64')}"`);
await write('examples/article-standalone.html', standalone);
console.log('Built generator, full article integration, and both standalone examples.');
