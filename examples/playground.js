// An ordinary JavaScript consumer of the compiled ClojureScript library.
import { enhance } from '../dist/reflow-ascii.js';

const pre = document.querySelector('#playground-diagram');
const graph = JSON.parse(document.getElementById(pre.dataset.asciiSource).textContent);
const frame = document.querySelector('#diagram-frame');
const width = document.querySelector('#width');
const output = document.querySelector('#width-value');
const status = document.querySelector('#layout-status');
const toggle = document.querySelector('#toggle');
let controller;

pre.addEventListener('ascii:render', ({ detail }) => {
  status.textContent = `${detail.mode} layout · ${detail.columns} columns available · ${detail.width} used`;
});
controller = enhance(pre, graph);
width.disabled = toggle.disabled = false;

function resize(value) {
  width.value = value;
  frame.style.width = `${value}px`;
  output.value = `${value} px`;
  document.querySelectorAll('[data-width]').forEach(button => {
    button.setAttribute('aria-pressed', String(button.dataset.width === String(value)));
  });
}
width.addEventListener('input', () => resize(width.value));
document.querySelectorAll('[data-width]').forEach(button => {
  button.addEventListener('click', () => resize(button.dataset.width));
});
toggle.addEventListener('click', () => {
  if (controller) {
    controller.destroy();
    controller = null;
    status.textContent = 'Static fallback · horizontal scrolling when needed';
    toggle.textContent = 'Responsive: off';
  } else {
    controller = enhance(pre, graph);
    toggle.textContent = 'Responsive: on';
  }
});
