// Headless offline bot for the closed beacon tower (F6) loop.
// Sends the same dalmaru:* payloads as DalmaruClient; reads commands appended to logs/bot.cmd.
// Commands: list | create <slot> <name> | select <uuid> | enter <FIRST_STEP|TRAINING> [requestId]
//           goto <x> <z> | fight [floorX floorY] | stay <x> <z> | stop | quit
const mineflayer = require('mineflayer');
const fs = require('fs');
const path = require('path');

const name = process.argv[2] || 'F6Bot';
const logs = path.join(__dirname, 'logs');
fs.mkdirSync(logs, { recursive: true });
const cmdFile = path.join(logs, `${name}.cmd`);
fs.writeFileSync(cmdFile, '');
const logFile = fs.createWriteStream(path.join(logs, `${name}.log`), { flags: 'a' });
const log = (...a) => { const line = `[${new Date().toISOString()}] ${a.join(' ')}`; console.log(line); logFile.write(line + '\n'); };

// --- Minecraft StreamCodec primitives used by DalmaruPayloads ---
const varLong = (v) => { v = BigInt.asUintN(64, BigInt(v)); const out = []; do { let b = Number(v & 0x7fn); v >>= 7n; if (v) b |= 0x80; out.push(b); } while (v); return Buffer.from(out); };
const varInt = (v) => varLong(BigInt.asUintN(32, BigInt(v)));
const str = (s) => { const b = Buffer.from(s, 'utf8'); return Buffer.concat([varInt(b.length), b]); };
const uuidParts = (u) => { const h = u.replace(/-/g, ''); return [BigInt.asIntN(64, BigInt('0x' + h.slice(0, 16))), BigInt.asIntN(64, BigInt('0x' + h.slice(16)))]; };
class Reader {
  constructor(buf) { this.b = buf; this.o = 0; }
  varLong() { let r = 0n, s = 0n, b; do { b = this.b[this.o++]; r |= BigInt(b & 0x7f) << s; s += 7n; } while (b & 0x80); return BigInt.asIntN(64, r); }
  varInt() { return Number(BigInt.asIntN(32, this.varLong())); }
  bool() { return this.b[this.o++] !== 0; }
  str() { const n = this.varInt(); const s = this.b.toString('utf8', this.o, this.o + n); this.o += n; return s; }
  uuid() { const h = (x) => BigInt.asUintN(64, x).toString(16).padStart(16, '0'); const s = h(this.varLong()) + h(this.varLong()); return `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20)}`; }
}
const decoders = {
  'dalmaru:welcome': (r) => ({ protocol: r.varInt() }),
  'dalmaru:character_selection_ready': (r) => ({ ready: r.bool() }),
  'dalmaru:character_list_response': (r) => { const o = { requestId: r.varLong(), status: r.str(), characters: [] }; for (let n = r.varInt(); n > 0; n--) o.characters.push({ id: r.uuid(), slot: r.varInt(), name: r.str(), active: r.bool() }); return o; },
  'dalmaru:character_select_response': (r) => ({ requestId: r.varLong(), id: r.uuid(), accepted: r.bool(), replayed: r.bool(), status: r.str() }),
  'dalmaru:character_create_response': (r) => ({ requestId: r.varLong(), id: r.uuid(), slot: r.varInt(), name: r.str(), accepted: r.bool(), replayed: r.bool(), status: r.str() }),
  'dalmaru:run_response': (r) => ({ requestId: r.varLong(), accepted: r.bool(), runId: r.uuid(), partySize: r.varInt(), scalingTier: r.varInt(), state: r.str(), status: r.str() }),
};

const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: name, auth: 'offline', version: '26.1' });
const send = (channel, data) => { bot._client.write('custom_payload', { channel: `dalmaru:${channel}`, data }); log('SEND', channel, data.toString('hex')); };
let requestId = BigInt(Date.now());
const nextId = () => ++requestId;

