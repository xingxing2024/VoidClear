// Loopback protocol clients, shared MySQL, fixed stock; every round checks all actual inventories.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createRequire } = require('node:module');
const req = createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'));
const nbt = req('prismarine-nbt');
const data = req('minecraft-data')('1.21.8');
const items = ['diamond', 'emerald', 'iron_ingot', 'gold_ingot'];
const expected = Object.fromEntries(items.map(item => [item, 256]));
const serverFailurePattern = /VC_PROBE_FAILED|OutOfMemoryError|Can't keep up!|玩家物品交易已隔离|Thread .*failed.*thread check|Error occurred while/;
function sumStock(bin, inventories) {
  return Object.fromEntries(items.map(item => [item, bin[item] + inventories.reduce((n, held) => n + held[item], 0)]));
}
function percentile(values, fraction) {
  assert(values.length); const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}
if (process.argv.includes('--self-check')) {
  const bin = Object.fromEntries(items.map(item => [item, 192]));
  const held = Object.fromEntries(items.map(item => [item, 64]));
  assert.deepEqual(sumStock(bin, [held]), expected);
  assert.notDeepEqual(sumStock(bin, [held, held]), expected, 'Double-counting a shared replica must fail');
  assert.equal(percentile([9, 1, 5, 3], .5), 3);
  assert.equal(percentile([9, 1, 5, 3], .95), 9);
  assert(serverFailurePattern.test("[WARN]: Can't keep up! Running 3040ms or 60 ticks behind"));
  assert(serverFailurePattern.test('[ERROR]: Error occurred while enabling VoidClear'));
  assert(!serverFailurePattern.test('[INFO]: Saving players'));
  console.log('Long-load arithmetic, percentile and log checks passed');
  process.exit(0);
}
const h = require('./platforms.cjs');
const namespace = process.env.VOIDCLEAR_TEST_NAMESPACE;
assert(namespace && /^[a-z0-9_]{1,24}$/.test(namespace), 'Fresh isolated namespace required');
const seconds = Number(process.env.VOIDCLEAR_LOAD_SECONDS || 1800);
assert(Number.isInteger(seconds) && seconds >= 30 && seconds <= 7200, 'Load duration must be 30..7200 seconds');
assert(process.env.VOIDCLEAR_TEST_JAR, 'Explicit immutable candidate required');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const reportFile = path.join(__dirname, `long-load-${namespace}.json`);
assert(!fs.existsSync(reportFile), 'Preserve the existing load report');
const evidenceDir = path.resolve(__dirname, '../target/platform-tests', namespace + '-handoffs');
assert(!fs.existsSync(evidenceDir), 'Preserve the existing handoff evidence');
fs.mkdirSync(evidenceDir);
const results = { candidate, sha256: crypto.createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'),
  startedAt: new Date().toISOString(), requestedSeconds: seconds, scope: 'Two loopback Paper servers, shared isolated MySQL; protocol clients, not humans; verified file-copy handoff, not a live proxy',
  expected, checks: [], phases: [], samples: [], handoffs: [], batches: [] };
