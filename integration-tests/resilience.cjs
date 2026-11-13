// Explicit isolated resilience tests. Kills only Java children created here, never a discovered PID.
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const assert = require('node:assert/strict');
const h = require('./platforms.cjs');
const results = { startedAt: new Date().toISOString(), checks: [], crashBoundaries: [], scope: 'Loopback fault simulation and 5-minute two-client soak per backend; not power loss or production load' };
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR || '../target/VoidClear-3.0.0-single-server.jar');
results.candidate = { path: candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex') };
const root = path.resolve(__dirname, '../target/platform-tests');
const focused = process.argv[2] === 'focused';
const sweepOnly = process.argv[2] === 'sweep';
const playerOnly = process.argv[2] === 'player';
assert(!process.argv[2] || focused || sweepOnly || playerOnly, 'Use no argument, focused, sweep, or player');
results.mode = playerOnly ? 'player-only' : sweepOnly ? 'sweep-only' : focused ? 'focused-faults-and-sweep' : 'full';
if (focused) results.scope = 'Loopback database/menu faults and four sweep crash controls; excludes soak and player transfer crash matrix';
if (sweepOnly) results.scope = 'Four sweep crash controls only; excludes database faults, soak and player transfer crash matrix';
if (playerOnly) results.scope = 'Eight acknowledged storage/offhand player crash controls only; excludes mid-apply, cursor/drop cuts, faults and soak';
const expectedCrashCases = playerOnly ? 8 : focused || sweepOnly ? 4 : 12;
const resultFile = path.join(__dirname, 'resilience-results-revised.json');
const runResultFile = path.join(__dirname, `resilience-results-${results.startedAt.replace(/[:.]/g, '-')}.json`);
const saveResults = () => {
  const json = JSON.stringify(results, null, 2);
  fs.writeFileSync(runResultFile, json); fs.writeFileSync(resultFile, json);
};
const saveErrors = /Could not pass event|Error occurred while|VC_PROBE_FAILED|垃圾箱保存失败|最终保存失败|数据库事务失败|交易未确认/;
let proxy;
let forwardingDelay = 0, forwardedPackets = 0;
const sockets = new Set();
async function forward(delayMs) {
  forwardingDelay = delayMs;
  proxy = net.createServer(client => {
    const upstream = net.connect(33317, '127.0.0.1');
    for (const socket of [client, upstream]) {
      sockets.add(socket); socket.on('close', () => sockets.delete(socket));
      socket.on('error', () => { client.destroy(); upstream.destroy(); });
    }
    for (const [source, target] of [[client, upstream], [upstream, client]]) {
      source.on('data', data => { forwardedPackets++; source.pause(); setTimeout(() => { if (!target.destroyed) target.write(data); source.resume(); }, forwardingDelay); });
      source.on('end', () => target.end());
    }
  });
  await new Promise((resolve, reject) => { proxy.once('error', reject); proxy.listen(33318, '127.0.0.1', resolve); });
}
async function closeProxy() {
  for (const socket of sockets) socket.destroy();
  if (proxy?.listening) await new Promise(resolve => proxy.close(resolve));
}
async function probe(s, command, marker) {
  const offset = s.output.length; s.cmd(command);
  await h.until(() => s.output.slice(offset).includes(marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), marker, 60000);
  assert(!s.output.slice(offset).includes('VC_PROBE_FAILED'), s.output.slice(offset));
  return s.output.slice(offset);
}
async function check(name, action) {
  try {
    const evidence = await action(); results.checks.push({ name, passed: true, evidence }); console.log('PASS', name, evidence || '');
  } catch (failed) {
    results.checks.push({ name, passed: false, error: failed.stack }); throw failed;
  } finally { saveResults(); }
}
async function reset(s, players) {
  s.cmd('vc trash clear confirm'); players.forEach(c => s.cmd('clear ' + c.username));
  await h.sleep(500); for (const c of players) await h.open(c);
}
async function checkpoint(s, c, token) { await probe(s, `vcprobe checkpoint ${c.username} ${token}`, 'VC_CHECKPOINT_' + token); }
async function flushdb(s, token) { await probe(s, `vcprobe flushdb ${token}`, 'VC_FLUSHDB_' + token); }
async function saveworld(s, token) {
  const offset = s.output.length;
  await probe(s, `vcprobe saveworld ${token}`, 'VC_SAVEWORLD_QUEUED_' + token);
  await h.until(() => s.output.slice(offset).includes('Saved the game'), 'world save completion ' + token, 60000);
}
async function dbcount(s, material, token) {
  const log = await probe(s, `vcprobe dbcount ${material.toUpperCase()} ${token}`, `VC_DBCOUNT_${token}=`);
  return Number(log.match(new RegExp(`VC_DBCOUNT_${token}=(\\d+)`))[1]);
}
async function kill(s) { assert(!s.exited); assert(s.child.kill('SIGKILL'), 'Failed to kill owned test child'); await s.done; }
async function faults() {
  const s = await h.start('resilience-faults', 25657, true);
  try {
    await check('MySQL 30ms per-direction TCP delay preserves committed quantity', async () => {
      await forward(30);
      try {
        const log = await probe(s, 'vcprobe dbfault delay', 'VC_DBFAULT_delay_OK');
        const transactionMs = Number(log.match(/VC_DB_DELAY_MS=([\d.]+)/)[1]);
        assert(transactionMs >= 60, 'Injected delay was not exercised');
        return { transactionMs, injectedPerDirectionMs: 30 };
      }
      finally { await closeProxy(); }
    });
    await check('MySQL held metadata lock preserves quantity and exposes synchronous stall', async () => {
      const log = await probe(s, 'vcprobe dbfault lock', 'VC_DBFAULT_lock_OK');
      return { transactionMs: Number(log.match(/VC_DB_LOCK_MS=([\d.]+)/)[1]), heldMs: 1500 };
    });
    await check('MySQL connection refusal rejects before effects and preserves database', async () => {
      await forward(0);
      const folder = path.join(s.dir, 'plugins/VoidClearProbe');
      for (const f of ['disconnect-ready', 'disconnect-go']) fs.rmSync(path.join(folder, f), { force: true });
      const offset = s.output.length; s.cmd('vcprobe dbfault disconnect');
      await h.until(() => fs.existsSync(path.join(folder, 'disconnect-ready')), 'disconnect ready', 30000);
      await closeProxy(); fs.writeFileSync(path.join(folder, 'disconnect-go'), 'go');
      await h.until(() => /VC_DBFAULT_disconnect_OK|VC_PROBE_FAILED/.test(s.output.slice(offset)), 'disconnect result', 30000);
      assert(!s.output.slice(offset).includes('VC_PROBE_FAILED'), s.output.slice(offset));
      return { rejectionMs: Number(s.output.slice(offset).match(/VC_DB_DISCONNECT_MS=([\d.]+)/)[1]) };
    });
  } finally { await closeProxy(); await h.stop(s); }
}
async function soak(mysql) {
  const name = mysql ? 'mysql' : 'sqlite';
  let s = await h.start('resilience-soak-' + name, 25657, mysql);
  let a = await h.connect(s, 'SoakA'), b = await h.connect(s, 'SoakB');
  try {
    await reset(s, [a, b]);
    await check(`${name}: five-minute two-client competing transfer soak`, async () => {
      const began = Date.now(), timings = []; let rounds = 0;
      while (Date.now() - began < 300000 || rounds < 100) {
        const start = performance.now();
        await h.deposit(a, 'iron_ingot', 16);
        await h.until(() => h.total(a, 'iron_ingot') === 16 && h.total(b, 'iron_ingot') === 16, 'soak publish');
        h.sendClick(a, 0); h.sendClick(b, 0);
        await h.until(() => h.total(a, 'iron_ingot') === 0 && h.total(b, 'iron_ingot') === 0
          && h.total(a, 'iron_ingot', 54, 90) + h.total(b, 'iron_ingot', 54, 90) === 16, 'soak conservation');
        timings.push(performance.now() - start); rounds++;
        s.cmd('clear SoakA'); s.cmd('clear SoakB');
        await h.until(() => h.total(a, 'iron_ingot', 54, 90) + h.total(b, 'iron_ingot', 54, 90) === 0, 'soak reset');
      }
      timings.sort((a, b) => a - b);
      return { durationMs: Date.now() - began, rounds, transferredItems: rounds * 16, endToEndP95Ms: timings[Math.ceil(timings.length * .95) - 1], includesProtocolWaits: true };
    });
    await check(`${name}: soak shutdown and restart preserve independent stored sentinels`, async () => {
      await h.deposit(a, 'gold_ingot', 7);
      await h.until(() => h.total(a, 'gold_ingot') === 7, 'soak bin sentinel');
      s.cmd('give SoakB emerald 11');
      await h.until(() => h.total(b, 'emerald', 54, 90) === 11, 'soak player sentinel');
      a.end('Restart check'); b.end('Restart check'); await h.stop(s);
      assert(!saveErrors.test(s.output), s.output.slice(-7000));
      assert.equal(await s.done, 0);
      s = await h.start('resilience-soak-' + name, 25657, mysql);
      a = await h.connect(s, 'SoakA'); b = await h.connect(s, 'SoakB'); await h.open(a); await h.open(b);
      assert.equal(h.total(a, 'gold_ingot'), 7); assert.equal(h.total(b, 'gold_ingot'), 7);
      assert.equal(h.total(a, 'emerald', 54, 90), 0); assert.equal(h.total(b, 'emerald', 54, 90), 11);
      assert.equal(await dbcount(s, 'gold_ingot', 'soakgold'), 7);
      assert.equal(await dbcount(s, 'iron_ingot', 'soakiron'), 0);
      return { binGold: 7, playerEmerald: 11, persistedIron: 0, independentJdbcRead: true, cleanRestart: true };
    });
  } finally { a.end('Complete'); b.end('Complete'); await h.stop(s); }
}
async function crash(mysql, direction, saveAfter) {
  const backend = mysql ? 'mysql' : 'sqlite';
  const name = `resilience-crash-${backend}-${direction}-${saveAfter ? 'saved' : 'unsaved'}`;
  const username = `Crash${mysql ? 'M' : 'S'}${direction[0]}${saveAfter ? 'Y' : 'N'}`;
  let s = await h.start(name, 25657, mysql), c = await h.connect(s, username);
  try {
    s.cmd('save-off');
    await reset(s, [c]);
    await h.deposit(c, 'diamond', 16);
    await h.until(() => h.total(c, 'diamond') === 16, 'crash seed');
    if (direction === 'deposit') {
      h.sendClick(c, 0); await h.until(() => h.total(c, 'diamond', 54, 90) === 16 && h.total(c, 'diamond') === 0, 'prepare player');
    }
    await checkpoint(s, c, 'before');
    if (direction === 'deposit') {
      const slot = c.window.items.findIndex((item, n) => n >= 54 && item?.itemCount === 16);
      assert(slot >= 54); h.sendClick(c, slot);
      await h.until(() => h.total(c, 'diamond') === 16 && h.total(c, 'diamond', 54, 90) === 0, 'deposit ack');
    } else {
      h.sendClick(c, 0);
      await h.until(() => h.total(c, 'diamond') === 0 && h.total(c, 'diamond', 54, 90) === 16, 'withdraw ack');
    }
    if (saveAfter) await checkpoint(s, c, 'after');
    else await flushdb(s, 'after');
    assert.equal(await dbcount(s, 'diamond', 'beforekill'), direction === 'deposit' ? 16 : 0);
    await kill(s); c.end('Killed test server');
    s = await h.start(name, 25657, mysql); c = await h.connect(s, username); await h.open(c);
    const observed = { backend, direction, saveAfter, dbOnlyBarrier: !saveAfter, expectedTotal: 16, bin: h.total(c, 'diamond'), player: h.total(c, 'diamond', 54, 90), forcedProcessKill: true };
    observed.total = observed.bin + observed.player;
    observed.quantityConserved = observed.total === observed.expectedTotal;
    results.crashBoundaries.push(observed);
    observed.exactSidesCorrect = observed.bin === (direction === 'deposit' ? 16 : 0) && observed.player === (direction === 'deposit' ? 0 : 16);
    results.checks.push({ name: `${backend}: ${direction} ${saveAfter ? 'saved' : 'DB-only saved'} crash safety`, passed: observed.quantityConserved && observed.exactSidesCorrect, evidence: observed });
    saveResults();
    console.log(observed.quantityConserved && observed.exactSidesCorrect ? 'PASS crash safety' : 'FAIL crash safety', observed);
  } finally { c.end('Complete'); await h.stop(s); }
}
async function menuFaults() {
  await forward(0);
  let s, c;
  try {
    s = await h.start('resilience-menu-faults', 25657, true, false, 33318); c = await h.connect(s, 'FaultA');
    await reset(s, [c]);
    await check('MySQL actual menu uses delayed JDBC proxy and persists 16 diamonds', async () => {
      const before = forwardedPackets; forwardingDelay = 30; const began = performance.now();
      s.cmd('give FaultA diamond 16');
      await h.until(() => h.total(c, 'diamond', 54, 90) === 16, 'delayed diamond seed');
      const slot = c.window.items.findIndex((item, n) => n >= 54 && item?.itemCount === 16); assert(slot >= 54);
      const clickBegan = performance.now(); h.sendClick(c, slot);
      await h.until(() => h.total(c, 'diamond') === 16 && h.total(c, 'diamond', 54, 90) === 0, 'delayed menu deposit', 30000);
      const clickToAckMs = performance.now() - clickBegan;
      assert(clickToAckMs >= 60, 'Actual menu did not exercise injected delay');
      await h.settled(s, c);
      const elapsedMs = performance.now() - began; forwardingDelay = 0;
      assert(forwardedPackets > before);
      assert.equal(await dbcount(s, 'diamond', 'menudelay'), 16);
      return { elapsedMs, clickToAckMs, injectedPerDirectionMs: 30, proxyPackets: forwardedPackets - before, persisted: 16 };
    });
    await check('MySQL actual menu isolates connection refusal without consumption', async () => {
      s.cmd('give FaultA emerald 9'); await h.until(() => h.total(c, 'emerald', 54, 90) === 9, 'fault emerald seed');
      await closeProxy(); const offset = s.output.length;
      const slot = c.window.items.findIndex((item, n) => n >= 54 && item?.itemCount === 9); assert(slot >= 54);
      h.sendClick(c, slot);
      await h.until(() => {
        const log = s.output.slice(offset);
        return /TrashListener\.(?:commit|lambda\$commitAsync)/.test(log) && log.includes('Connection refused');
      }, 'menu refusal');
      await h.until(() => s.output.slice(offset).includes('FaultA lost connection'), 'refused player isolated');
      await forward(0); c.end('Refusal isolation');
      await assert.rejects(h.connect(s, 'FaultA'), /VoidClear|未确认|交易/);
      await h.stop(s);
      const nbt = require('node:module').createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'))('prismarine-nbt');
      const saved = nbt.simplify((await nbt.parse(fs.readFileSync(path.join(s.dir, 'world/playerdata', c.uuid + '.dat')))).parsed);
      const emerald = saved.Inventory.filter(item => item.id === 'minecraft:emerald')
        .reduce((sum, item) => sum + Number(item.count || item.Count || 0), 0);
      assert.equal(emerald, 9, 'Refusal consumed the actual saved inventory');
      s = await h.start('resilience-menu-faults', 25657, true, false, 33318);
      assert.equal(await dbcount(s, 'emerald', 'menurefusal'), 0);
      assert.equal(await dbcount(s, 'diamond', 'menukept'), 16);
      return { playerEmerald: emerald, persistedEmerald: 0, priorDiamond: 16, sameProcessLoginDenied: true,
        fault: 'new connection refusal; not COMMIT ACK loss', actualSavedNbt: true };
    });
  } finally { if (c) c.end('Complete'); if (s) await h.stop(s); await closeProxy(); }
}
async function sweepCrash(mysql, saveAfter) {
  const backend = mysql ? 'mysql' : 'sqlite';
  const name = `resilience-sweep-${backend}-${saveAfter ? 'saved' : 'unsaved'}`;
  let s = await h.start(name, 25657, mysql), c = await h.connect(s, 'SweepA');
  try {
    await reset(s, [c]); s.cmd('kill @e[type=minecraft:item]');
    await checkpoint(s, c, 'sweepbaseline');
    let log = await probe(s, 'vcprobe ground seed SweepA seed', 'VC_GROUND_seed='); assert(log.includes('VC_GROUND_seed=16'));
    await saveworld(s, 'baseline');
    s.cmd('save-off'); s.cmd('vc sweep');
    await h.until(() => h.total(c, 'quartz') === 16, 'sweep ack', 30000);
    await flushdb(s, 'sweep'); assert.equal(await dbcount(s, 'quartz', 'sweepdb'), 16);
    log = await probe(s, 'vcprobe ground count SweepA removed', 'VC_GROUND_removed='); assert(log.includes('VC_GROUND_removed=0'));
    if (saveAfter) await saveworld(s, 'after');
    await kill(s); c.end('Killed test server');
    s = await h.start(name, 25657, mysql); c = await h.connect(s, 'SweepA'); await h.open(c);
    log = await probe(s, 'vcprobe ground count SweepA restarted', 'VC_GROUND_restarted=');
    const observed = { backend, direction: 'sweep', saveAfter, expectedTotal: 16, bin: h.total(c, 'quartz'), ground: Number(log.match(/VC_GROUND_restarted=(\d+)/)[1]), player: h.total(c, 'quartz', 54, 90), forcedProcessKill: true, dbOnlyBarrier: !saveAfter, databaseFlushed: true, worldSavedAfter: saveAfter };
    observed.total = observed.bin + observed.ground + observed.player; observed.quantityConserved = observed.total === 16;
    observed.exactSidesCorrect = observed.bin === 16 && observed.ground === 0 && observed.player === 0;
    results.crashBoundaries.push(observed);
    results.checks.push({ name: `${backend}: sweep ${saveAfter ? 'world saved' : 'world unsaved'} crash safety`, passed: observed.quantityConserved && observed.exactSidesCorrect, evidence: observed });
    saveResults(); console.log(observed.quantityConserved && observed.exactSidesCorrect ? 'PASS sweep crash' : 'FAIL sweep crash', observed);
  } finally { c.end('Complete'); await h.stop(s); }
}
const watchdog = setTimeout(() => { for (const s of h.servers) if (!s.exited) s.child.kill('SIGKILL'); process.exitCode = 1; }, 1800000);
(async () => {
  try {
    fs.mkdirSync(root, { recursive: true });
    if (!sweepOnly && !playerOnly) { await faults(); await menuFaults(); }
    for (const mysql of [false, true]) {
      if (!focused && !sweepOnly) {
        if (!playerOnly) await soak(mysql);
        for (const direction of ['deposit', 'withdraw']) for (const saved of [false, true]) await crash(mysql, direction, saved);
      }
      if (!playerOnly) for (const saved of [false, true]) await sweepCrash(mysql, saved);
    }
    results.observationCompleted = results.crashBoundaries.length === expectedCrashCases;
    results.crashAcceptancePassed = results.observationCompleted && results.crashBoundaries.every(record => record.quantityConserved && record.exactSidesCorrect);
    if (!results.crashAcceptancePassed) {
      results.safetyFailure = 'Crash restart reproduced quantity loss or duplication; observation completion is NOT safety acceptance';
      process.exitCode = 1;
    }
  } catch (failed) { results.fatal = failed.stack; console.error(failed); process.exitCode = 1; }
  finally {
    await closeProxy(); h.clients.forEach(c => c.end('Cleanup'));
    await Promise.all(h.servers.map(h.stop)); clearTimeout(watchdog);
    results.finishedAt = new Date().toISOString();
    if (results.checks.some(check => !check.passed) || results.crashBoundaries.length !== expectedCrashCases) process.exitCode = 1;
    saveResults();
  }
})();
