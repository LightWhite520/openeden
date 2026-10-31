import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const launcher = fileURLToPath(new URL('../bin/openeden.mjs', import.meta.url));
test('help works without Java or a build', () => {
  const result = spawnSync(process.execPath, [launcher, '--help'], { encoding: 'utf8' });
  assert.equal(result.status, 0);
  assert.match(result.stdout, /Java 21/);
});

test('unbuilt installation reports how to recover', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'openeden-npm-'));
  try {
    mkdirSync(path.join(root, 'npm/bin'), { recursive: true });
    const copy = path.join(root, 'npm/bin/openeden.mjs');
    cpSync(launcher, copy);
    const result = spawnSync(process.execPath, [copy], { encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /npm run build/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

test('missing Java reports an actionable error', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'openeden-npm-'));
  try {
    mkdirSync(path.join(root, 'npm/bin'), { recursive: true });
    mkdirSync(path.join(root, '.npm-runtime/cli/lib'), { recursive: true });
    const copy = path.join(root, 'npm/bin/openeden.mjs');
    cpSync(launcher, copy);
    const result = spawnSync(process.execPath, [copy], {
      encoding: 'utf8', env: { ...process.env, JAVA_HOME: path.join(root, 'missing-java') },
    });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Install Java 21/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
