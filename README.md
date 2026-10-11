# reflow-ascii

Responsive ASCII flow diagrams, written in ClojureScript.

Ship a real diagram in a `<pre>`. Without JavaScript, readers can scroll it
horizontally. With JavaScript, the library measures the available space, wraps
labels, and redraws the connections. The output remains selectable plain text.

The layout engine uses immutable Clojure data and pure functions. Browser
measurement and lifecycle management live in separate namespaces. A compiled ES
module lets an ordinary JavaScript site consume the library.

## Create a diagram

**Open `generator-standalone.html` directly in a browser.** No server or install
is needed. Choose a starting example, expand **Edit nodes and connections**, and
edit the EDN model. Click **Render diagram**, then use the width controls to
inspect its layouts. Invalid edits leave the last working diagram visible.

Exports include:

- **ASCII:** the exact plain text currently shown in the preview.
- **EDN / JSON:** the editable graph, retaining every node and edge.
- **HTML:** a complete standalone diagram, including its static fallback,
  graph data, CSS, and compiled responsive library.

Generate from EDN or JSON in a build pipeline as well:

```sh
node bin/reflow-ascii.cjs examples/decision.edn --columns 32
node bin/reflow-ascii.cjs examples/macos-swap.json --html --id swap --columns 90
```

The generator and CLI are themselves written in ClojureScript. ASCII is an
output format; the graph is the source of truth. Arbitrary graphs are supported,
with explicit destination references for loops and joins in this first version.

## Try the included article

**Open `examples/article-standalone.html` directly in a browser.** It contains the
complete article, embedded styles, fonts, artwork, and compiled library. Resize
the window or use **Show original ASCII** to compare the two layouts. Disable
JavaScript and reload to test the actual no-JS fallback.

For the development example and width playground, run from this directory:

```sh
node scripts/serve.mjs
```

Then open:

- `http://localhost:4173/` — diagram editor, exports, and responsive preview.
- `http://localhost:4173/examples/article.html#swap-diagram` — the article using
  the actual external module, exactly as a site integration would.

The compiled bundle is included. Running these examples needs Node 18+ for the
local server, or just a browser for the standalone article. No install or Java
toolchain is required to try them. The package has not been published to npm.

## Author in ClojureScript

```clojure
(ns my-site.diagrams
  (:require [reflow-ascii.core :as diagram]
            [reflow-ascii.browser :as browser]))

(def checkout
  {:nodes [{:id :cart    :label "Check the cart"}
           {:id :pay     :label "Take payment"}
           {:id :empty   :label "Keep shopping"}]
   :edges [{:from :cart :to :empty :label "empty"}
           {:from :cart :to :pay   :label "ready" :primary true}]})

;; Pure: suitable for a static site generator or Clojure/JVM build.
(diagram/render checkout {:columns 80})

;; Pure: useful for inspecting the selected layout.
(diagram/layout checkout {:columns 32})
;; => {:text "..." :mode :stacked :columns 32 :width 32 :height ...}

;; The browser is the only place that needs measurement or mutable state.
(def controller
  (browser/enhance! (js/document.querySelector "#checkout") checkout))

((:refresh! controller))
((:update! controller) checkout)
((:destroy! controller))
```

Add the project's `src` directory to your classpath, or use this unpacked project
as a local dependency:

```clojure
{:deps {reflow-ascii/reflow-ascii {:local/root "../reflow-ascii"}}}
```

`core.cljc` works on both Clojure and ClojureScript. `browser.cljs`, `measure.cljs`,
and `markup.cljs` target ClojureScript. No React, Reagent, layout engine, canvas,
or SVG is involved.

## Integrate with an ordinary JavaScript site

1. Copy `dist/reflow-ascii.js` and `src/style.css` into your site's assets.
2. Render the initial `<pre>` and model while building the page.
3. Enhance the diagram in the browser.

At build time (Node ESM):

