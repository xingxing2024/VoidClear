// Isolated Paper/SQLite regression: real item serialization, unaffected pages, and ordinary player transfers.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
(async () => {
  let server;
  try {
    server = await h.start('resilience-item-fences', 25658, false);
    server.cmd('vcprobe itemfences');
    await h.until(() => /VC_ITEM_FENCES_OK|VC_PROBE_FAILED/.test(server.output), 'real item fences');
    assert(!server.output.includes('VC_PROBE_FAILED'), server.output.slice(-6000));
    results.checks.push('pending item fence survives restart, sweep merge and page shift; unrelated page remains usable');
    let a = await h.connect(server, 'FenceA');
    const b = await h.connect(server, 'FenceB');
    await h.open(a); await h.open(b);
    await h.deposit(a, 'diamond', 16);
    await h.until(() => h.total(b, 'diamond') === 16, 'shared deposit');
    h.sendClick(b, 0);
    await h.until(() => h.total(b, 'diamond', 54, 90) === 16 && h.total(b, 'diamond') === 0, 'withdrawal conservation');
    results.checks.push('normal player deposit and withdrawal conserve 16 diamonds');
    a.end('Normal logout');
    await h.until(() => server.output.includes('FenceA left the game'), 'logout');
    await h.sessionClosed(server, 'FenceA');
    a = await h.connect(server, 'FenceA');
    await h.open(a);
    assert.equal(h.total(a, 'diamond'), 0);
    results.checks.push('normal logout/rejoin retains receipt and session checks');
    assert(!/Could not pass event|Error occurred while enabling VoidClear/.test(server.output), server.output.slice(-6000));
    console.log('PASS', results.checks);
  } catch (error) {
    results.error = error.stack;
    console.error(error);
    process.exitCode = 1;
  } finally {
    h.clients.forEach(c => c.end('Cleanup'));
    if (server) await h.stop(server);
    fs.writeFileSync(path.join(__dirname, `item-fences-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
