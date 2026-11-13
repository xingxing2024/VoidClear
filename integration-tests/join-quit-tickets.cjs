// Disconnect after Join while its database check is queued; no world/player evidence is rewritten.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const h = require('./platforms.cjs');
const mc = require('node:module').createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'))('minecraft-protocol');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Fresh isolated namespace required');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
async function probe(s, command, marker) {
  const offset = s.output.length; s.cmd(command);
  await h.until(() => s.output.slice(offset).includes(marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), command);
  assert(!s.output.slice(offset).includes('VC_PROBE_FAILED'), s.output.slice(offset));
}
(async () => {
  let s, paused;
  try {
    s = await h.start('resilience-join-quit-tickets', 25652, true);
    let c = await h.connect(s, 'JoinQuitQA'); await h.open(c);
    await h.deposit(c, 'diamond', 16); await h.settled(s, c);
    s.cmd('give JoinQuitQA stone 2304');
    await h.until(() => h.total(c, 'stone', 54, 90) === 2304, 'full inventory');
    h.sendClick(c, 0, 4, 1);
    await h.until(() => h.total(c, 'diamond') === 0, 'source debit'); await h.settled(s, c);
    c.end('Source logout'); await h.sessionClosed(s, c.username);
    paused = mc.createClient({ host: '127.0.0.1', port: s.port, username: 'JoinQuitQA', auth: 'offline', version: '1.21.8', disableChatSigning: true });
    paused.on('error', () => {});
    const write = paused.write.bind(paused);
    let configuration;
    paused.write = (name, data) => { if (name === 'finish_configuration') { configuration = data; return; } return write(name, data); };
    await h.until(() => configuration !== undefined, 'source login paused');
    await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=1');
    await probe(s, 'vcprobe dbhold', 'VC_DB_HELD');
    const offset = s.output.length;
    paused.state = 'configuration';
    write('finish_configuration', configuration);
    paused.state = 'play';
    await h.until(() => s.output.slice(offset).includes('JoinQuitQA joined the game'), 'joined with queued verification');
    paused.end('Quit before database verification');
    await h.until(() => s.output.slice(offset).includes('JoinQuitQA left the game'), 'quit while database held');
    await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=0');
    await probe(s, 'vcprobe dbrelease', 'VC_DB_RELEASED');
    await h.sessionClosed(s, 'JoinQuitQA');
    c = await h.connect(s, 'JoinQuitQA'); await h.open(c); await h.settled(s, c);
    await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=0');
    await probe(s, 'vcprobe rejoindrop JoinQuitQA inspect 0', 'VC_REJOIN_DROP_inspect_OK');
    results.checks.push({ name: 'pending Join quit releases actual tickets before database resumes; reconnect preserves source', passed: true });
    console.log('PASS', results.checks);
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    if (s && !s.exited) s.cmd('vcprobe dbrelease');
    paused?.end('Cleanup'); h.clients.forEach(c => c.end('Cleanup'));
    await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `join-quit-tickets-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
