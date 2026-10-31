import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { publishRuntime } from './bin/runtime.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const windows = process.platform === 'win32';
// Fixed command only: never interpolate user arguments into a shell.
const child = spawn(windows ? 'cmd.exe' : './gradlew', windows
  ? ['/d', '/s', '/c', 'gradlew.bat :cli:installDist']
  : [':cli:installDist'], { cwd: root, stdio: 'inherit' });
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', async code => {
  if (code !== 0) { process.exitCode = code ?? 1; return; }
  try {
    await publishRuntime(root, path.join(root, 'cli/build/install/openeden/lib'));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
});
