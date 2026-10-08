const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'dalmaru-dev-jar-'));
try {
  const mods = path.join(fixture, 'server-project/run/mods');
  const wrapper = path.join(fixture, 'tools/autoplay/server-run.js');
  fs.mkdirSync(mods, { recursive: true });
  fs.mkdirSync(path.dirname(wrapper), { recursive: true });
  fs.copyFileSync(path.join(__dirname, 'server-run.js'), wrapper);
  fs.writeFileSync(path.join(mods, 'dalmaru-0.1.0.jar'), 'guard fixture');

  const result = spawnSync(process.execPath, [wrapper], { encoding: 'utf8', timeout: 5000 });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Move run\/mods\/dalmaru-0\.1\.0\.jar/);
  assert.equal(fs.existsSync(path.join(path.dirname(wrapper), 'logs')), false);
  console.log('Development JAR preflight verified without starting a server.');
} finally {
  assert.equal(path.dirname(fs.realpathSync(fixture)), fs.realpathSync(os.tmpdir()));
  fs.rmSync(fixture, { recursive: true });
}
