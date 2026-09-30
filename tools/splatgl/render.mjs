// Renders splat frames through the game's GLSL in headless Chromium (WebGL 2).
//   node tools/splatgl/render.mjs <sceneRoot> <shaderDir> <outDir> [shots...]
// <sceneRoot>/<shot>/{scene.json,splats.bin} come from `microScenePreview ... splats`;
// <shaderDir> from SceneShadersTest with STRATUM_SHADER_DUMP set.
import { createRequire } from 'node:module';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

// Through require, so a global install is found with NODE_PATH=$(npm root -g).
const { chromium } = createRequire(import.meta.url)('playwright');

const [sceneRoot, shaderDir, outDir, ...only] = process.argv.slice(2);
const here = path.dirname(new URL(import.meta.url).pathname);
fs.mkdirSync(outDir, { recursive: true });

// Serve the page, the scenes and the shaders from one origin; fetch does not read file:// URLs.
const roots = { '/page/': here, '/scene/': sceneRoot, '/shaders/': shaderDir };
const server = http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split('?')[0]);
  const prefix = Object.keys(roots).find(p => url.startsWith(p));
  const file = prefix && path.join(roots[prefix], url.slice(prefix.length));
  if (!file || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200);
  fs.createReadStream(file).pipe(res);
}).listen(0);
const port = server.address().port;

const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const shots = only.length ? only : fs.readdirSync(sceneRoot).filter(d => fs.existsSync(path.join(sceneRoot, d, 'scene.json')));
const report = [];
for (const shot of shots) {
  for (const mode of ['fast', 'exact']) {
    const scene = JSON.parse(fs.readFileSync(path.join(sceneRoot, shot, 'scene.json')));
    const page = await browser.newPage({ viewport: { width: scene.width, height: scene.height } });
    page.on('console', m => console.log(`[${shot}/${mode}] ${m.text()}`));
    await page.goto(`http://127.0.0.1:${port}/page/index.html?scene=/scene/${shot}&shaders=/shaders&mode=${mode}`);
    await page.waitForFunction(() => document.title === 'done' || document.title === 'error', null, { timeout: 120000 });
    const result = await page.evaluate(() => window.result);
    if (result.error && typeof result.error === 'string') throw new Error(`${shot}/${mode}: ${result.error}`);
    const file = path.join(outDir, `gpu-${shot}-${mode}.png`);
    await page.locator('canvas').screenshot({ path: file });
    report.push({ shot, mode, ...result });
    console.log(`wrote ${file}: ${JSON.stringify(result)}`);
    await page.close();
  }
}
fs.writeFileSync(path.join(outDir, 'gpu-report.json'), JSON.stringify(report, null, 2));
await browser.close();
server.close();
