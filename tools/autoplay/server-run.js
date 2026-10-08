// Runs `gradlew runServer` with a controllable stdin: lines appended to logs/server.cmd are sent to the console.
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '../../server-project');
const mods = path.join(root, 'run', 'mods');
const deployed = fs.existsSync(mods) && fs.readdirSync(mods).find(name => /^dalmaru(?:-.*)?\.jar$/i.test(name));
if (deployed) throw new Error(`Move run/mods/${deployed} outside run/mods before starting the development server.`);

const logs = path.join(__dirname, 'logs');
fs.mkdirSync(logs, { recursive: true });
const cmdFile = path.join(logs, 'server.cmd');
fs.writeFileSync(cmdFile, '');
const out = fs.createWriteStream(path.join(logs, 'server.console.log'));

// cmd.exe cannot resolve gradlew.bat from this OneDrive cwd, so start the wrapper jar directly.
const child = spawn('java', ['-cp', 'gradle/wrapper/gradle-wrapper.jar', 'org.gradle.wrapper.GradleWrapperMain', '--console=plain', 'runServer'], {
  cwd: root,
  env: { ...process.env, DALMARU_DB_PASSWORD: process.env.DALMARU_DB_PASSWORD || 'postgres' },
});
fs.writeFileSync(path.join(logs, 'server.pid'), String(child.pid));
child.stdout.pipe(out);
child.stderr.pipe(out);

let sent = 0;
const timer = setInterval(() => {
  const lines = fs.readFileSync(cmdFile, 'utf8').split('\n').filter(Boolean);
  for (; sent < lines.length; sent++) child.stdin.write(lines[sent] + '\n');
}, 500);
child.on('exit', (code) => {
  clearInterval(timer);
  fs.writeFileSync(path.join(logs, 'server.exit.txt'), String(code));
  process.exit(code ?? 1);
});
