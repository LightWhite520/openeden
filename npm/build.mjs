import { spawn } from 'node:child_process';
import { cp, mkdir, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

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
    const target = path.join(root, '.npm-runtime', 'cli');
    await rm(target, { recursive: true, force: true });
    await mkdir(target, { recursive: true });
    await cp(path.join(root, 'cli/build/install/openeden/lib'), path.join(target, 'lib'), { recursive: true });
  } catch (error) { console.error(error.message); process.exitCode = 1; }
});
