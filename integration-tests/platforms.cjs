// Isolated Paper/MySQL and Folia/SQLite checks; never uses the running production database.
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { createRequire } = require('node:module');
const assert = require('node:assert/strict');
const req = createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'));
const mc = req('minecraft-protocol');
const nbt = req('prismarine-nbt');
const data = req('minecraft-data')('1.21.8');
const testNamespace = process.env.VOIDCLEAR_TEST_NAMESPACE || '';
assert(/^[A-Za-z0-9_]*$/.test(testNamespace), 'Unsafe isolated test namespace');
const root = path.resolve(__dirname, '../target/platform-tests');
const runtime = path.resolve(__dirname, '../../SimpleFly/test-server');
const results = { environment: { paper: '1.21.7', folia: '1.21.8-6', mysql: '5.7', java: '25' }, checks: [] };
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR || '../target/VoidClear-3.0.0-single-server.jar');
results.candidate = { path: candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex') };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const servers = [], clients = [];
const mode = process.argv[2] || 'all';
function text(v) {
  if (v?.type) v = nbt.simplify(v);
  if (typeof v === 'string') { try { return text(JSON.parse(v)); } catch { return v; } }
  if (Array.isArray(v)) return v.map(text).join('');
  return v && typeof v === 'object' ? (v.text || v.translate || '') + text(v.extra || []) : '';
}
async function until(fn, label, limit = 15000) {
  const end = Date.now() + limit;
  while (Date.now() < end) { if (fn()) return; await sleep(50); }
  throw new Error('Timeout: ' + label);
}
function quantities(c) {
  return ['gold_ingot', 'iron_ingot'].map(item => ({ item,
    player: total(c, item), bin: total(c, item, 54, 90) }));
}
async function check(name, action) {
  try { const evidence = await action(); results.checks.push({ name, passed: true, evidence }); console.log('PASS', name, evidence || ''); }
  catch (e) { results.checks.push({ name, passed: false, error: e.stack }); console.error('FAIL', name, e.message); }
}
function tablePrefix(name) {
  // Only the explicit two-server session fixture shares ownership across separate installations.
  const isolated = name.startsWith('resilience-') && !['resilience-session-a', 'resilience-session-b'].includes(name);
  const namespace = testNamespace + (isolated
    ? '_' + require('node:crypto').createHash('sha256').update(name).digest('hex').slice(0, 8) : '');
  const prefix = `platform_${namespace ? namespace + '_' : ''}`;
  assert(prefix.length + 'trash_items_player_drops'.length <= 64, 'Isolated table name exceeds MySQL limit');
  return prefix;
}
async function start(name, port, mysql, folia = false, mysqlPort = 33317, options = {}) {
  assert([33317, 33318].includes(mysqlPort), 'Only isolated database ports are allowed');
  const maxPlayers = options.maxPlayers || 4;
  assert(Number.isInteger(maxPlayers) && maxPlayers >= 1 && maxPlayers <= 32, 'Invalid test player limit');
  // Fail before writing fixtures when another suite already owns this server port.
  await new Promise((resolve, reject) => {
    const socket = require('node:net').createServer();
    socket.once('error', reject);
    socket.listen({ host: '127.0.0.1', port, exclusive: true }, () => socket.close(resolve));
  });
  for (const key of ['VOIDCLEAR_DB_URL', 'VOIDCLEAR_DB_USER', 'VOIDCLEAR_DB_PASSWORD'])
    assert(!process.env[key], 'Remove database environment override before isolated tests: ' + key);
  const dir = path.join(root, testNamespace ? `${testNamespace}-${name}` : name);
   const prefix = tablePrefix(name);
  fs.mkdirSync(path.join(dir, 'plugins/VoidClear/menus'), { recursive: true });
   for (const d of ['cache', 'libraries', 'versions']) fs.cpSync(path.join(runtime, d), path.join(dir, d), { recursive: true, force: false });
   if (folia) {
     const cached = path.join(root, 'folia/cache/mojang_1.21.8.jar');
     // Match Folia's embedded download-context before reusing a previous isolated runtime download.
     const hash = require('node:crypto').createHash('sha256').update(fs.readFileSync(cached)).digest('hex');
     assert.equal(hash, '2349d9a8f0d4be2c40e7692890ef46a4b07015e7955b075460d02793be7fbbe7', 'Unverified Folia runtime cache');
     fs.copyFileSync(cached, path.join(dir, 'cache/mojang_1.21.8.jar'));
   }
  fs.copyFileSync(folia ? path.join(root, 'folia.jar') : path.join(runtime, 'server.jar'), path.join(dir, 'server.jar'));
  fs.copyFileSync(path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR || '../target/VoidClear-3.0.0-single-server.jar'), path.join(dir, 'plugins/VoidClear.jar'));
  if (options.probe || name === 'paper-a' || name.startsWith('resilience-')) {
    fs.copyFileSync(path.resolve(__dirname, '../target/probe/VoidClearProbe.jar'), path.join(dir, 'plugins/VoidClearProbe.jar'));
    if (!folia) fs.copyFileSync(path.resolve(__dirname, '../../minecraft-fakeplayer-next-master/lib/PlaceholderAPI-2.11.6.jar'), path.join(dir, 'plugins/PlaceholderAPI.jar'));
  }
  for (const f of ['messages.yml', 'menus/bin.yml']) fs.copyFileSync(path.resolve(__dirname, '../src/main/resources', f), path.join(dir, 'plugins/VoidClear', f));
  let config = fs.readFileSync(path.resolve(__dirname, '../src/main/resources/config.yml'), 'utf8').replace('period: 3600', 'period: 36000');
  if (mysql) config = config.replace('type: sqlite', 'type: mysql')
    .replace(/jdbc-url: '[^']*'/, `jdbc-url: 'jdbc:mysql://127.0.0.1:${mysqlPort}/voidclear_test?useSSL=false&serverTimezone=UTC'`)
    .replace("username: ''", "username: 'voidclear'").replace("password: ''", "password: 'voidclear'")
     .replace('table-prefix: voidclear_', `table-prefix: ${prefix}`);
  fs.writeFileSync(path.join(dir, 'plugins/VoidClear/config.yml'), config);
  const written = fs.readFileSync(path.join(dir, 'plugins/VoidClear/config.yml'), 'utf8');
  assert.match(written, mysql ? /^  type: mysql$/m : /^  type: sqlite$/m);
  if (mysql) {
    assert(written.includes(`    jdbc-url: 'jdbc:mysql://127.0.0.1:${mysqlPort}/voidclear_test?useSSL=false&serverTimezone=UTC'`), 'Isolated JDBC replacement failed');
     for (const line of ["    username: 'voidclear'", "    password: 'voidclear'", `    table-prefix: ${prefix}`])
      assert(written.includes(line), 'Isolated configuration replacement failed: ' + line);
  }
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-players=${maxPlayers}\n`);
  if (folia) {
    fs.mkdirSync(path.join(dir, 'config'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'config/paper-global.yml'), '_version: 30\nthreaded-regions:\n  threads: 2\n');
  }
  if (name.startsWith('resilience-')) {
    fs.mkdirSync(path.join(dir, 'config'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'config/paper-global.yml'), (folia ? '_version: 30\nthreaded-regions:\n  threads: 2\n' : '_version: 29\n') + 'player-auto-save:\n  max-per-tick: -1\n  rate: -1\n');
  }
  const log = fs.createWriteStream(path.join(dir, 'platform.log'), { flags: 'a' });
  log.write(`\nTEST START ${new Date().toISOString()}\n`);
  const child = spawn('java', ['-Xms256M', '-Xmx1024M', '-Dvoidclear.probe=true', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=https://maven.aliyun.com/repository/central', '-jar', 'server.jar', '--nogui'], { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'] });
  const s = { name, port, dir, child, output: '', exited: false, config };
  s.done = new Promise(resolve => child.once('close', code => { s.exited = true; log.end(() => resolve(code)); }));
  for (const stream of [child.stdout, child.stderr]) stream.on('data', b => { s.output += b; log.write(b); });
  s.cmd = command => { if (!s.exited) child.stdin.write(command + '\n'); };
  child.on('error', e => { s.output += e.stack; });
  servers.push(s);
  await until(() => s.output.includes('Done (') || s.exited, name + ' startup', 240000);
  if (s.exited || /Error occurred while enabling VoidClear/.test(s.output)) throw new Error(name + ' failed: ' + s.output.slice(-5000));
  s.cmd('gamerule doMobSpawning false'); s.cmd('gamerule doMobLoot false');
  console.log('READY', name);
  return s;
}
async function connect(s, username) {
  const c = mc.createClient({ host: '127.0.0.1', port: s.port, username, auth: 'offline', version: '1.21.8', disableChatSigning: true });
  clients.push(c); c.s = s; c.window = null; c.chats = [];
  c.on('error', e => console.log('CLIENT', username, e.message));
  c.on('packet', (d, m) => {
    if (m.name === 'position') { c.pos = { x: d.x, z: d.z }; c.write('teleport_confirm', { teleportId: d.teleportId }); c.write('position_look', { x: d.x, y: d.y, z: d.z, yaw: d.yaw, pitch: d.pitch, flags: { onGround: false, hasHorizontalCollision: false } }); }
    if (m.name === 'update_health') c.health = d.health;
    if (m.name === 'open_window') c.window = { id: d.windowId, title: text(d.windowTitle), items: [], state: 0 };
    if (m.name === 'window_items' && c.window?.id === d.windowId) { c.window.items = d.items; c.window.state = d.stateId; c.window.cursor = d.carriedItem; }
    if (m.name === 'set_slot' && c.window?.id === d.windowId) { c.window.items[d.slot] = d.item; c.window.state = d.stateId; }
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
  s.cmd('op ' + username); s.cmd('gamemode creative ' + username); await sleep(400);
  return c;
}
async function open(c) { const old = c.window; c.chat('/vc trash'); await until(() => c.window && c.window !== old && c.window.items.length >= 90, 'open ' + c.username); await sleep(150); }
function sendClick(c, slot, mode = 1, button = 0) { c.write('window_click', { windowId: c.window.id, stateId: c.window.state, slot, mouseButton: button, mode, changedSlots: [], cursorItem: undefined }); }
const total = (c, item, from = 0, to = 45) => c.window.items.slice(from, to).reduce((n, i) => n + (i?.itemId === data.itemsByName[item].id ? i.itemCount : 0), 0);
async function reset(a, b) {
  a.chat('/clear @s'); b.chat('/clear @s'); a.chat('/vc trash clear confirm');
  await sleep(3200); await open(a); await open(b);
}
async function deposit(c, item, quantity) {
  const binBefore = total(c, item);
  c.chat(`/give @s ${item} ${quantity}`);
  await until(() => total(c, item, 54, 90) === quantity, 'give ' + item);
  const slot = c.window.items.findIndex((i, n) => n >= 54 && i?.itemId === data.itemsByName[item].id);
  sendClick(c, slot);
  await until(() => total(c, item) === binBefore + quantity && total(c, item, 54, 90) === 0, 'deposit ' + item);
}
async function stop(s) {
  if (s.exited) return;
  s.cmd('stop');
  const timeout = setTimeout(() => {
    process.exitCode = 1;
    console.error('FAIL graceful shutdown timeout:', s.name);
    s.child.kill('SIGKILL');
  }, 30000);
  try { await s.done; } finally { clearTimeout(timeout); }
}
async function sessionClosed(s, username) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    const offset = s.output.length;
    s.cmd('vcprobe sessionclosed ' + username);
    await until(() => /VC_SESSION_(CLOSED|PENDING)_|VC_PROBE_FAILED/.test(s.output.slice(offset)), 'session cleanup probe');
    assert(!s.output.slice(offset).includes('VC_PROBE_FAILED'), s.output.slice(offset));
    if (s.output.slice(offset).includes('VC_SESSION_CLOSED_' + username)) return;
    await sleep(100);
  }
  assert.fail('Session cleanup did not complete: ' + username);
}
async function settled(s, client) {
  for (let attempt = 0; attempt < 150; attempt++) {
    const offset = s.output.length;
    s.cmd('vcprobe dbpending ' + client.username);
    await until(() => /VC_DB_PENDING=|VC_PROBE_FAILED/.test(s.output.slice(offset)), 'transaction confirmation');
    const output = s.output.slice(offset);
    assert(!output.includes('VC_PROBE_FAILED'), output);
    if (output.includes('VC_DB_PENDING=false')) return;
    await sleep(100);
  }
  assert.fail('Transaction confirmation did not complete: ' + client.username);
}
async function multi() {
  const one = await start('paper-a', 25653, true);
  const two = await start('paper-b', 25654, true);
  const a = await connect(one, 'CrossA'), b = await connect(two, 'CrossB');
  await check('MySQL: real item cache merge, rollback, remote refresh and bytes-only UPDATE', async () => {
    one.cmd('vcprobe mysqlcache');
    await until(() => /VC_PROBE_MYSQLCACHE_OK|VC_PROBE_FAILED/.test(one.output), 'mysql cache probe', 30000);
    assert(!one.output.includes('VC_PROBE_FAILED'), one.output.slice(-7000));
    return { realItemSerialization: true, failureBeforeCommit: true };
  });
  await open(a); await open(b);
  await check('MySQL: cross-server deposit, polling sync and sequential withdrawal', async () => {
    await reset(a, b); await deposit(a, 'diamond', 64);
    await until(() => total(b, 'diamond') === 64, 'remote deposit sync');
    sendClick(b, 0); await sleep(3500);
    assert.equal(total(b, 'diamond', 54, 90), 64);
    assert.equal(total(a, 'diamond'), 0);
    return { deposited: 64, held: 64, remaining: 0 };
  });
  await check('MySQL: competing cross-server withdrawals conserve 64 items', async () => {
    const rounds = [];
    for (let i = 0; i < 3; i++) {
      await reset(a, b); await deposit(a, 'stone', 64);
      await until(() => total(a, 'stone') === 64 && total(b, 'stone') === 64, 'both see same stack');
      sendClick(a, 0); sendClick(b, 0); await sleep(3500);
      rounds.push({ held: total(a, 'stone', 54, 90) + total(b, 'stone', 54, 90), remainingA: total(a, 'stone'), remainingB: total(b, 'stone') });
    }
    results.withdrawals = rounds;
    assert(rounds.every(r => r.held + r.remainingA === 64 && r.remainingA === r.remainingB), JSON.stringify(rounds));
    return rounds;
  });
  await check('MySQL: simultaneous deposits preserve both stacks', async () => {
    await reset(a, b);
    a.chat('/give @s gold_ingot 32'); b.chat('/give @s iron_ingot 16');
    await until(() => total(a, 'gold_ingot', 54, 90) === 32 && total(b, 'iron_ingot', 54, 90) === 16, 'simultaneous deposit seed');
    const goldSlot = a.window.items.findIndex((stack, n) => n >= 54 && stack?.itemId === data.itemsByName.gold_ingot.id);
    const ironSlot = b.window.items.findIndex((stack, n) => n >= 54 && stack?.itemId === data.itemsByName.iron_ingot.id);
    assert(goldSlot >= 54 && ironSlot >= 54);
    sendClick(a, goldSlot); sendClick(b, ironSlot);
    await sleep(4000);
    // The losing stale page must reject without consuming the player's item; retry on the fresh page.
    const conserved = { gold: total(a, 'gold_ingot') + total(a, 'gold_ingot', 54, 90),
      iron: total(b, 'iron_ingot') + total(b, 'iron_ingot', 54, 90) };
    assert.deepEqual(conserved, { gold: 32, iron: 16 }, JSON.stringify({ A: quantities(a), B: quantities(b) }));
    // A rejected click leaves its player's stack intact. Reopen both views so the retry uses a current stateId.
    await open(a); await open(b);
    for (const [c, item] of [[a, 'gold_ingot'], [b, 'iron_ingot']]) {
      const slot = c.window.items.findIndex((stack, n) => n >= 54 && stack?.itemId === data.itemsByName[item].id);
      if (slot >= 54) sendClick(c, slot);
    }
    await until(() => total(a, 'gold_ingot') === 32 && total(b, 'iron_ingot') === 16, 'simultaneous deposit retry');
    await until(() => total(a, 'gold_ingot') === 32 && total(a, 'iron_ingot') === 16
      && total(b, 'gold_ingot') === 32 && total(b, 'iron_ingot') === 16, 'simultaneous deposit final sync');
    const observed = { goldA: total(a, 'gold_ingot'), goldB: total(b, 'gold_ingot'), ironA: total(a, 'iron_ingot'), ironB: total(b, 'iron_ingot') };
    assert.deepEqual(observed, { goldA: 32, goldB: 32, ironA: 16, ironB: 16 }); return observed;
  });
  await check('MySQL: remote clear blocks stale withdrawal', async () => {
    await reset(a, b); await deposit(a, 'emerald', 8);
    await until(() => total(b, 'emerald') === 8, 'remote emerald');
    a.chat('/vc trash clear confirm'); await sleep(350);
    sendClick(b, 0); await sleep(3500);
    const held = total(b, 'emerald', 54, 90);
    assert.equal(held, 0, 'remote clear still allowed ' + held + ' emeralds from stale view'); return { held };
  });
  await check('MySQL: swept ground drops publish only after committed storage', async () => {
    await reset(a, b);
    a.chat('/summon item ~ ~3 ~ {Item:{id:"minecraft:quartz",count:13},PickupDelay:32767}');
    await sleep(400); a.chat('/vc sweep');
    await until(() => total(a, 'quartz') === 13 && total(b, 'quartz') === 13, 'mysql sweep');
    sendClick(b, 0); await sleep(3500);
    assert.equal(total(b, 'quartz', 54, 90), 13);
    assert.equal(total(a, 'quartz'), 0);
    return { collectedAndWithdrawn: 13 };
  });
  a.end('Complete'); b.end('Complete'); await stop(two); await stop(one);
}
async function folia(mysql = false) {
  const label = mysql ? 'Folia+MySQL' : 'Folia';
  const s = await start(mysql ? 'folia-mysql' : 'folia', mysql ? 25656 : 25655, mysql, true);
  const a = await connect(s, 'RegionA'), b = await connect(s, 'RegionB');
  a.chat('/tp @s 0 -60 0'); b.chat('/tp @s 4096 -60 4096');
  await until(() => a.pos && b.pos && Math.abs(b.pos.x - a.pos.x) > 3000, 'players in distant regions', 30000);
  await open(a); await open(b);
  await check(`${label}: distant-region viewers deposit and synchronize`, async () => {
    await reset(a, b); await deposit(a, 'diamond', 64);
    await until(() => total(b, 'diamond') === 64, 'region view sync');
    return { a: a.pos, b: b.pos, count: total(b, 'diamond') };
  });
  await check(`${label}: competing region withdrawals conserve quantity`, async () => {
    assert.equal(total(a, 'diamond'), 64); assert.equal(total(b, 'diamond'), 64);
    sendClick(a, 0); sendClick(b, 0); await sleep(700);
    const held = total(a, 'diamond', 54, 90) + total(b, 'diamond', 54, 90);
    assert.equal(held + total(a, 'diamond'), 64); return { held, remaining: total(a, 'diamond') };
  });
  await check(`${label}: repeated region transfers conserve items under a bounded load`, async () => {
    await reset(a, b);
    const timings = [];
    for (let round = 0; round < 12; round++) {
      const began = performance.now();
      await deposit(a, 'iron_ingot', 16);
      await until(() => total(a, 'iron_ingot') === 16 && total(b, 'iron_ingot') === 16, 'load deposit sync');
      sendClick(a, 0); sendClick(b, 0);
      await until(() => total(a, 'iron_ingot') === 0 && total(b, 'iron_ingot') === 0, 'load withdrawal sync');
      await until(() => total(a, 'iron_ingot', 54, 90) + total(b, 'iron_ingot', 54, 90) === 16, 'load conservation');
      timings.push(performance.now() - began);
      a.chat('/clear @s'); b.chat('/clear @s');
      await until(() => total(a, 'iron_ingot', 54, 90) + total(b, 'iron_ingot', 54, 90) === 0, 'load clear');
    }
    timings.sort((a, b) => a - b);
    return { rounds: timings.length, endToEndP95Ms: timings[11], note: 'Includes protocol waits and polling; not database transaction latency or production load' };
  });
  await check(`${label}: ordinary sweep collects a regional dropped item`, async () => {
    await reset(a, b); a.chat('/summon item ~ ~3 ~ {Item:{id:"minecraft:quartz",count:13},PickupDelay:32767}');
    await sleep(400); a.chat('/vc sweep');
    await until(() => total(a, 'quartz') === 13 && total(b, 'quartz') === 13, 'regional sweep', 20000);
    return { collected: 13 };
  });
  await check(`${label}: invalid menu reload keeps live views; valid change invalidates both regions`, async () => {
    const menu = path.join(s.dir, 'plugins/VoidClear/menus/bin.yml'); const original = fs.readFileSync(menu, 'utf8');
    fs.writeFileSync(menu, 'Layout: [\n'); a.chat('/vc reload'); await sleep(600);
    assert(a.window && b.window);
    fs.writeFileSync(menu, original.replace('Title:', '# changed\nTitle:').replace('&e公共垃圾箱', '&e区域测试箱'));
    a.chat('/vc reload'); await until(() => !a.window && !b.window, 'region invalidation');
    await open(a); await open(b); assert(a.window.title.includes('区域测试箱'));
    fs.writeFileSync(menu, original); return { retainedQuartz: total(a, 'quartz') };
  });
  await check(`${label}: automatic trash clear closes both distant views`, async () => {
    const config = s.config.replace(/(trash:\s*\n\s+clear:[\s\S]*?enable: )false/, '$1true').replace(/(\n    period: )7200/, (_, prefix) => prefix + '5');
    fs.writeFileSync(path.join(s.dir, 'plugins/VoidClear/config.yml'), config); a.chat('/vc reload');
    await until(() => !a.window && !b.window, 'menu reload closure'); await open(a); await open(b);
    await until(() => !a.window && !b.window, 'automatic regional clear', 15000);
    await open(a); assert.equal(total(a, 'quartz'), 0); return { cleared: true };
  });
  a.end('Complete'); b.end('Complete'); await stop(s);
  await check(`${label}: no VoidClear thread violations and clean shutdown`, async () => {
    assert(!/Could not pass event|Error occurred while|Thread failed|not owned by current region|垃圾箱最终保存失败|Exception.*VoidClear/.test(s.output), s.output.slice(-7000));
    assert(s.output.includes('Saving') || s.output.includes('saving')); return { exitCode: await s.done };
  });
}
module.exports = { start, connect, open, sendClick, total, deposit, stop, until, sleep, sessionClosed, settled, tablePrefix, clients, servers };
if (require.main === module) {
const watchdog = setTimeout(() => { for (const s of servers) s.cmd('stop'); process.exitCode = 1; }, 600000);
(async () => {
  try {
    if (mode === 'all' || mode === 'mysql') await multi();
    if (mode === 'all' || mode === 'folia') await folia();
    if (mode === 'all' || mode === 'folia-mysql') await folia(true);
  } catch (e) { results.fatal = e.stack; console.error(e); process.exitCode = 1; }
  finally {
    clients.forEach(c => c.end('Cleanup'));
    await Promise.all(servers.map(stop)); clearTimeout(watchdog);
    results.finishedAt = new Date().toISOString();
    const report = JSON.stringify(results, null, 2);
    fs.writeFileSync(path.join(__dirname, `platform-results-${mode}.json`), report);
    fs.writeFileSync(path.join(__dirname, `platform-results-${mode}-${results.finishedAt.replace(/[:.]/g, '-')}.json`), report);
    if (results.checks.some(c => !c.passed)) process.exitCode = 1;
  }
})();
}
