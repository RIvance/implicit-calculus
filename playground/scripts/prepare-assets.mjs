import { copyFile, mkdir, readdir, readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const packageDirectory = dirname(fileURLToPath(import.meta.resolve('z3-solver/package.json')));
const { version } = JSON.parse(await readFile(join(packageDirectory, 'package.json'), 'utf8'));
const destination = join(root, 'public', 'z3', version);
await mkdir(destination, { recursive: true });
for (const name of await readdir(join(packageDirectory, 'build'))) {
  if (name.startsWith('z3-built.') && /\.(js|wasm)$/.test(name)) {
    await copyFile(join(packageDirectory, 'build', name), join(destination, name));
  }
}
await copyFile(
  fileURLToPath(import.meta.resolve('coi-serviceworker/coi-serviceworker.js')),
  join(root, 'public', 'coi-serviceworker.js'),
);
