#!/usr/bin/env node
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const args = process.argv.slice(2);
if (args[0] === '--help' || args[0] === '-h') {
  console.log('Usage: openeden [chat --message <text>|state]\nRequires Java 21+ and a separately running server.\nBuild from source with npm run build. Configure serverUrl in ~/.openeden/config.json.');
} else {
  const lib = path.join(root, '.npm-runtime', 'cli', 'lib');
  if (!existsSync(lib)) {
    console.error('OpenEden distribution missing. Run npm run build in the source checkout, or reinstall a built npm package.');
    process.exitCode = 1;
  } else {
    if (args.length === 0 && process.stdin.isTTY && process.stdout.isTTY) {
      console.clear();
    }
    const env = { ...process.env };
    env.OPENEDEN_SERVER_URL ??= 'http://127.0.0.1:8080';
    const java = env.JAVA_HOME
      ? path.join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
    const child = spawn(java, ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
      '-cp', path.join(lib, '*'), 'io.openeden.cli.MainKt', ...args], { env, stdio: 'inherit' });
    const forward = signal => { if (!child.killed) child.kill(signal); };
    const interrupt = () => forward('SIGINT');
    const terminate = () => forward('SIGTERM');
    process.on('SIGINT', interrupt);
    process.on('SIGTERM', terminate);
    child.on('error', error => {
      console.error(`Cannot start Java. Install Java 21+ and check JAVA_HOME/PATH. ${error.message}`);
      process.exitCode = 1;
    });
    child.on('exit', (code, signal) => {
      process.removeListener('SIGINT', interrupt);
      process.removeListener('SIGTERM', terminate);
      process.exitCode = code ?? (signal === 'SIGINT' ? 130 : 143);
    });
  }
}
