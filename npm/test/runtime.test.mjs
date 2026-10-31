import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, mkdtemp, readFile, rm, writeFile, cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { publishRuntime, runtimeLib } from '../bin/runtime.mjs';

const tool = name => process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
const hasJdk = spawnSync(tool('javac'), ['-version']).status === 0;

test('rebuild preserves classes a running JVM loads only on exit', { skip: !hasJdk, timeout: 30_000 }, async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'openeden-runtime-'));
  let child;
  try {
    await mkdir(path.join(root, 'npm/bin'), { recursive: true });
    for (const file of ['openeden.mjs', 'runtime.mjs']) {
      await cp(fileURLToPath(new URL(`../bin/${file}`, import.meta.url)), path.join(root, 'npm/bin', file));
    }
    const source = path.join(root, 'source');
    const classes = path.join(root, 'classes');
    await mkdir(source);
    await mkdir(classes);
    await writeFile(path.join(root, 'MainKt.java'), `
      package io.openeden.cli;
      public class MainKt {
        public static void main(String[] args) throws Exception {
          System.out.println("ready");
          System.in.read();
          System.out.println(Class.forName("io.openeden.cli.Late").getMethod("value").invoke(null));
        }
      }`);
    async function build(value) {
      await writeFile(path.join(root, 'Late.java'), `
        package io.openeden.cli;
        public class Late { public static String value() { return "${value}"; } }`);
      for (const [name, args] of [
        ['javac', ['-d', classes, path.join(root, 'MainKt.java'), path.join(root, 'Late.java')]],
        ['jar', ['--create', '--file', path.join(source, 'cli-test.jar'), '-C', classes, '.']],
      ]) {
        const result = spawnSync(tool(name), args, { encoding: 'utf8' });
        assert.equal(result.status, 0, result.stderr);
      }
    }
    await build('old-exit-class');
    const first = await publishRuntime(root, source);
    const oldLib = runtimeLib(root);
    const oldJar = await readFile(path.join(oldLib, 'cli-test.jar'));
    child = spawn(process.execPath, [path.join(root, 'npm/bin/openeden.mjs')], {
      env: process.env, stdio: ['pipe', 'pipe', 'pipe'],
    });
    let output = '';
    let errors = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { errors += chunk; });
    while (!output.includes('ready')) {
      if (child.exitCode !== null) assert.fail(errors);
      await new Promise(resolve => setTimeout(resolve, 10));
    }
    await build('new-exit-class');
    const second = await publishRuntime(root, source);
    assert.notEqual(second, first);
    assert.notEqual(runtimeLib(root), oldLib);
    assert.deepEqual(await readFile(path.join(oldLib, 'cli-test.jar')), oldJar);
    const exited = once(child, 'exit');
    child.stdin.end('\n');
    const [code] = await exited;
    assert.equal(code, 0, errors);
    assert.match(output, /old-exit-class/);
    assert.doesNotMatch(output, /new-exit-class/);
    const next = spawnSync(process.execPath, [path.join(root, 'npm/bin/openeden.mjs')], {
      env: process.env, input: '\n', encoding: 'utf8', timeout: 5_000,
    });
    assert.equal(next.status, 0, next.stderr);
    assert.match(next.stdout, /new-exit-class/);
    assert.equal(await publishRuntime(root, source), second);
  } finally {
    if (child && child.exitCode === null) {
      const exited = once(child, 'exit');
      child.kill();
      await exited;
    }
    await rm(root, { recursive: true, force: true });
  }
});

test('failed staging leaves the selected runtime unchanged', async () => {
  const root = await mkdtemp(path.join(tmpdir(), 'openeden-runtime-'));
  try {
    const source = path.join(root, 'source');
    await mkdir(source);
    await writeFile(path.join(source, 'cli-test.jar'), 'valid initial artifact');
    const first = await publishRuntime(root, source);
    await assert.rejects(publishRuntime(root, path.join(root, 'missing')));
    assert.equal(JSON.parse(await readFile(path.join(root, '.npm-runtime/cli/current.json'), 'utf8')).generation, first);
    assert.equal(await readFile(path.join(runtimeLib(root), 'cli-test.jar'), 'utf8'), 'valid initial artifact');
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
