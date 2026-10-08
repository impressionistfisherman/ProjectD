const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'dalmaru-dev-jar-'));
try {
  const preload = path.join(fixture, 'no-server.cjs');
  fs.writeFileSync(preload, `
    const { EventEmitter } = require('node:events');
    const { PassThrough } = require('node:stream');
    require('node:child_process').spawn = () => {
      require('node:fs').writeFileSync(process.env.SPAWN_MARKER, 'reached');
      const child = new EventEmitter();
      child.pid = 1;
      child.stdout = new PassThrough();
      child.stderr = new PassThrough();
      child.stdin = new PassThrough();
      process.nextTick(() => child.emit('exit', 0));
      return child;
    };
  `);
  const cases = [
    { name: 'dalmaru-0.1.0.jar', entries: { 'fabric.mod.json': '{"id":"dalmaru"}' }, blocked: true },
    { name: 'old-dalmaru.jar', entries: { 'fabric.mod.json': '{"id":"dalmaru"}' }, blocked: true },
    { name: 'backup.jar', entries: { 'META-INF/mods.toml': '[[mods]]\nmodId = "dalmaru"\n' }, blocked: true },
    { name: 'dalmaru-api.jar', entries: {
      'fabric.mod.json': '{"id":"dalmaru-api"}',
      'META-INF/mods.toml': '# modId = "dalmaru"\n[[mods]]\nmodId = "dalmaru-api"\n[[dependencies.dalmaru-api]]\nmodId = "dalmaru"\n',
    }, blocked: false },
  ];
  for (const [index, test] of cases.entries()) {
    const caseRoot = path.join(fixture, String(index));
    const mods = path.join(caseRoot, 'server-project/run/mods');
    const wrapper = path.join(caseRoot, 'tools/autoplay/server-run.js');
    fs.mkdirSync(mods, { recursive: true });
    fs.mkdirSync(path.dirname(wrapper), { recursive: true });
    fs.copyFileSync(path.join(__dirname, 'server-run.js'), wrapper);
    const created = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', `
      $ErrorActionPreference = 'Stop'
      Add-Type -AssemblyName System.IO.Compression.FileSystem
      $archive = [IO.Compression.ZipFile]::Open($env:FIXTURE_JAR, 'Create')
      try {
        $entries = ConvertFrom-Json $env:FIXTURE_ENTRIES
        foreach ($property in $entries.PSObject.Properties) {
          $entry = $archive.CreateEntry($property.Name)
          $writer = New-Object IO.StreamWriter($entry.Open())
          try { $writer.Write($property.Value) } finally { $writer.Dispose() }
        }
      } finally { $archive.Dispose() }
    `], { encoding: 'utf8', timeout: 10000, env: {
      ...process.env, FIXTURE_JAR: path.join(mods, test.name), FIXTURE_ENTRIES: JSON.stringify(test.entries),
    } });
    assert.equal(created.status, 0, created.error?.message || created.stderr);
    const marker = path.join(caseRoot, 'spawned');
    const result = spawnSync(process.execPath, ['--require', preload, wrapper], {
      encoding: 'utf8', timeout: 10000, env: { ...process.env, DALMARU_DB_PASSWORD: 'test-only', SPAWN_MARKER: marker },
    });
    assert.equal(result.status, test.blocked ? 1 : 0, result.error?.message || result.stderr);
    assert.equal(fs.existsSync(marker), !test.blocked, test.name);
    assert.equal(fs.existsSync(path.join(path.dirname(wrapper), 'logs')), !test.blocked, test.name);
    if (test.blocked) assert.ok(result.stderr.includes(`Move run/mods/${test.name}`), result.stderr);
  }
  for (const password of [undefined, '']) {
    const caseRoot = path.join(fixture, password === undefined ? 'missing-password' : 'empty-password');
    const wrapper = path.join(caseRoot, 'tools/autoplay/server-run.js');
    const marker = path.join(caseRoot, 'spawned');
    fs.mkdirSync(path.dirname(wrapper), { recursive: true });
    fs.copyFileSync(path.join(__dirname, 'server-run.js'), wrapper);
    const env = { ...process.env, SPAWN_MARKER: marker };
    if (password === undefined) delete env.DALMARU_DB_PASSWORD;
    else env.DALMARU_DB_PASSWORD = password;
    const result = spawnSync(process.execPath, ['--require', preload, wrapper], { encoding: 'utf8', timeout: 10000, env });
    assert.equal(result.status, 1, result.error?.message || result.stderr);
    assert.ok(result.stderr.includes('DALMARU_DB_PASSWORD must be set'), result.stderr);
    assert.equal(fs.existsSync(marker), false);
    assert.equal(fs.existsSync(path.join(path.dirname(wrapper), 'logs')), false);
  }
  console.log('JAR metadata guard and missing/empty DB password guard PASS; no server started.');
} finally {
  assert.equal(path.dirname(fs.realpathSync(fixture)), fs.realpathSync(os.tmpdir()));
  fs.rmSync(fixture, { recursive: true });
}
