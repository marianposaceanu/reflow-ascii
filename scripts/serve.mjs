import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { extname, resolve, sep } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const types = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.avif': 'image/avif', '.woff2': 'font/woff2', '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.md': 'text/plain; charset=utf-8'
};

export function createDemoServer() {
  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://localhost');
      const name = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname);
      const file = resolve(root, `.${name}`);
      if (!file.startsWith(root.endsWith(sep) ? root : `${root}${sep}`)) {
        response.writeHead(403).end();
        return;
      }
      const body = await readFile(file);
      response.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
      response.end(body);
    } catch {
      response.writeHead(404, { 'Content-Type': 'text/plain' }).end('Not found');
    }
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT ?? 4173);
  createDemoServer().listen(port, '127.0.0.1', () => {
    console.log(`Playground: http://localhost:${port}`);
    console.log(`Article:    http://localhost:${port}/examples/article.html#swap-diagram`);
  });
}