bot._client.on('custom_payload', (p) => {
  if (!p.channel.startsWith('dalmaru:')) return;
  const dec = decoders[p.channel];
  let body = p.data.toString('hex');
  if (dec) try { body = JSON.stringify(dec(new Reader(p.data)), (k, v) => (typeof v === 'bigint' ? v.toString() : v)); } catch (e) { body += ' decodeError=' + e.message; }
  if (p.channel !== 'dalmaru:combat_state_response') log('RECV', p.channel, body);
});
bot.once('login', () => { log('LOGIN', bot.username); send('hello', varInt(27)); });
bot.on('spawn', () => log('SPAWN', fmtPos()));
bot.on('messagestr', (m) => log('CHAT', m));
bot.on('health', () => log('HEALTH', bot.health.toFixed(1)));
bot.on('death', () => log('DEATH', fmtPos()));
bot.on('kicked', (r) => log('KICKED', JSON.stringify(r)));
bot.on('end', (r) => { log('END', r); process.exit(0); });
bot.on('error', (e) => log('ERROR', e.message));
bot.on('entityDead', (e) => log('ENTITY_DEAD', e.id, e.name, entityLabel(e)));

function fmtPos() { const p = bot.entity?.position; return p ? `${p.x.toFixed(1)},${p.y.toFixed(1)},${p.z.toFixed(1)}` : '?'; }
function entityLabel(e) { const n = e.metadata?.[2]; return n ? JSON.stringify(n).slice(0, 80) : ""; }

let task = null;
function walkTo(x, z, then) {
  clearInterval(task);
  task = setInterval(() => {
    const p = bot.entity.position;
    if (Math.hypot(p.x - x, p.z - z) < 0.8) { bot.clearControlStates(); clearInterval(task); task = null; log('ARRIVED', fmtPos()); if (then) then(); return; }
    bot.lookAt(p.offset(x - p.x, 0, z - p.z).offset(0, bot.entity.height, 0), true);
    bot.setControlState('forward', true);
  }, 100);
}
// Only zombies inside the run corridor (x within 9 of the floor center, y >= floor) count as bosses.
function fight(cx, fy) {
  clearInterval(task);
  let last = 0, lastReport = 0;
  task = setInterval(() => {
    const target = bot.nearestEntity((e) => e.name === 'zombie' && (cx === undefined || (Math.abs(e.position.x - cx) <= 9 && e.position.y >= fy)));
    if (!target) { bot.clearControlStates(); return; }
    if (Date.now() - lastReport > 5000) { lastReport = Date.now(); log('BOSS', target.id, entityLabel(target), 'hp', target.metadata?.[9], 'at', target.position.toString(), 'me', fmtPos(), bot.health.toFixed(1)); }
    const d = bot.entity.position.distanceTo(target.position);
    bot.lookAt(target.position.offset(0, target.height * 0.8, 0), true);
    bot.setControlState('forward', d > 2.5);
    if (d < 3.2 && Date.now() - last > 650) { bot.attack(target); last = Date.now(); }
  }, 100);
}

const handlers = {
  list: () => send('character_list_request', varLong(nextId())),
  create: (slot, display) => send('character_create_request', Buffer.concat([varLong(nextId()), varInt(Number(slot)), str(display)])),
  select: (id) => { const [m, l] = uuidParts(id); send('character_select_request', Buffer.concat([varLong(nextId()), varLong(m), varLong(l)])); },
  enter: (difficulty, id) => send('run_enter_request', Buffer.concat([varLong(id ? BigInt(id) : nextId()), str(difficulty)])),
  goto: (x, z) => walkTo(Number(x), Number(z)),
  stay: (x, z) => walkTo(Number(x), Number(z)),
  fight: (cx, fy) => fight(cx === undefined ? undefined : Number(cx), Number(fy ?? 100)),
  stop: () => { clearInterval(task); task = null; bot.clearControlStates(); },
  pos: () => log('POS', fmtPos(), 'hp', bot.health),
  chat: (...m) => bot.chat(m.join(' ')),
  quit: () => bot.quit(),
};
let done = 0;
setInterval(() => {
  const lines = fs.readFileSync(cmdFile, 'utf8').split('\n').map((s) => s.trim()).filter(Boolean);
  for (; done < lines.length; done++) {
    const [cmd, ...args] = lines[done].split(/\s+/);
    log('CMD', lines[done]);
    try { (handlers[cmd] || (() => log('UNKNOWN', cmd)))(...args); } catch (e) { log('CMD_ERROR', e.message); }
  }
}, 300);
handlers.scan = () => { for (const e of Object.values(bot.entities)) if (e.name === 'zombie') log('ZOMBIE', e.id, entityLabel(e), 'hp', e.metadata?.[9], 'at', e.position.toString()); };
handlers.findfire = () => { const b = bot.findBlock({ matching: (blk) => blk.name.includes('campfire'), maxDistance: 64 }); log('FIRE', b ? b.position.toString() : 'none'); };
