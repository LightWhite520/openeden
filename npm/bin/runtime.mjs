import { createHash, randomUUID } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { cp, mkdir, mkdtemp, readdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';

export function runtimeLib(root) {
  const runtime = path.join(root, '.npm-runtime', 'cli');
  const manifest = path.join(runtime, 'current.json');
  if (!existsSync(manifest)) return path.join(runtime, 'lib');
  const { generation } = JSON.parse(readFileSync(manifest, 'utf8'));
  if (!/^[a-f0-9]{64}$/.test(generation)) throw new Error('Invalid CLI runtime generation');
  return path.join(runtime, 'releases', generation, 'lib');
}

export async function publishRuntime(root, source) {
  const runtime = path.join(root, '.npm-runtime', 'cli');
  const releases = path.join(runtime, 'releases');
  await mkdir(releases, { recursive: true });
  const staging = await mkdtemp(path.join(releases, '.staging-'));
  const manifest = path.join(runtime, `.current-${randomUUID()}.json`);
  try {
    const lib = path.join(staging, 'lib');
    await cp(source, lib, { recursive: true });
    const files = (await readdir(lib)).sort();
    if (!files.some(name => /^cli-.*\.jar$/.test(name))) throw new Error('CLI JAR missing from distribution');
    const hash = createHash('sha256');
    for (const name of files) {
      const bytes = await readFile(path.join(lib, name));
      hash.update(`${name}\0${bytes.length}\0`).update(bytes);
    }
    const generation = hash.digest('hex');
    const target = path.join(releases, generation);
    try {
      await rename(staging, target);
    } catch (error) {
      if (!['EEXIST', 'ENOTEMPTY', 'EPERM'].includes(error.code) || !existsSync(path.join(target, 'lib'))) throw error;
    }
    await writeFile(manifest, JSON.stringify({ generation }) + '\n');
    await rename(manifest, path.join(runtime, 'current.json'));
    // Older generations may still belong to running JVMs, including classes loaded only on exit.
    return generation;
  } finally {
    await rm(staging, { recursive: true, force: true });
    await rm(manifest, { force: true });
  }
}
