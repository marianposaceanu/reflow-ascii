import { enhanceAll } from '../dist/reflow-ascii.js';

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => enhanceAll(), { once: true });
} else {
  enhanceAll();
}
