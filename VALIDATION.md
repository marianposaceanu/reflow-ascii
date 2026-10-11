# Validation of this archive

- Library, generator, and command-line tool: Closure advanced release builds,
  with zero compiler warnings.
- ClojureScript: 11 tests and 755 assertions passed.
- Compiled JavaScript in an emulated DOM: 32 lifecycle, integration, and export
  checks passed. Character widths and element widths were controlled test
  inputs; this is not a real browser layout engine.
- EDN and JSON command-line rendering verified against the included examples.
- The article's original ASCII text and all other code blocks are preserved.

The actual Chromium suite is included as `test/browser.test.mjs` but could not
run in the build environment. Its restricted runtime lacks `/proc`, which
Chromium needs; automatic approval rejected the request for broader execution.
Consequently real browser typography, screenshots, and visual overflow checks
remain unverified here. No Safari or Firefox run is claimed.

To run the browser checks locally:

```sh
npm ci
npx playwright install chromium
npm run test:browser
```

The suite tests 320, 360, 390, 768, and 1280 pixel viewports, true JavaScript-off
and blocked-module fallbacks, resizing, font changes, generator editing and
HTML export, and the standalone article loaded from disk. It writes screenshots
under `test-results/` when it completes.
