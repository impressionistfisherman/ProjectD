// Runs `gradlew runServer` with a controllable stdin: lines appended to logs/server.cmd are sent to the console.
const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

if (!process.env.DALMARU_DB_PASSWORD) throw new Error('DALMARU_DB_PASSWORD must be set before starting the development server.');

const root = path.resolve(__dirname, '../../server-project');
const mods = path.join(root, 'run', 'mods');
let deployed;
if (fs.existsSync(mods) && fs.readdirSync(mods).some(name => /\.jar$/i.test(name))) {
  // Windows provides ZIP reading through .NET; use metadata rather than a JAR filename.
  const inspected = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', String.raw`
    $ErrorActionPreference = 'Stop'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    function Read-ModMetadata($archive, $name) {
      $entry = $archive.GetEntry($name)
      if ($null -eq $entry) { return $null }
      $reader = New-Object IO.StreamReader($entry.Open())
      try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    function Is-DalmaruMod($toml) {
      $inMods = $false
      foreach ($line in ($toml -split '\r?\n')) {
        if ($line -match '^\s*\[') {
          $inMods = $line -cmatch '^\s*\[\[mods\]\]\s*(?:#.*)?$'
        } elseif ($inMods -and $line -cmatch '^\s*modId\s*=\s*(["''])dalmaru\1\s*(?:#.*)?$') {
          return $true
        }
      }
      return $false
    }
    foreach ($jar in Get-ChildItem -LiteralPath $env:DALMARU_MODS_DIR -File -Filter '*.jar') {
      $archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
      try {
        $fabric = Read-ModMetadata $archive 'fabric.mod.json'
        $toml = Read-ModMetadata $archive 'META-INF/mods.toml'
        if (($null -ne $fabric -and (ConvertFrom-Json $fabric).id -ceq 'dalmaru') -or
            ($null -ne $toml -and (Is-DalmaruMod $toml))) {
          Write-Output $jar.Name
          break
        }
      } finally { $archive.Dispose() }
    }
  `], { encoding: 'utf8', timeout: 30000, env: { ...process.env, DALMARU_MODS_DIR: mods } });
  if (inspected.error || inspected.status !== 0) {
    throw new Error(`Cannot inspect run/mods JAR metadata: ${inspected.error?.message || inspected.stderr}`);
  }
  deployed = inspected.stdout.trim();
}
if (deployed) throw new Error(`Move run/mods/${deployed} outside run/mods before starting the development server.`);

const logs = path.join(__dirname, 'logs');
fs.mkdirSync(logs, { recursive: true });
const cmdFile = path.join(logs, 'server.cmd');
fs.writeFileSync(cmdFile, '');
const out = fs.createWriteStream(path.join(logs, 'server.console.log'));

// cmd.exe cannot resolve gradlew.bat from this OneDrive cwd, so start the wrapper jar directly.
const child = spawn('java', ['-cp', 'gradle/wrapper/gradle-wrapper.jar', 'org.gradle.wrapper.GradleWrapperMain', '--console=plain', 'runServer'], {
  cwd: root,
  env: process.env,
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
