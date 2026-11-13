const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Fresh namespace required');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
async function probe(s, command, marker) {
  const offset = s.output.length; s.cmd(command);
  await h.until(() => s.output.slice(offset).includes(marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), command);
  assert(!s.output.slice(offset).includes('VC_PROBE_FAILED'), s.output.slice(offset));
}
(async () => {
  let s;
  try {
    s = await h.start('resilience-abandoned-pickup', 25652, true);
    let c = await h.connect(s, 'AbandonedQA'); await h.open(c); await h.settled(s, c);
    await probe(s, 'vcprobe dbhold', 'VC_DB_HELD');
    await probe(s, 'vcprobe unreadypickup AbandonedQA', 'VC_UNREADY_QUEUED');
    const offset = s.output.length; c.end('Quit before readiness result');
    await h.until(() => s.output.slice(offset).includes('AbandonedQA left the game'), 'quit');
    await probe(s, 'vcprobe dbrelease', 'VC_DB_RELEASED'); await h.sessionClosed(s, 'AbandonedQA');
    await probe(s, 'vcprobe unreadyreleased', 'VC_UNREADY_RELEASED');
    results.checks.push({ name: 'unprepared pickup releases its source reservation after disconnect and preserves 16 diamonds', passed: true });
    c = await h.connect(s, 'AbandonedQA'); await h.open(c); await h.settled(s, c);
    await probe(s, 'vcprobe stalecallback AbandonedQA', 'VC_STALE_CALLBACK_OK');
    await h.deposit(c, 'emerald', 9); await h.settled(s, c);
    assert.equal(h.total(c, 'emerald'), 9);
    results.checks.push({ name: 'retired connection cannot clear new pending or isolate new session; new deposit succeeds', passed: true });
    console.log('PASS', results.checks);
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    if (s && !s.exited) s.cmd('vcprobe dbrelease');
    h.clients.forEach(c => c.end('Cleanup')); await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `abandoned-pickup-audit-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
