import { enhanceAll } from '../dist/reflow-ascii.js';

const pre = document.querySelector('#swap-diagram');
const toggle = document.querySelector('#article-toggle');
const status = document.querySelector('#article-status');
let controller;

pre.addEventListener('ascii:render', ({ detail }) => {
  status.textContent = `${detail.mode} · ${detail.columns} columns`;
});
[controller] = enhanceAll();
if (controller) toggle.hidden = false;

toggle.addEventListener('click', () => {
  if (controller) {
    controller.destroy();
    controller = null;
    toggle.textContent = 'Enable responsive layout';
    status.textContent = 'Original ASCII · scroll horizontally on narrow screens';
  } else {
    [controller] = enhanceAll();
    toggle.textContent = 'Show original ASCII';
  }
});
