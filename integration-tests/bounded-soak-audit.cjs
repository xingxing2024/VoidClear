// Bounded two-client survival transfer soak; no throughput claim for a production server.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
(async () => {
  try {
    let s = await h.start('resilience-bounded-soak', 25656, false);
    let a = await h.connect(s, 'SoakAuditA'), b = await h.connect(s, 'SoakAuditB');
    s.cmd('gamemode survival SoakAuditA'); s.cmd('gamemode survival SoakAuditB');
    a.write('player_loaded', {}); b.write('player_loaded', {});
    await h.open(a); await h.open(b);
    const began = Date.now(), times = [];
    while (Date.now() - began < 120000 || times.length < 50) {
      const start = performance.now();
      await h.deposit(a, 'iron_ingot', 16);
      await h.until(() => h.total(b, 'iron_ingot') === 16, 'second viewer publish');
      h.sendClick(a, 0); h.sendClick(b, 0);
      await h.until(() => h.total(a, 'iron_ingot') === 0 && h.total(b, 'iron_ingot') === 0
        && h.total(a, 'iron_ingot', 54, 90) + h.total(b, 'iron_ingot', 54, 90) === 16, 'conservation');
      times.push(performance.now() - start);
      s.cmd('clear SoakAuditA'); s.cmd('clear SoakAuditB');
      await h.until(() => h.total(a, 'iron_ingot', 54, 90) + h.total(b, 'iron_ingot', 54, 90) === 0, 'reset');
    }
    times.sort((a, b) => a - b);
    results.checks.push({ name: 'two-minute survival competing transfers conserve each round', passed: true,
      rounds: times.length, durationMs: Date.now() - began, endToEndP95Ms: times[Math.ceil(times.length * .95) - 1], includesProtocolPolling: true });
    await h.deposit(a, 'gold_ingot', 7);
    a.end('Clean restart'); b.end('Clean restart');
    await h.until(() => s.output.includes('SoakAuditA left the game') && s.output.includes('SoakAuditB left the game'), 'logout');
    await h.sessionClosed(s, 'SoakAuditA'); await h.sessionClosed(s, 'SoakAuditB');
    assert(!/玩家物品交易已隔离|VC_PROBE_FAILED|Could not pass event/.test(s.output), s.output.slice(-10000));
    await h.stop(s);
    s = await h.start('resilience-bounded-soak', 25656, false);
    a = await h.connect(s, 'SoakAuditA'); b = await h.connect(s, 'SoakAuditB');
    await h.open(a); await h.open(b);
    assert.equal(h.total(a, 'gold_ingot'), 7); assert.equal(h.total(b, 'gold_ingot'), 7);
    assert.equal(h.total(a, 'iron_ingot') + h.total(b, 'iron_ingot')
      + h.total(a, 'iron_ingot', 54, 90) + h.total(b, 'iron_ingot', 54, 90), 0);
    results.checks.push({ name: 'clean restart after soak preserves sentinel and empty iron balance', passed: true });
    console.log('PASS', results.checks);
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(c => c.end('Cleanup'));
    await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `bounded-soak-audit-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
