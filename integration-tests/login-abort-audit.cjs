// Observe a real login socket abort after pre-login, without bypassing any reservation or deleting evidence.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const mc = createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'))('minecraft-protocol');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), observations: [] };
(async () => {
  let s, aborted;
  try {
    s = await h.start('resilience-login-abort', 25652, false);
    const name = 'AbortLoginQA';
    let acknowledged = false;
    aborted = mc.createClient({ host: '127.0.0.1', port: s.port, username: name, auth: 'offline', version: '1.21.8', disableChatSigning: true });
    aborted.on('error', () => {});
    const write = aborted.write.bind(aborted);
    aborted.write = (packet, data) => {
      if (packet === 'finish_configuration') { acknowledged = true; aborted.socket.destroy(); return; }
      return write(packet, data);
    };
    await h.until(() => acknowledged, 'server admitted pre-login');
    await h.until(() => aborted.ended, 'aborted socket closed');
    await h.until(() => s.output.includes(name) && s.output.includes('lost connection'), 'aborted socket observed by server');
    assert(!s.output.includes(name + ' joined the game'), 'Fixture unexpectedly joined');
    let denied = false, reason;
    try { const rejoin = await h.connect(s, name); await h.open(rejoin); }
    catch (error) { denied = true; reason = error.message; assert(/VoidClear|交易|会话/.test(reason)); }
    results.observations.push({ name: 'same identity reconnect after aborted pre-login', reconnectDenied: denied, reason });
    assert(!denied, 'Exact disconnected connection must not block reconnect');
    const independent = await h.connect(s, 'UnaffectedQA');
    await h.open(independent);
    results.observations.push({ name: 'unrelated identity can enter', passed: true });
    independent.end('Audit restart');
    await h.sessionClosed(s, 'UnaffectedQA');
    await h.stop(s);
    s = await h.start('resilience-login-abort', 25652, false);
    const afterRestart = await h.connect(s, name);
    await h.open(afterRestart);
    results.observations.push({ name: 'same identity reconnects after clean server restart', passed: true });
    console.log('OBSERVED', JSON.stringify(results.observations, null, 2));
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    if (aborted) aborted.end('Cleanup');
    h.clients.forEach(c => c.end('Cleanup'));
    await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `login-abort-audit-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