```js
import { renderMarkup } from './dist/reflow-ascii.js';

const graph = {
  nodes: [
    { id: 'start', label: 'Check the cart' },
    { id: 'pay', label: 'Take payment' }
  ],
  edges: [{ from: 'start', to: 'pay', label: 'ready' }]
};

const html = renderMarkup(graph, { id: 'checkout', columns: 80 });
// Insert html into your page template. It includes the real ASCII text and
// an inert application/json script containing the model.
```

In the page:

```html
<link rel="stylesheet" href="/assets/reflow-ascii.css">
<!-- Insert the build-time html here. -->
<script type="module">
  import { enhanceAll } from '/assets/reflow-ascii.js';
  enhanceAll();
</script>
```

Give every `renderMarkup` call a unique `id`. The same graph generates both
outputs, so the fallback cannot drift from the enhanced version.

You can also use `src/auto.js` as a module entry point; keep the package's relative
directory structure when copying it. To install the unpacked package in another
project: `npm install /path/to/reflow-ascii`.

## Keep an existing ASCII fallback

The included article demonstrates this route. Its original diagram text remains
inside the first `<pre>`, with these attributes added:

```html
<pre id="swap-diagram" data-ascii data-ascii-source="swap-model" tabindex="0">
  <!-- The original, already-rendered ASCII goes here. -->
</pre>
<script type="application/json" id="swap-model">
  {"nodes":[{"id":"start","label":"Begin"}],"edges":[]}
</script>
```

Use the actual graph in the JSON script; `examples/macos-swap.json` is the full
article model. Then call `enhanceAll()`.

For trusted build-time inputs, `renderMarkup` handles HTML and script escaping.
When writing a JSON script yourself, escape `<` as `\u003c` so a label cannot
close the script element. Runtime rendering always uses `textContent`.

The library **does not guess graph semantics from arbitrary ASCII artwork**.
Existing text is a fallback; a small explicit model supplies the relationships.
This avoids silently reconnecting an ambiguous hand-drawn arrow.

## Model and layout rules

| Field | Meaning |
| --- | --- |
| `nodes` | Ordered vector/array of `{id, label, kind?}`. IDs are unique. |
| `kind` | `box` (default) or `text` for an unboxed caption. |
| `edges` | Ordered vector/array of `{from, to, label?, primary?}`. |
| `primary` | The downward continuation in a wide layout; defaults to the last outgoing edge. |
| `root` | Optional start ID; defaults to the first node with no incoming edge. |
| `title` | Optional accessible title. |
| `description` | Optional complete accessible description; otherwise generated from the graph. |

Native ClojureScript uses keyword keys and may use keyword IDs. JavaScript uses
string keys and IDs. Branch order follows the edge vector in both APIs.

The renderer tries the widest readable boxes that fit, then uses a stacked
layout with a continuous fork rail. It does not shrink the font or hide overflow.
Joins, cycles, disconnected graphs, and deeply nested forks that cannot fit use
numbered boxes with explicit destination references. Those references preserve
every edge without inventing a misleading route through the drawing.

First-release boundaries:

- Labels use printable ASCII plus line breaks. Tabs, ANSI sequences, emoji, and
  wide Unicode glyphs are rejected instead of producing unreliable alignment.
- Budgets are 8–512 character columns; models support up to 100 nodes and 300
  edges. A container physically narrower than eight cells keeps the scrollable
  fallback. Ordinary phone sizes fit and reflow.
- Include the baseline CSS and use a monospace font. The supplied CSS disables
  ligatures and preserves spaces. Give flex/grid ancestors `min-width: 0` if
  your layout otherwise prevents the diagram's container from shrinking.
- ResizeObserver handles container changes. Window resize is also observed;
  font loading triggers remeasurement. Without ResizeObserver, call `refresh()`
  after container-only changes. Call it after changing typography manually.
