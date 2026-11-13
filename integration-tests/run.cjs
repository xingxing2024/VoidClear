// Isolated loopback server and two real protocol clients. Never uses production data.
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const assert = require('node:assert/strict');
const { sweep: completedSweep } = require('./sweep-completion.cjs');
const { createRequire } = require('node:module');
const req = createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'));
const mc = req('minecraft-protocol');
const nbt = req('prismarine-nbt');
const data = req('minecraft-data')('1.21.8');
const dir = path.resolve(__dirname, process.env.VOIDCLEAR_LIVE_DIR || '../target/live-server');
const source = path.resolve(__dirname, '../../SimpleFly/test-server');
const sleep = ms => new Promise(r => setTimeout(r, ms));
const runStamp = new Date().toISOString().replace(/[:.]/g, '-');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR || '../target/VoidClear-3.0.0.jar');
const candidateSha256 = require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex');
fs.mkdirSync(path.join(dir, 'plugins/VoidClear'), { recursive: true });
for (const file of ['server.jar', 'eula.txt']) fs.copyFileSync(path.join(source, file), path.join(dir, file));
for (const name of ['cache', 'libraries', 'versions']) {
  fs.cpSync(path.join(source, name), path.join(dir, name), { recursive: true, force: false });
}
fs.copyFileSync(path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR || '../target/VoidClear-3.0.0.jar'), path.join(dir, 'plugins/VoidClear.jar'));
fs.copyFileSync(path.resolve(__dirname, '../../minecraft-fakeplayer-next-master/lib/PlaceholderAPI-2.11.6.jar'), path.join(dir, 'plugins/PlaceholderAPI.jar'));
fs.copyFileSync(path.resolve(__dirname, '../target/probe/VoidClearProbe.jar'), path.join(dir, 'plugins/VoidClearProbe.jar'));
fs.copyFileSync(path.resolve(__dirname, '../src/main/resources/messages.yml'), path.join(dir, 'plugins/VoidClear/messages.yml'));
const menuPath = path.join(dir, 'plugins/VoidClear/menus/bin.yml');
fs.mkdirSync(path.dirname(menuPath), { recursive: true });
const defaultMenu = fs.readFileSync(path.resolve(__dirname, '../src/main/resources/menus/bin.yml'), 'utf8');
fs.writeFileSync(menuPath, defaultMenu);
fs.writeFileSync(path.join(dir, 'server.properties'), 'server-ip=127.0.0.1\nserver-port=25652\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-players=4\n');
const configPath = path.join(dir, 'plugins/VoidClear/config.yml');
let config = fs.readFileSync(path.resolve(__dirname, '../src/main/resources/config.yml'), 'utf8');
config = config.replace('period: 3600', 'period: 36000');
const baseConfig = config;
function setBinTimer(enabled, period) {
  config = baseConfig.replace(/(trash:\s*\n\s+clear:[\s\S]*?enable: )false/, `$1${enabled}`)
    .replace(/(\n    period: )7200/, `$1${period}`);
  fs.writeFileSync(configPath, config);
  consoleCommand('vc reload');
}
fs.writeFileSync(configPath, config);
const log = fs.createWriteStream(path.join(dir, 'integration.log'), { flags: 'a' });
log.write(`\nTEST START ${runStamp}\n`);
let output = '';
let exited = false;
function startServer() {
  exited = false;
  const child = spawn('java', ['-Xms256M', '-Xmx1024M', '-Dvoidclear.probe=true', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=https://maven.aliyun.com/repository/central', '-jar', 'server.jar', '--nogui'], { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'] });
  for (const stream of [child.stdout, child.stderr]) stream.on('data', b => { output += b; log.write(b); });
  child.once('exit', () => { exited = true; });
  return child;
}
let server = startServer();
const consoleCommand = s => server.stdin.write(s + '\n');
const clients = [];
const checks = [];
let failure;
const timeout = setTimeout(() => {
  const owned = server;
  failure = 'Paper integration watchdog expired';
  consoleCommand('stop'); process.exitCode = 1;
  const force = setTimeout(() => { if (owned.exitCode === null && owned.signalCode === null) owned.kill('SIGKILL'); }, 30000);
  force.unref();
}, 360000);
function text(v) {
  if (v?.type) v = nbt.simplify(v);
  if (typeof v === 'string') { try { return text(JSON.parse(v)); } catch { return v; } }
  if (Array.isArray(v)) return v.map(text).join('');
  return v && typeof v === 'object' ? (v.text || v.translate || '') + text(v.extra || []) : '';
}
async function until(fn, label, limit = 15000) {
  const end = Date.now() + limit;
  while (Date.now() < end) { if (fn()) return; if (exited) throw new Error('Server exited: ' + output.slice(-5000)); await sleep(50); }
  throw new Error('Timeout: ' + label + '\n' + output.slice(-3000));
}
async function connect(username) {
  const c = mc.createClient({ host: '127.0.0.1', port: 25652, username, auth: 'offline', version: '1.21.8', disableChatSigning: true });
  clients.push(c); c.window = null; c.chats = [];
  c.on('error', e => console.log('CLIENT', username, e.message));
  c.on('packet', (d, m) => {
    if (['window_items', 'set_slot', 'set_cursor_item'].includes(m.name))
      log.write(JSON.stringify({ inventoryPacketAt: Date.now(), player: username, packet: m.name, data: d }) + '\n');
    if (m.name === 'position') {
      c.write('teleport_confirm', { teleportId: d.teleportId });
      c.write('position_look', { x: d.x, y: d.y, z: d.z, yaw: d.yaw, pitch: d.pitch, flags: { onGround: false, hasHorizontalCollision: false } });
    }
    if (m.name === 'update_health') c.health = d.health;
    if (m.name === 'open_window') { c.window = { id: d.windowId, title: text(d.windowTitle), items: [], state: 0 }; }
    if (m.name === 'window_items' && c.window?.id === d.windowId) { c.window.items = d.items; c.window.state = d.stateId; c.window.cursor = d.carriedItem; }
    if (m.name === 'set_slot' && c.window?.id === d.windowId) { c.window.items[d.slot] = d.item; c.window.state = d.stateId; }
    if (m.name === 'set_slot' && d.windowId === -1 && c.window) c.window.cursor = d.item;
    if (m.name === 'set_cursor_item' && c.window) c.window.cursor = d.contents;
    if (m.name === 'close_window') c.window = null;
    if (m.name === 'system_chat') c.chats.push(text(d.content));
  });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Login timed out: ' + username)), 30000);
    c.once('playerJoin', () => { clearTimeout(timer); resolve(); });
    c.on('packet', (d, m) => { if (m.name === 'kick_disconnect' || m.name === 'disconnect') { clearTimeout(timer); reject(new Error(JSON.stringify(d))); } });
    c.once('kicked', r => { clearTimeout(timer); reject(new Error(JSON.stringify(r))); });
    c.once('error', e => { clearTimeout(timer); reject(e); });
  });
  await sleep(300); return c;
}
async function open(c) { const old = c.window; c.chat('/vc trash'); await until(() => c.window && c.window !== old && c.window.items.length >= 54, 'open bin'); await sleep(200); }
async function click(c, slot, mode = 0, mouseButton = 0) {
  const w = c.window;
  log.write(JSON.stringify({ clickAt: Date.now(), player: c.username, window: w.id, state: w.state, slot, mode, mouseButton }) + '\n');
  c.write('window_click', { windowId: w.id, stateId: w.state, slot, mouseButton, mode, changedSlots: [], cursorItem: undefined });
  const token = `${Date.now()}_${slot}_${mode}_${mouseButton}`;
  c.chat('/vcprobe clientsync ' + token);
  await until(() => c.chats.some(line => line.includes('VC_CLIENT_SYNC_' + token)), 'owner-thread click barrier');
  await sleep(500);
}
async function check(name, action) { await action(); checks.push(name); console.log('PASS', name); }
const count = item => item?.itemCount || 0;
const total = (c, id, from = 0, to = 90) => c.window.items.slice(from, to).reduce((n, i) => n + (i?.itemId === data.itemsByName[id].id ? count(i) : 0), 0);
async function summon(id, amount, extra = '') {
  consoleCommand(`execute at BinTestA run summon item ~ ~3 ~ {Item:{id:"minecraft:${id}",count:${amount}${extra}},PickupDelay:32767}`);
}
async function sweep() {
  await sleep(300);
  await completedSweep({ dir, cmd: consoleCommand }, until);
  await sleep(1200); // Heartbeat publishes the completed bin to both viewers.
}
async function wipe(a, b) { consoleCommand('vc trash clear confirm'); await sleep(400); await open(a); await open(b); }
async function papi(c, key) {
  const start = c.chats.length;
  c.chat(`/papi parse me VC_PAPI_${key}=%voidclear_${key}%`);
  await until(() => c.chats.slice(start).some(line => line.includes(`VC_PAPI_${key}=`)), 'PAPI ' + key);
  const line = c.chats.slice(start).find(line => line.includes(`VC_PAPI_${key}=`));
  const value = line.split(`VC_PAPI_${key}=`)[1];
  assert(!value.includes('%voidclear_'), 'Unresolved PAPI variable: ' + key);
  return value;
}
(async () => {
  try {
    await until(() => output.includes('Done ('), 'server ready', 180000);
    assert(!output.includes('Error occurred while enabling VoidClear'), output.slice(-6000));
    consoleCommand('gamerule doMobSpawning false');
    consoleCommand('gamerule doMobLoot false');
    // Exact global item totals require ambient block updates not to add unrelated drops.
    consoleCommand('gamerule doTileDrops false');
    consoleCommand('gamerule randomTickSpeed 0');
    let a = await connect('BinTestA'); let b = await connect('BinTestB');
    consoleCommand('op BinTestA'); consoleCommand('op BinTestB');
    consoleCommand('gamemode survival BinTestA'); consoleCommand('gamemode survival BinTestB');
    consoleCommand('tp BinTestA 0 -60 0'); consoleCommand('tp BinTestB 5 -60 0');
    consoleCommand('clear BinTestA'); consoleCommand('clear BinTestB');
    consoleCommand('kill @e[type=item]'); consoleCommand('vc trash clear confirm');
    await sleep(500);
    await check('real PlaceholderAPI registers and resolves all 17 variables', async () => {
      for (const key of ['sweep_seconds', 'sweep_time', 'sweep_at', 'sweep_mode', 'sweep_active',
        'sweep_last_removed', 'sweep_last_collected', 'sweep_last_duration', 'trash_count', 'trash_stacks',
        'trash_capacity', 'trash_free_slots', 'trash_clear_enabled', 'trash_clear_mode',
        'trash_clear_seconds', 'trash_clear_time', 'trash_clear_at']) {
        const value = await papi(a, key);
        assert(value.length > 0, key);
      }
      assert.equal(await papi(a, 'trash_count'), '0');
      assert.equal(await papi(a, 'trash_clear_enabled'), 'false');
      assert.equal(await papi(a, 'trash_clear_time'), '未开启');
    });
    await check('two clients see same chest; decorative pane cannot be taken', async () => {
      await open(a); await open(b);
      assert(count(a.window.items[46]) > 0);
      const before = JSON.stringify(a.window.items[46]);
      await click(a, 46); assert.equal(JSON.stringify(a.window.items[46]), before);
      assert.equal(JSON.stringify(b.window.items[46]), before);
      for (const [mode, button] of [[1, 0], [2, 0], [2, 40], [4, 1], [3, 2]]) {
        await click(a, 46, mode, button);
        assert.equal(JSON.stringify(a.window.items[46]), before);
        assert.equal(count(a.window.cursor), 0);
      }
    });
    await check('swept stacks merge and both viewers update', async () => {
      consoleCommand('execute at BinTestA run summon zombie ~4 ~ ~ {Tags:["vc_test_zombie"],NoAI:1b,PersistenceRequired:1b}');
      consoleCommand('execute at BinTestA run summon cow ~4 ~ ~ {Tags:["vc_test_cow"],NoAI:1b,PersistenceRequired:1b}');
      consoleCommand('execute at BinTestA run summon item ~ ~3 ~ {Item:{id:"minecraft:diamond",count:12},PickupDelay:32767}');
      consoleCommand('execute at BinTestA run summon item ~2 ~3 ~ {Item:{id:"minecraft:diamond",count:20},PickupDelay:32767}');
      await sleep(300); consoleCommand('vc sweep'); await sleep(1800);
      await until(() => count(a.window.items[0]) === 32 && count(b.window.items[0]) === 32, 'live refresh without reopening');
      assert.equal(count(a.window.items[0]), 32); assert.equal(count(b.window.items[0]), 32);
      consoleCommand('execute if entity @e[tag=vc_test_zombie] run say VC_TEST_ZOMBIE_NOT_CLEARED');
      consoleCommand('execute if entity @e[tag=vc_test_cow] run say VC_TEST_COW_PROTECTED');
      await until(() => output.includes('[Not Secure] [Server] VC_TEST_COW_PROTECTED') || output.includes('[Server] VC_TEST_COW_PROTECTED'), 'ordinary entity rules still work');
      assert(!output.includes('[Server] VC_TEST_ZOMBIE_NOT_CLEARED'));
      consoleCommand('kill @e[tag=vc_test_cow]');
    });
    await check('later opener can shift-withdraw and other viewer sees removal', async () => {
      await click(b, 0, 1);
      await until(() => count(a.window.items[0]) === 0 && count(b.window.items[0]) === 0, 'journaled withdrawal publication', 15000);
      assert.equal(count(a.window.items[0]), 0);
      assert.equal(count(b.window.items[0]), 0);
      assert(b.window.items.slice(54).some(i => count(i) === 32));
    });
    await check('shift deposit, right split, sparse slots, drag and double-click conserve items', async () => {
      const diamond = b.window.items.findIndex((i, n) => n >= 54 && i?.itemId === data.itemsByName.diamond.id);
      await click(b, diamond, 1);
      await until(() => total(a, 'diamond', 0, 45) === 32, 'journaled deposit publication', 15000);
      assert.equal(total(a, 'diamond', 0, 45), 32);
      await click(a, 0, 0, 1);
      await until(() => count(a.window.cursor) === 16 && count(b.window.items[0]) === 16, 'split publication');
      assert.equal(count(a.window.cursor), 16);
      assert.equal(count(b.window.items[0]), 16);
      await click(a, 10);
      await until(() => count(a.window.items[10]) === 16, 'sparse placement publication');
      assert.equal(count(a.window.items[10]), 16);
      await click(b, 0, 1);
      assert.equal(count(a.window.items[10]), 16);
      await click(a, 10, 0, 1);
      await until(() => count(a.window.cursor) === 8, 'second split publication');
      assert.equal(count(a.window.cursor), 8);
      // Left drag over two empty slots (start, add, add, finish).
      for (const [slot, button] of [[-999, 0], [20, 1], [21, 1], [-999, 2]]) await click(a, slot, 5, button);
      await until(() => count(a.window.items[20]) === 4 && count(b.window.items[21]) === 4, 'drag publication');
      assert.equal(count(a.window.items[20]), 4, JSON.stringify({ stage: 'drag', a: a.window.items.slice(0, 45), b: b.window.items.slice(0, 45), cursor: a.window.cursor }));
      assert.equal(count(b.window.items[21]), 4);
      await click(a, 20);
      await until(() => count(a.window.cursor) === 4, 'drag pickup publication');
      assert.equal(count(a.window.cursor), 4, 'pickup after drag');
      await click(a, 20, 6);
      await until(() => count(a.window.cursor) === 16 && total(a, 'diamond', 0, 45) === 0, 'double click publication');
      assert.equal(count(a.window.cursor), 16);
      assert.equal(total(a, 'diamond', 0, 45), 0);
      await click(a, 5);
      await until(() => count(b.window.items[5]) === 16, 'other viewer placement publication');
      assert.equal(count(b.window.items[5]), 16);
      assert.equal(total(b, 'diamond'), 32);
      await open(a); assert.equal(total(a, 'diamond', 0, 45), 16);
      assert.equal(count(a.window.items[5]), 16, 'new viewers preserve existing slot positions');
    });
    await check('number-key and offhand swapping cannot move a decorative icon', async () => {
      const before = JSON.stringify(a.window.items[46]);
      await click(a, 46, 2, 0); await click(a, 46, 2, 40);
      assert.equal(JSON.stringify(a.window.items[46]), before);
      assert.equal(total(a, 'black_stained_glass_pane', 54), 0);
    });
    await check('manual wipe closes nonempty views and removes persisted contents', async () => {
      assert(total(a, 'diamond', 0, 45) > 0);
      consoleCommand('vc trash clear confirm');
      await until(() => !a.window && !b.window, 'manual wipe closes both viewers');
      await open(a); await open(b);
      assert.equal(total(a, 'diamond', 0, 45), 0);
    });
    await check('different item metadata stays separate; next and previous pages work', async () => {
      for (let i = 0; i < 46; i++) await summon('stone', 1, `,components:{"minecraft:custom_data":{test:${i}}}`);
      await sweep();
      assert.equal(total(a, 'stone', 0, 45), 45);
      await click(a, 53); await sleep(250);
      assert.equal(total(a, 'stone', 0, 45), 1);
      await click(a, 0, 1);
      await until(() => total(a, 'stone', 0, 45) === 0, 'last page withdrawal publication');
      assert.equal(total(a, 'stone', 0, 45), 0);
      await click(a, 45); await sleep(250);
      assert.equal(total(a, 'stone', 0, 45), 45);
      await click(b, 0, 1); await click(b, 1, 1);
      await until(() => total(b, 'stone', 0, 45) === 43 && total(b, 'stone', 54) === 2, 'two sequential withdrawals publication');
      assert.equal(total(b, 'stone', 0, 45), 43);
      assert.equal(total(b, 'stone', 54), 2);
      await wipe(a, b);
    });
    await check('overflow stacking and competing withdrawals preserve total quantity', async () => {
      await summon('gold_ingot', 40); await summon('gold_ingot', 40); await sweep();
      assert.equal(total(a, 'gold_ingot', 0, 45), 80);
      assert.equal(count(a.window.items[0]), 64);
      assert.equal(count(a.window.items[1]), 16);
      assert.equal(await papi(a, 'trash_count'), '80');
      assert.equal(await papi(a, 'trash_stacks'), '2');
      assert(JSON.stringify(a.window.items[49]).includes('80'), 'menu must count 80 items, not 2 stacks');
      const statusStart = a.chats.length;
      a.chat('/vc status'); await sleep(300);
      assert(a.chats.slice(statusStart).some(t => /垃圾箱.*80.*件/.test(t)), 'status must report 80 items');
      await Promise.all([click(a, 0, 1), click(b, 0, 1)]);
      assert.equal(total(a, 'gold_ingot', 54) + total(b, 'gold_ingot', 54) + total(a, 'gold_ingot', 0, 45), 80);
      await wipe(a, b);
    });
    await check('manual wipe reports actual quantity rather than occupied stacks', async () => {
      await summon('gold_ingot', 64); await summon('gold_ingot', 16); await sweep();
      const start = a.chats.length;
      a.chat('/vc trash clear confirm');
      await until(() => a.chats.slice(start).some(t => /删除.*80.*件/.test(t)), 'manual wipe item count');
      await open(a); await open(b);
      assert.equal(total(a, 'gold_ingot', 0, 45), 0);
    });
    await check('menu reload changes layout safely; invalid menu preserves settings, items and deadlines', async () => {
      await summon('copper_ingot', 13); await sweep();
      await click(a, 0); await click(a, 10);
      const deadlines = fs.readFileSync(path.join(dir, 'plugins/VoidClear/timer.yml'), 'utf8');
      consoleCommand('vc reload'); await sleep(400);
      assert.equal(count(a.window.items[10]), 13, 'unchanged reload must preserve sparse slots');
      assert(JSON.stringify(a.window.items[49]).includes('未开启'));
      fs.writeFileSync(menuPath, defaultMenu.replace("  - '         '", "  - '#########'").replace("Title: '&b虚空清理 &7丨 &e公共垃圾箱", "Title: '&b虚空清理 &7丨 &e重载测试箱"));
      consoleCommand('vc reload');
      await until(() => !a.window && !b.window, 'changed menu closes stale views');
      await open(a); await open(b);
      assert(a.window.title.includes('重载测试箱'));
      assert.equal(total(a, 'copper_ingot', 0, 45), 13);
      assert.equal(a.window.items[0].itemId, data.itemsByName.black_stained_glass_pane.id);
      await click(a, 9, 1);
      assert.equal(total(a, 'copper_ingot', 54), 13);
      fs.writeFileSync(menuPath, "Layout: [\n");
      consoleCommand('vc reload'); await sleep(400);
      assert(a.window && b.window, 'invalid reload must not close the active menu');
      assert.equal(fs.readFileSync(path.join(dir, 'plugins/VoidClear/timer.yml'), 'utf8'), deadlines);
      await open(b); assert(b.window.title.includes('重载测试箱'));
      fs.writeFileSync(menuPath, defaultMenu); consoleCommand('vc reload');
      await until(() => !a.window && !b.window, 'restore menu');
      await open(a); await open(b);
    });
    await check('full and partially full player inventories conserve withdrawal quantities', async () => {
      await summon('quartz', 10); await sweep();
      consoleCommand('clear BinTestB');
      consoleCommand('give BinTestB dirt 2304'); await sleep(500);
      const before = total(b, 'quartz', 54);
      await click(b, 0, 1);
      assert.equal(total(b, 'quartz', 0, 45), 10);
      assert.equal(total(b, 'quartz', 54), before);
      consoleCommand('item replace entity BinTestB hotbar.0 with quartz 60'); await sleep(500);
      await click(b, 0, 1);
      assert.equal(total(b, 'quartz', 0, 45), 6);
      assert.equal(total(b, 'quartz', 54), before + 64);
      consoleCommand('clear BinTestB'); await sleep(300);
      await wipe(a, b);
    });
    await check('disabled bin timer does not wipe stored items', async () => {
      await summon('emerald', 7); await sweep();
      await sleep(6000);
      assert.equal(total(a, 'emerald', 0, 45), 7);
      await click(a, 0, 0, 1);
      assert.equal(count(a.window.cursor), 4);
      assert.equal(count(b.window.items[0]), 3);
    });
    await check('automatic bin wipe has its own timer, closes stale views, and cannot resurrect', async () => {
      const sweepDeadline = fs.readFileSync(path.join(dir, 'plugins/VoidClear/timer.yml'), 'utf8').match(/sweep-deadline: (\d+)/)[1];
      const chatStart = a.chats.length;
      setBinTimer(true, 5);
      await until(() => !a.window && !b.window, 'automatic wipe closes viewers', 10000);
      assert(a.chats.slice(chatStart).some(t => t.includes('垃圾箱将在')), 'missing bin countdown warning');
      assert(a.chats.slice(chatStart).some(t => /垃圾箱已清空.*3.*件/.test(t)), 'automatic wipe must report the 3 remaining items');
      assert.equal(fs.readFileSync(path.join(dir, 'plugins/VoidClear/timer.yml'), 'utf8').match(/sweep-deadline: (\d+)/)[1], sweepDeadline);
      await open(a); assert(a.window.items.slice(0, 45).every(i => count(i) === 0));
      await open(b); assert(b.window.items.slice(0, 45).every(i => count(i) === 0));
      assert.equal(total(a, 'emerald', 54) + (a.window.cursor?.itemId === data.itemsByName.emerald.id ? count(a.window.cursor) : 0), 4,
        'closing bin must return cursor items to the player');
    });
    await check('reload disables wiping and changed periods restart only the bin timer', async () => {
      setBinTimer(false, 5); await sleep(300);
      await summon('iron_ingot', 9); await sweep(); await sleep(5500);
      assert.equal(total(b, 'iron_ingot', 0, 45), 9);
      setBinTimer(true, 20); await sleep(300);
      assert.equal(await papi(a, 'trash_clear_enabled'), 'true');
      assert(Number(await papi(a, 'trash_clear_seconds')) > 0);
      assert(JSON.stringify(b.window.items[49]).includes('清空倒计时'));
      await sleep(1100);
      assert(!JSON.stringify(b.window.items[49]).includes('未开启'), 'enabled timer must show live countdown');
      const timer = () => fs.readFileSync(path.join(dir, 'plugins/VoidClear/timer.yml'), 'utf8').match(/trash-deadline: (\d+)/)[1];
      const original = timer();
      consoleCommand('vc reload'); await sleep(300); assert.equal(timer(), original);
      setBinTimer(true, 3);
      await until(() => !b.window, 'changed period triggers wipe', 6000);
      await open(b); assert.equal(total(b, 'iron_ingot', 0, 45), 0);
    });
    await check('ordinary players cannot clear the bin', async () => {
      setBinTimer(false, 3); await sleep(300);
      await summon('lapis_lazuli', 11); await sweep();
      consoleCommand('deop BinTestB'); await sleep(300);
      const chatStart = b.chats.length;
      b.chat('/vc trash clear confirm'); await sleep(400);
      assert(b.chats.slice(chatStart).some(t => t.includes('权限')));
      assert.equal(total(b, 'lapis_lazuli', 0, 45), 11);
      consoleCommand('op BinTestB');
    });
    await check('clean server restart preserves bin contents and both independent deadlines', async () => {
      setBinTimer(true, 60); await sleep(300);
      const timersPath = path.join(dir, 'plugins/VoidClear/timer.yml');
      const deadlines = fs.readFileSync(timersPath, 'utf8');
      a.end('Restart test'); b.end('Restart test');
      const stopped = new Promise(resolve => server.once('exit', resolve));
      consoleCommand('stop'); await stopped;
      const start = output.length;
      server = startServer();
      await until(() => output.slice(start).includes('Done ('), 'restarted server ready', 90000);
      assert.equal(fs.readFileSync(timersPath, 'utf8'), deadlines, 'restart reset persisted deadlines');
      a = await connect('BinTestA'); b = await connect('BinTestB');
      if (a.health === 0) a.write('client_command', { actionId: 0 });
      if (b.health === 0) b.write('client_command', { actionId: 0 });
      await sleep(500);
      await open(a); await open(b);
      assert.equal(total(a, 'lapis_lazuli', 0, 45), 11);
      assert.equal(total(b, 'lapis_lazuli', 0, 45), 11);
      await click(b, 0, 1);
      assert.equal(total(a, 'lapis_lazuli', 0, 45), 0);
      assert.equal(total(b, 'lapis_lazuli', 54), 11);
    });
    await check('daily mode clears at a chosen clock time, rejects invalid reload and reschedules tomorrow', async () => {
      await summon('amethyst_shard', 5); await sweep();
      const timersPath = path.join(dir, 'plugins/VoidClear/timer.yml');
      const readDeadline = key => Number(fs.readFileSync(timersPath, 'utf8').match(new RegExp(key + ': (\\d+)'))[1]);
      const sweepDeadline = readDeadline('sweep-deadline');
      const target = new Date(); target.setSeconds(0, 0); target.setMinutes(target.getMinutes() + 1);
      const at = `${String(target.getHours()).padStart(2, '0')}:${String(target.getMinutes()).padStart(2, '0')}`;
      setBinTimer(true, 3);
      config = config.replace(/(trash:[\s\S]*?mode: )interval/, '$1daily')
        .replace(/(trash:[\s\S]*?\n    times:)\r?\n      - '04:00'\r?\n      - '16:00'/, `$1 ['${at}']`);
      fs.writeFileSync(configPath, config); consoleCommand('vc reload'); await sleep(300);
      const deadline = readDeadline('trash-deadline');
      assert(Math.abs(deadline - target.getTime()) < 1500, 'daily mode ignored selected clock time');
      assert.equal(readDeadline('sweep-deadline'), sweepDeadline);
      const validConfig = config;
      fs.writeFileSync(configPath, config.replace(`['${at}']`, "['25:99']"));
      consoleCommand('vc reload'); await sleep(300);
      assert.equal(readDeadline('trash-deadline'), deadline, 'bad reload changed active schedule');
      fs.writeFileSync(configPath, validConfig);
      const chatStart = a.chats.length;
      await until(() => !a.window && !b.window, 'daily scheduled wipe', 65000);
      await until(() => a.chats.slice(chatStart).some(t => /垃圾箱已清空.*5.*件/.test(t)), 'daily wipe quantity');
      assert(readDeadline('trash-deadline') - deadline > 23 * 3600 * 1000, 'daily mode did not schedule the next day');
      assert.equal(readDeadline('sweep-deadline'), sweepDeadline);
      await open(a); assert.equal(total(a, 'amethyst_shard', 0, 45), 0);
      setBinTimer(false, 3); await sleep(300);
    });
    await check('10000-slot live capacity, PAPI totals, page navigation and concurrent withdrawals', async () => {
      consoleCommand('clear BinTestA'); consoleCommand('clear BinTestB'); await sleep(300);
      consoleCommand('vcprobe capacity');
      await until(() => output.includes('VC_PROBE_CAPACITY_OK') || output.includes('VC_PROBE_FAILED'), 'live capacity probe', 60000);
      assert(!output.includes('VC_PROBE_FAILED'), output.slice(-6000));
      await open(a); await open(b);
      assert.equal(total(a, 'stone', 0, 45), 45 * 64);
      await Promise.all([click(a, 0, 1), click(b, 0, 1)]);
      const stored = Number(await papi(a, 'trash_count'));
      await until(() => stored + total(a, 'stone', 54) + total(b, 'stone', 54) === 640000,
        'capacity withdrawal inventory ACKs', 30000);
      assert.equal(stored + total(a, 'stone', 54) + total(b, 'stone', 54), 640000);
      for (let i = 0; i < 5; i++) { await click(a, 53); await click(a, 45); }
      consoleCommand('vc trash clear confirm');
      await until(() => !a.window && !b.window, 'capacity cleanup');
      assert.equal(await papi(a, 'trash_count'), '0');
    });
    await check('managed player drops reject hoppers, preserve partial claims and finish exactly once', async () => {
      consoleCommand('vcprobe playerdrop');
      await until(() => /VC_PLAYER_DROP_OK|VC_PROBE_FAILED/.test(output), 'managed drop probe');
      assert(!output.includes('VC_PROBE_FAILED'), output.slice(-6000));
    });
    await check('partial inventory, cursor and drop delivery failures lock the menu without retry', async () => {
      consoleCommand('vcprobe deliveryfault');
      await until(() => output.includes('VC_PROBE_DELIVERYFAULT_OK') || output.includes('VC_PROBE_FAILED'), 'delivery failure probe');
      assert(!output.includes('VC_PROBE_FAILED'), output.slice(-6000));
    });
    await check('corrupt page render preserves visible contents and locks all further commits', async () => {
      consoleCommand('vcprobe renderfault');
      await until(() => output.includes('VC_PROBE_RENDERFAULT_OK') || output.includes('VC_PROBE_FAILED'), 'render failure probe');
      assert(!output.includes('VC_PROBE_FAILED'), output.slice(-6000));
    });
    await check('decoded item cache preserves counts across merge, replacement, claim and clear', async () => {
      consoleCommand('vcprobe cache');
      await until(() => output.includes('VC_PROBE_CACHE_OK') || output.includes('VC_PROBE_FAILED'), 'cache regression probe');
      assert(!output.includes('VC_PROBE_FAILED'), output.slice(-6000));
    });
    await check('durable sweep receipts remove restored sources and reject stale replay', async () => {
      consoleCommand('vcprobe sweepreceipt');
      await until(() => output.includes('VC_SWEEP_RECEIPT_OK') || output.includes('VC_PROBE_FAILED'), 'sweep receipt probe');
      assert(!output.includes('VC_PROBE_FAILED'), output.slice(-6000));
    });
    assert(!/Could not pass event|Exception.*VoidClear|垃圾箱保存失败/.test(output), output.slice(-8000));
  } catch (failed) {
    failure = failed.stack || String(failed);
    throw failed;
  } finally {
    const capacityPath = path.join(dir, 'plugins/VoidClearProbe/capacity.json');
    const capacity = checks.some(name => name.startsWith('10000-slot')) && fs.existsSync(capacityPath)
      ? JSON.parse(fs.readFileSync(capacityPath, 'utf8')) : null;
    const result = JSON.stringify({ checks, capacity, runStamp, candidate, candidateSha256,
      ...(failure ? { error: failure } : {}), outputTail: output.slice(-6000) }, null, 2);
    fs.writeFileSync(path.join(__dirname, 'results.json'), result);
    fs.writeFileSync(path.join(__dirname, `results-${runStamp}.json`), result);
    clients.forEach(c => c.end('Test complete'));
    if (!exited) { const stopped = new Promise(resolve => server.once('exit', resolve)); consoleCommand('stop'); await stopped; }
    log.end(); clearTimeout(timeout);
  }
})().catch(e => { console.error(e); process.exitCode = 1; });
