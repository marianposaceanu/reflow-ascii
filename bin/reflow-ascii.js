#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { renderDiagram, renderMarkup } from '../dist/reflow-ascii.js';

const args = process.argv.slice(2);
if (!args.length || args.includes('--help')) {
  console.log('Usage: reflow-ascii graph.json [--columns 80] [--html] [--id diagram-id]\nWrites ASCII, or a static <pre> and inert graph JSON with --html.');
  process.exit(args.length ? 0 : 1);
}
try {
  const path = args.shift();
  const options = {};
  let html = false;
  while (args.length) {
    const flag = args.shift();
    if (flag === '--html') html = true;
    else if (flag === '--columns') options.columns = Number(args.shift());
    else if (flag === '--id') {
      if (!args.length) throw new Error('--id needs a value');
      options.id = args.shift();
    } else throw new Error(`Unknown option: ${flag}`);
  }
  const graph = JSON.parse(await readFile(path, 'utf8'));
  console.log(html ? renderMarkup(graph, options) : renderDiagram(graph, options));
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
