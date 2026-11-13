// Real menu under delayed JDBC; only the isolated MySQL and loopback proxy are used.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const { createRequire } = require('node:module');
const req = createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'));
const nbt = req('prismarine-nbt');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Fresh isolated namespace required');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const folia = process.env.VOIDCLEAR_ASYNC_FOLIA === 'true';
const results = { candidate, platform: folia ? 'folia' : 'paper', sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
const sockets = new Set();
let delay = 0;
const proxy = net.createServer(client => {
  const upstream = net.connect(33317, '127.0.0.1');
  for (const socket of [client, upstream]) {
    sockets.add(socket); socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => { client.destroy(); upstream.destroy(); });
  }
  for (const [source, target] of [[client, upstream], [upstream, client]]) {
    source.on('data', data => { source.pause(); setTimeout(() => { if (!target.destroyed) target.write(data); source.resume(); }, delay); });
    source.on('end', () => target.end());
  }
});
async function probe(s, command, marker) {
  const offset = s.output.length; s.cmd(command);
  await h.until(() => s.output.slice(offset).includes(marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), command, 30000);
  const output = s.output.slice(offset); assert(!output.includes('VC_PROBE_FAILED'), output); return output;
}
async function pending(s, c, expected) {
  for (let attempt = 0; attempt < 100; attempt++) {
    const output = await probe(s, `vcprobe dbpending ${c.username}`, 'VC_DB_PENDING=');
    if (output.includes('VC_DB_PENDING=' + expected)) return;
    await h.sleep(100);
  }
  assert.fail('Player pending state did not become ' + expected);
}
async function check(name, run) {
  const evidence = await run(); results.checks.push({ name, passed: true, evidence }); console.log('PASS', name, evidence);
}
(async () => {
  try {
    await new Promise((resolve, reject) => { proxy.once('error', reject); proxy.listen(33318, '127.0.0.1', resolve); });
    let s = await h.start('resilience-async-database', 25657, true, folia, 33318);
    let c = await h.connect(s, 'AsyncDatabaseQA'); c.write('player_loaded', {}); await h.open(c); await pending(s, c, false);
    await check('delayed menu commits preserve quantity while tick and cached counts remain responsive', async () => {
      s.cmd('give AsyncDatabaseQA diamond 16'); await h.until(() => h.total(c, 'diamond', 54, 90) === 16, 'seed diamond');
      delay = 60;
      const slot = c.window.items.findIndex((item, n) => n >= 54 && item?.itemCount === 16);
      const start = performance.now(); h.sendClick(c, slot); await pending(s, c, true);
      const samples = [];
      for (let i = 0; i < 5; i++) {
        const began = performance.now(); await probe(s, `vcprobe tickping delay${i}`, `VC_TICK_PING_delay${i}=`);
        samples.push(performance.now() - began);
      }
      assert(Math.max(...samples) < 700, JSON.stringify(samples));
      await h.until(() => h.total(c, 'diamond') === 16 && h.total(c, 'diamond', 54, 90) === 0, 'async deposit', 30000);
      await pending(s, c, false); delay = 0;
      return { clickToConfirmationMs: performance.now() - start, tickPingMs: samples, total: 16 };
    });
    await check('ordinary cursor close settles and permits clean reconnect', async () => {
      h.sendClick(c, 0, 0, 0);
      await h.until(() => c.window?.cursor?.itemCount === 16, 'withdraw to cursor');
      await pending(s, c, false);
      c.write('close_window', { windowId: c.window.id });
      await h.sleep(500); await pending(s, c, false);
      c.end('Clean cursor logout'); await h.sessionClosed(s, c.username);
      c = await h.connect(s, 'AsyncDatabaseQA'); c.write('player_loaded', {}); await h.open(c); await pending(s, c, false);
      assert.equal(h.total(c, 'diamond') + h.total(c, 'diamond', 54, 90), 16);
      return { total: 16, reconnect: true };
    });
    await check('cursor death after settled withdrawal preserves its real source and reconnects', async () => {
      // Keep the diamond sentinel in player storage; use a separate source for the death path.
      await h.deposit(c, 'quartz', 7); await pending(s, c, false);
      const slot = c.window.items.findIndex((item, n) => n < 45 && item?.itemCount === 7);
      assert(slot >= 0);
      h.sendClick(c, slot, 0, 0); await h.until(() => c.window?.cursor?.itemCount === 7, 'death cursor');
      await pending(s, c, false);
      s.cmd('gamemode survival AsyncDatabaseQA'); s.cmd('gamerule keepInventory true');
      const offset = s.output.length; s.cmd('kill AsyncDatabaseQA');
      await h.until(() => s.output.slice(offset).includes('Killed AsyncDatabaseQA'), 'cursor death');
      await h.until(() => c.health === 0, 'actual death health');
      await h.sleep(1000); await pending(s, c, false);
      const ground = await probe(s, 'vcprobe ground local AsyncDatabaseQA death', 'VC_GROUND_death=');
      assert(ground.includes('VC_GROUND_death=7'), ground);
      assert(!s.output.slice(offset).includes('玩家物品交易已隔离'), s.output.slice(offset));
      let respawned = false;
      c.once('respawn', () => { respawned = true; c.write('player_loaded', {}); });
      c.write('client_command', { actionId: 0 });
      await h.until(() => respawned && c.health > 0, 'actual loaded respawn', 30000);
      await h.open(c); await pending(s, c, false);
      c.end('Death source logout'); await h.sessionClosed(s, c.username);
      c = await h.connect(s, 'AsyncDatabaseQA'); c.write('player_loaded', {}); await h.open(c); await pending(s, c, false);
      return { groundBeforeRespawn: 7, reconnect: true, respawnAcknowledged: true };
    });
    await check('backpack mutation during JDBC wait isolates instead of restoring the old inventory', async () => {
      s.cmd('give AsyncDatabaseQA emerald 9'); await h.until(() => h.total(c, 'emerald', 54, 90) === 9, 'seed emerald');
      delay = 60;
      const offset = s.output.length;
      const slot = c.window.items.findIndex((item, n) => n >= 54 && item?.itemCount === 9);
      h.sendClick(c, slot); await pending(s, c, true);
      s.cmd('give AsyncDatabaseQA gold_ingot 3');
      await h.until(() => s.output.slice(offset).includes('玩家物品交易已隔离'), 'changed inventory isolation', 30000);
      delay = 0;
      await h.until(() => s.output.slice(offset).includes('AsyncDatabaseQA lost connection'), 'isolation disconnect');
      await h.sessionClosed(s, c.username);
      const journal = await probe(s, 'vcprobe playerjournal AsyncDatabaseQA isolated', 'VC_PLAYER_JOURNAL_isolated=');
      assert(journal.includes('VC_PLAYER_JOURNAL_isolated=QUARANTINED'));
      return { state: 'QUARANTINED', oldPlanApplied: false };
    });
    await h.stop(s);
    await check('isolated saved inventory retains the actual emerald and injected gold', async () => {
      const id = require('node:crypto').createHash('md5').update('OfflinePlayer:AsyncDatabaseQA').digest();
      id[6] = (id[6] & 0x0f) | 0x30; id[8] = (id[8] & 0x3f) | 0x80;
      const hex = id.toString('hex'), uuid = `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
      const parsed = nbt.simplify((await nbt.parse(fs.readFileSync(path.join(s.dir, 'world/playerdata', uuid + '.dat')))).parsed);
      const amount = name => (parsed.Inventory || []).filter(item => item.id === 'minecraft:' + name)
        .reduce((sum, item) => sum + Number(item.count || item.Count || 0), 0);
      assert.equal(amount('emerald'), 9); assert.equal(amount('gold_ingot'), 3); assert.equal(amount('diamond'), 16);
      return { playerEmerald: 9, playerGold: 3, playerDiamond: 16 };
    });
    assert(!/Could not pass event|not owned by current region|Thread failed/.test(s.output), s.output.slice(-7000));
  } catch (failed) { results.error = failed.stack; console.error(failed); process.exitCode = 1; }
  finally {
    delay = 0; h.clients.forEach(c => c.end('Cleanup')); await Promise.all(h.servers.map(h.stop));
    for (const socket of sockets) socket.destroy();
    if (proxy.listening) await new Promise(resolve => proxy.close(resolve));
    fs.writeFileSync(path.join(__dirname, `${folia ? 'folia-' : ''}async-database-audit-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
