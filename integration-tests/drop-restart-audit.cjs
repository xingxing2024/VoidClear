// No crash or corrupt data: drop from bin, clean logout/rejoin, normal restart, then witness pickup.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const port = Number(process.env.VOIDCLEAR_DROP_PORT || 25656);
assert([25652, 25656].includes(port), 'Only isolated source audit ports are allowed');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), observations: [] };
async function probe(s, command, marker) {
  const offset = s.output.length;
  s.cmd(command);
  await h.until(() => s.output.slice(offset).includes(marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), command);
  assert(!s.output.slice(offset).includes('VC_PROBE_FAILED'), s.output.slice(offset));
}
async function logout(s, c) {
  const offset = s.output.length;
  c.end('Normal logout');
  await h.until(() => s.output.slice(offset).includes(c.username + ' left the game'), 'logout');
  await h.sessionClosed(s, c.username);
}
async function pauseLogin(s, username) {
  const mc = require('node:module').createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'))('minecraft-protocol');
  const client = mc.createClient({ host: '127.0.0.1', port: s.port, username, auth: 'offline', version: '1.21.8', disableChatSigning: true });
  client.on('error', () => {});
  const write = client.write.bind(client);
  let configured = false;
  client.write = (packet, data) => {
    if (packet === 'finish_configuration') { configured = true; return; }
    return write(packet, data);
  };
  await h.until(() => configured, 'paused pre-login ' + username);
  return client;
}
(async () => {
  let s;
  try {
    s = await h.start('resilience-drop-restart', port, false);
    let c = await h.connect(s, 'DropRestartQA');
    s.cmd('tp DropRestartQA 1000 -60 1000');
    await h.until(() => c.pos?.x > 999 && c.pos?.z > 999, 'non-spawn source location');
    s.cmd('gamemode survival DropRestartQA');
    await h.open(c);
    await h.deposit(c, 'diamond', 16);
    s.cmd('give DropRestartQA stone 2304');
    await h.until(() => h.total(c, 'stone', 54, 90) === 2304, 'full inventory');
    h.sendClick(c, 0, 4, 1);
    await h.until(() => h.total(c, 'diamond') === 0, 'drop debit');
    c.write('close_window', { windowId: c.window.id });
    await probe(s, 'vcprobe rejoindrop DropRestartQA inspect 0', 'VC_REJOIN_DROP_inspect_OK');
    await logout(s, c);
    c = await h.connect(s, 'DropRestartQA');
    await h.open(c);
    results.observations.push({ name: 'same-process normal logout/rejoin with unclaimed drop', allowed: true });
    await logout(s, c);
    await h.stop(s);
    s = await h.start('resilience-drop-restart', port, false);
    const mc = require('node:module').createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'))('minecraft-protocol');
    const aborted = mc.createClient({ host: '127.0.0.1', port: s.port, username: 'DropRestartQA', auth: 'offline', version: '1.21.8', disableChatSigning: true });
    aborted.on('error', () => {});
    const write = aborted.write.bind(aborted);
    let configured = false;
    aborted.write = (packet, data) => {
      if (packet === 'finish_configuration') { configured = true; aborted.socket.destroy(); return; }
      return write(packet, data);
    };
    await h.until(() => configured && aborted.ended, 'source owner aborted login');
    await h.sleep(1500);
    await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=0');
    results.observations.push({ name: 'source owner aborted pre-login releases temporary chunk tickets', passed: true });
    let denied = false, reason;
    try { c = await h.connect(s, 'DropRestartQA'); }
    catch (error) { denied = true; reason = error.message; assert(/VoidClear|会话|交易/.test(reason)); }
    results.observations.push({ name: 'normal server restart with unclaimed drop', loginDenied: denied, reason });
    assert(!denied, 'Verified saved world source must allow rejoin after a clean restart');
    await h.open(c);
    assert.equal(h.total(c, 'diamond', 54, 90), 0);
    await h.sleep(200);
    await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=0');
    const witness = await h.connect(s, 'DropWitnessQA');
    s.cmd('tp DropWitnessQA 1000 -60 1000');
    await h.until(() => witness.pos?.x > 999, 'witness source location');
    await probe(s, 'vcprobe rejoindrop DropWitnessQA consume 16', 'VC_REJOIN_DROP_consume_OK');
    await h.open(c); await h.open(witness);
    assert.equal(h.total(c, 'diamond'), 0);
    assert.equal(h.total(c, 'diamond', 54, 90), 0);
    assert.equal(h.total(witness, 'diamond', 54, 90), 16);
    results.observations.push({ name: 'original player remains admitted while witness consumes source', allowed: true, totalDiamond: 16 });
    // Both owners create sources inside the same chunk; one disconnect must retain the other's ticket.
    for (const owner of [c, witness]) {
      s.cmd('clear ' + owner.username);
      await h.until(() => h.total(owner, 'stone', 54, 90) + h.total(owner, 'diamond', 54, 90) === 0, 'shared fixture reset');
      s.cmd('give ' + owner.username + ' stone 2304');
      await h.until(() => h.total(owner, 'stone', 54, 90) === 2304, 'shared full inventory');
    }
    const seeder = await h.connect(s, 'SharedSeeder');
    await h.open(seeder);
    for (const owner of [c, witness]) {
      await h.deposit(seeder, 'quartz', 16);
      await h.until(() => h.total(owner, 'quartz') === 16, 'shared source seed');
      h.sendClick(owner, 0, 4, 1);
      await h.until(() => h.total(owner, 'quartz') === 0, 'shared source debit');
      owner.write('close_window', { windowId: owner.window.id });
      await logout(s, owner);
    }
    await logout(s, seeder);
    await h.stop(s);
    s = await h.start('resilience-drop-restart', port, false);
    const first = await pauseLogin(s, 'DropRestartQA');
    const second = await pauseLogin(s, 'DropWitnessQA');
    try {
      await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=1');
      first.socket.destroy();
      await h.sleep(1500);
      await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=1');
      second.socket.destroy();
      await h.sleep(1500);
      await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=0');
    } finally { first.socket.destroy(); second.socket.destroy(); }
    c = await h.connect(s, 'DropRestartQA');
    await h.open(c);
    await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=0');
    results.observations.push({ name: 'shared source chunk survives first disconnect and releases after both disconnects', passed: true });
    await logout(s, c);
    const stoppingLogin = await pauseLogin(s, 'DropRestartQA');
    try {
      await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=1');
      await h.stop(s);
      assert(!/停用清理失败|Could not pass event|Error occurred while disabling/.test(s.output), s.output.slice(-9000));
    } finally { stoppingLogin.socket.destroy(); }
    s = await h.start('resilience-drop-restart', port, false);
    c = await h.connect(s, 'DropRestartQA');
    await h.open(c);
    await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=0');
    results.observations.push({ name: 'shutdown with an active source login releases tickets and permits restart', passed: true });
    // A chunk hint cannot admit an owner when the actual source has disappeared.
    await probe(s, 'vcprobe loadedsource DropRestartQA', 'VC_LOADED_SOURCE=true');
    s.cmd('kill @e[type=item]');
    await h.sleep(300);
    await probe(s, 'vcprobe loadedsource DropRestartQA', 'VC_LOADED_SOURCE=false');
    await logout(s, c);
    await h.stop(s);
    s = await h.start('resilience-drop-restart', port, false);
    let missingDenied = false;
    try { await h.connect(s, 'DropRestartQA'); }
    catch (error) { missingDenied = true; assert(/VoidClear|会话|交易/.test(error.message)); }
    assert(missingDenied, 'Missing source must not be replaced by a chunk hint');
    await probe(s, 'vcprobe logintickets', 'VC_LOGIN_TICKETS=0');
    results.observations.push({ name: 'missing actual source denies owner and releases real chunk tickets', passed: true,
      verifiedBeforeRemoval: true, verifiedAfterRemoval: false, loginDenied: true });
    console.log('OBSERVED', JSON.stringify(results.observations, null, 2));
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(c => c.end('Cleanup'));
    await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `drop-restart-audit-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