- Changes made by `update()` affect the enhanced view; `destroy()` restores the
  original static DOM, including its original node identities and attributes.
- Accessible descriptions contain the graph's meaning; screen readers need not
  read punctuation art. Resizing does not create live announcements.

## API

| ClojureScript | JavaScript | Result |
| --- | --- | --- |
| `core/render` | `renderDiagram` | ASCII string |
| `core/layout` | `layoutDiagram` | Text, chosen mode, budget, width, height |
| `core/describe` | `describeDiagram` | Accessible description |
| `markup/render` | `renderMarkup` | Static pre and inert model script |
| `browser/enhance!` | `enhance` | Controller for one pre |
| `browser/enhance-all!` | `enhanceAll` | Controllers for opted-in pre elements |

Pure render options: `{:columns 80 :mode :auto}` / `{columns: 80, mode: 'auto'}`.
Modes are `auto`, `stacked`, and `references`. Enhancement options accept `mode`
and `on-error` / `onError(error, pre)`.

JavaScript controller methods are `refresh()`, `update(graph)`, and `destroy()`.
ClojureScript controllers are maps with `:refresh!`, `:update!`, `:destroy!`, and
`:element`. Repeated enhancement of an element reuses the original observer and
options; destroy it first to change options.

The pre dispatches bubbling `ascii:render` events with layout results in
`event.detail`, and `ascii:error` events with `detail.error`. A malformed model
is reported without deleting fallback content. One failed `enhanceAll()` element
does not stop the rest.

The CLI can render standalone text or build-time markup:

```sh
node bin/reflow-ascii.cjs examples/macos-swap.json --columns 32
node bin/reflow-ascii.cjs examples/macos-swap.json --html --id swap --columns 90
```

## Build and test

Editing and compiling ClojureScript requires **Java 21+** and **Node 18+**.
The build uses the pinned shadow-cljs version and Closure advanced compilation.

```sh
npm ci
npm test
npm run build
npm run test:dom
npx playwright install chromium
npm run test:browser
```

`npm run build` regenerates the library ES module, generator, CLI, hosted article
example, and standalone HTML files. It does not fetch or modify the live website.

The ClojureScript tests cover column budgets, wrapping, branching, cycles,
shared targets, validation, immutability, accessible descriptions, and script
escaping. DOM tests exercise the compiled library and generator with controlled
measurements; they do not replace visual browser checks. Browser tests exercise
the compiled public API, phone/desktop sizing,
container resizing, font changes, fallback with JavaScript disabled or blocked,
DOM restoration, generator exports, and the complete article integration.
See `VALIDATION.md` for the results obtained while preparing this archive.

## Source map

| Source | Responsibility |
| --- | --- |
| `src/reflow_ascii/core.cljc` | Pure model validation and ASCII composition |
| `src/reflow_ascii/measure.cljs` | Font and content-box measurements |
| `src/reflow_ascii/browser.cljs` | DOM lifetime, observation, and enhancement |
| `src/reflow_ascii/markup.cljs` | Safe static HTML generation |
| `src/reflow_ascii/api.cljs` | Conversion at the JavaScript boundary |
| `src/reflow_ascii/generator.cljs` | EDN editor, preview controls, and exports |
| `src/reflow_ascii/cli.cljs` | EDN/JSON command-line renderer |
| `src/index.d.ts` | TypeScript declarations for JS consumers |
| `examples/article.source.html` | Unmodified downloaded article snapshot |
| `examples/macos-swap.json` | Its explicit diagram model |
| `scripts/build-demo.mjs` | Reproducible example assembly |

Authored JavaScript is integration glue, build tooling, and browser tests.
`dist`, `bin/reflow-ascii.cjs`, and `examples/generated` contain compiler output.
The renderer, browser engine, generator, and CLI are implemented in Clojure(Script).

The library and new example tooling are MIT licensed. The source article and
its existing assets retain their original ownership and terms; see `NOTICE.md`.