let serial = 0, rounds = 0, attempts = 0, committedMoves = 0;
let servers = [], roster = [];
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const persist = () => fs.writeFileSync(reportFile, JSON.stringify(results, null, 2));
const inventory = c => Object.fromEntries(items.map(item => [item, h.total(c, item, 54, 90)]));
const bin = c => Object.fromEntries(items.map(item => [item, h.total(c, item)]));
const equal = (a, b) => items.every(item => a[item] === b[item]);
async function probe(s, command, marker) {
  const offset = s.output.length; s.cmd(command);
  await h.until(() => s.output.slice(offset).includes(marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), command, 30000);
  const output = s.output.slice(offset); assert(!output.includes('VC_PROBE_FAILED'), output);
  return output.slice(output.indexOf(marker) + marker.length).split(/\r?\n/)[0].replace(/\u001b\[[0-9;]*m/g, '');
}
async function stats(s) {
  const token = String(++serial), began = performance.now();
  const value = JSON.parse(await probe(s, 'vcprobe loadstats ' + token, 'VC_LOAD_STATS_' + token + '='));
  assert(!s.exited, 'Server exited during load');
  assert(Number.isFinite(value.binCount) && Number.isFinite(value.pending), 'Invalid metrics');
  return { server: s.name, responseMs: performance.now() - began, ...value };
}
async function settledAll() {
  const deadline = Date.now() + 30000;
  do {
    const values = await Promise.all(servers.map(stats));
    if (values.every(value => value.pending === 0)) return values;
    await h.sleep(50);
  } while (Date.now() < deadline);
  assert.fail('Database queue did not settle in 30 seconds');
}
async function sync(c) {
  const token = String(++serial); c.chat('/vcprobe clientsync ' + token);
  await h.until(() => c.chats.includes('VC_CLIENT_SYNC_' + token), 'client packet barrier ' + c.username, 30000);
}
async function connect(s, username) {
  const offset = s.output.length, c = await h.connect(s, username);
  await h.until(() => s.output.slice(offset).includes(username + '[/127.0.0.1:')
    && s.output.slice(offset).includes('] logged in with entity id'), 'actual server Join ' + username);
  c.write('player_loaded', {}); s.cmd('gamemode creative ' + username);
  await h.open(c); await sync(c); await settledAll();
  return c;
}
async function conservation() {
  await h.until(() => {
    assert(roster.every(c => c.window && !c.ended), 'Active load client lost its menu or connection');
    assert(roster.every(c => !c.window.cursor?.itemCount), 'Shift-only load unexpectedly left a cursor item');
    const held = roster.map(inventory), remaining = Object.fromEntries(items.map(item => [item, expected[item] - held.reduce((n, stock) => n + stock[item], 0)]));
    assert(items.every(item => remaining[item] >= 0), 'Player inventories alone exceeded the initial stock');
    return roster.every(c => equal(bin(c), remaining));
  }, 'all shared replicas and player inventories conserve fixed stock', 30000);
  const total = sumStock(bin(roster[0]), roster.map(inventory));
  assert.deepEqual(total, expected); return total;
}
async function batch(withdraw, selected = roster) {
  await settledAll(); const before = roster.map(inventory), began = performance.now();
  let sent = 0;
  for (const c of selected) {
    const index = roster.indexOf(c), item = items[(index + rounds) % items.length];
    const slots = c.window.items.flatMap((stack, slot) => stack?.itemId === data.itemsByName[item].id
      && (withdraw ? slot < 45 : slot >= 54 && slot < 90) ? [slot] : []);
    const slot = withdraw ? slots[Math.floor(index / items.length) % slots.length]
      : c.window.items.findIndex((stack, at) => at >= 54 && at < 90 && stack?.itemCount > 0);
    if (slot === undefined || slot < 0) continue;
    h.sendClick(c, slot); sent++;
  }
  attempts += sent;
  await Promise.all(selected.map(sync)); await settledAll(); await conservation();
  const changed = roster.filter((c, index) => !equal(inventory(c), before[index])).length;
  committedMoves += changed;
  results.batches.push({ round: rounds, players: roster.length, withdraw, attempts: sent, changedInventories: changed, elapsedMs: performance.now() - began });
  if (withdraw) assert(changed > 0, 'All concurrent withdrawals made no progress');
  return changed;
}
async function depositAll() {
  const deadline = Date.now() + 45000;
  while (roster.some(c => items.some(item => inventory(c)[item] > 0))) {
    assert(Date.now() < deadline, 'Concurrent deposits did not return all stock in 45 seconds');
    await batch(false, roster.filter(c => items.some(item => inventory(c)[item] > 0)));
  }
}
async function logout(c) {
  const offset = c.s.output.length; c.end('Verified load handoff');
  await h.until(() => c.s.output.slice(offset).includes(c.username + ' left the game'), 'verified quit ' + c.username, 30000);
  await h.sessionClosed(c.s, c.username);
}
async function savedInventory(file) {
  const bytes = fs.readFileSync(file), value = nbt.simplify((await nbt.parse(bytes)).parsed);
  const counts = Object.fromEntries(items.map(item => [item, (value.Inventory || []).filter(stack => stack.id === 'minecraft:' + item)
    .reduce((n, stack) => n + Number(stack.count ?? stack.Count ?? 0), 0)]));
  return { counts, sha256: hash(bytes), receipt: value.BukkitValues?.['voidclear:player_receipt'], logout: value.BukkitValues?.['voidclear:session_logout'] };
}
async function handoff() {
  const index = results.handoffs.length % roster.length, c = roster[index];
  await batch(true, [c]);
  const held = inventory(c), source = c.s, destination = servers.find(s => s !== source);
  await assert.rejects(h.connect(destination, c.username), /VoidClear|会话|交易/);
  await logout(c);
  const from = path.join(source.dir, 'world/playerdata', c.uuid + '.dat');
  const to = path.join(destination.dir, 'world/playerdata', c.uuid + '.dat');
  const saved = await savedInventory(from);
  assert.deepEqual(saved.counts, held); assert(saved.receipt && saved.logout, 'Actual handoff receipts missing');
  const tag = String(results.handoffs.length + 1).padStart(3, '0');
  fs.copyFileSync(from, path.join(evidenceDir, tag + '-source.dat'), fs.constants.COPYFILE_EXCL);
  if (fs.existsSync(to)) fs.renameSync(to, path.join(evidenceDir, tag + '-previous-destination.dat'));
  fs.copyFileSync(from, to, fs.constants.COPYFILE_EXCL);
  assert.equal(hash(fs.readFileSync(to)), saved.sha256);
  const replacement = await connect(destination, c.username); roster[index] = replacement;
  assert.deepEqual(inventory(replacement), held); await conservation();
  results.handoffs.push({ username: c.username, source: source.name, destination: destination.name, ...saved, duplicateLoginDenied: true, copiedBytesMatch: true });
  await depositAll();
  console.log('HANDOFF', results.handoffs.at(-1)); persist();
}
async function sample(elapsedMs) {
  const values = await Promise.all(servers.map(stats));
  results.samples.push({ elapsedMs, rounds, attempts, committedMoves, servers: values,
    worldSaveTimes: servers.map(s => ({ server: s.name, modifiedMs: fs.statSync(path.join(s.dir, 'world/level.dat')).mtimeMs })) });
  console.log('LOAD', JSON.stringify(results.samples.at(-1))); persist();
}
async function run() {
  try {
    const initialSaveTimes = [];
    for (const [name, port] of [['load-paper-a', 25653], ['load-paper-b', 25654]]) {
      const server = await h.start(name, port, true, false, 33317, { probe: true, maxPlayers: 18 });
      servers.push(server);
      // Flush the fresh world before starting the next server or connecting players.
      const worldFile = path.join(server.dir, 'world/level.dat');
      const offset = server.output.length;
      const started = performance.now();
      server.cmd('save-all flush');
      await h.until(() => server.output.slice(offset).includes('Saved the game') && fs.existsSync(worldFile),
        'initial durable world baseline ' + server.name, 60000);
      initialSaveTimes.push(fs.statSync(worldFile).mtimeMs);
      console.log('BASELINE SAVE', server.name, `${Math.round(performance.now() - started)} ms`);
    }
    assert.equal(h.tablePrefix(servers[0].name), h.tablePrefix(servers[1].name));
    for (let index = 0; index < 4; index++) roster.push(await connect(servers[index % 2], 'Load' + String(index).padStart(2, '0')));
    for (const item of items) for (let stack = 0; stack < 4; stack++) {
      await h.deposit(roster[0], item, 64); await h.settled(roster[0].s, roster[0]);
    }
    await conservation();
    results.checks.push({ name: 'two servers share one fixed 1024-item stock', passed: true });
    const loadBegan = performance.now();
    let nextSample = 0, nextHandoff = seconds < 1800 ? seconds * 500 : 300000;
    for (const [phase, players] of [4, 8, 16].entries()) {
      while (roster.length < players) {
        const index = roster.length; roster.push(await connect(servers[index % 2], 'Load' + String(index).padStart(2, '0')));
      }
      await conservation(); const phaseBegan = performance.now(), targetEnd = loadBegan + seconds * 1000 * (phase + 1) / 3;
      const firstRound = rounds;
      do {
        await batch(true); await depositAll();
        assert(roster.every(c => items.every(item => inventory(c)[item] === 0)), 'A full round did not return all stock'); rounds++;
        const elapsed = performance.now() - loadBegan;
        if (elapsed >= nextSample) { await sample(elapsed); nextSample = elapsed + 30000; }
        if (elapsed >= nextHandoff) { await handoff(); nextHandoff = elapsed + (seconds < 1800 ? seconds * 1000 : 300000); }
      } while (performance.now() < targetEnd);
      results.phases.push({ players, rounds: rounds - firstRound, durationMs: performance.now() - phaseBegan });
      assert(rounds > firstRound); console.log('PASS ramp', results.phases.at(-1)); persist();
    }
    results.activeLoadMs = performance.now() - loadBegan; assert(results.activeLoadMs >= seconds * 1000);
    results.nativeAutosaves = servers.map((s, index) => ({ server: s.name, initialModifiedMs: initialSaveTimes[index],
      finalModifiedMs: fs.statSync(path.join(s.dir, 'world/level.dat')).mtimeMs }));
    if (seconds >= 1800) assert(results.nativeAutosaves.every(value => value.finalModifiedMs > value.initialModifiedMs), 'Both servers must perform a native world save during the long run');
    results.checks.push({ name: '4/8/16-client continuous shared-stock conservation', passed: true, rounds, attempts, committedMoves });
    assert(results.handoffs.length >= 1);
    results.checks.push({ name: 'foreign duplicate refused and actual saved inventory handed off', passed: true, handoffs: results.handoffs.length });
    await batch(true, roster.slice(0, 4));
    const held = roster.map(inventory), identities = roster.map(c => ({ name: c.username, uuid: c.uuid, server: servers.indexOf(c.s) }));
    await Promise.all(roster.map(logout));
    const saved = await Promise.all(identities.map(async (who, index) => {
      const value = await savedInventory(path.join(servers[who.server].dir, 'world/playerdata', who.uuid + '.dat'));
      assert.deepEqual(value.counts, held[index]); return { ...who, ...value };
    }));
    await Promise.all(servers.map(h.stop));
    for (const s of servers) assert.equal(await s.done, 0, 'Unclean server shutdown');
    results.savedPlayers = saved;
    servers = [await h.start('load-paper-a', 25653, true, false, 33317, { probe: true, maxPlayers: 18 }),
      await h.start('load-paper-b', 25654, true, false, 33317, { probe: true, maxPlayers: 18 })];
    roster = [];
    for (const who of identities) roster.push(await connect(servers[who.server], who.name));
    roster.forEach((c, index) => assert.deepEqual(inventory(c), held[index])); await conservation();
    for (const item of items) {
      const token = String(++serial), sql = Number(await probe(servers[0], 'vcprobe dbcount ' + item.toUpperCase() + ' ' + token, 'VC_DBCOUNT_' + token + '='));
      assert.equal(sql + held.reduce((n, stock) => n + stock[item], 0), expected[item]);
    }
    results.checks.push({ name: 'clean restart preserves actual player NBT, committed SQL and total stock', passed: true });
    await depositAll(); await conservation();
    results.batchP95Ms = percentile(results.batches.map(value => value.elapsedMs), .95);
    results.serverFailures = h.servers.flatMap(s => s.output.split(/\r?\n/).filter(line => serverFailurePattern.test(line))
      .map(line => ({ server: s.name, line })));
    assert.equal(results.serverFailures.length, 0, 'Server log failures: ' + JSON.stringify(results.serverFailures));
    results.finishedAt = new Date().toISOString(); console.log('PASS long load', JSON.stringify({ rounds, attempts, committedMoves, activeLoadMs: results.activeLoadMs, batchP95Ms: results.batchP95Ms }));
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(c => c.end('Load cleanup'));
    await Promise.all(h.servers.map(h.stop)); results.cleanupFinishedAt = new Date().toISOString(); persist();
  }
}
run();
