// Fresh loopback-only Canvas worlds. Copies runtime files, never existing worlds, player data or plugin databases.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
assert(/^[A-Za-z0-9_]+$/.test(process.env.VOIDCLEAR_TEST_NAMESPACE));
const root = path.resolve(__dirname, '../target/canvas263-tests', process.env.VOIDCLEAR_TEST_NAMESPACE);
const runtime = path.resolve(__dirname, '../../测试服/生存服');
const core = path.resolve(__dirname, '../../Canvas-BentoBox-每岛末地-交付候选/canvas-26.3-island-end.jar');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const sha256 = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const report = { environment: { server: 'Canvas 26.3', coreSha256: sha256(core), client: '1.21.8 through ViaVersion 5.12.2 + ViaBackwards 5.12.2' }, candidate, sha256: sha256(candidate), checks: [] };
const stamp = new Date().toISOString().replace(/[:.]/g, '-');

async function start(name, port) {
  assert([25660, 25661].includes(port));
  await new Promise((resolve, reject) => {
    const socket = require('node:net').createServer(); socket.once('error', reject);
    socket.listen({ host: '127.0.0.1', port, exclusive: true }, () => socket.close(resolve));
  });
  for (const key of ['VOIDCLEAR_DB_URL', 'VOIDCLEAR_DB_USER', 'VOIDCLEAR_DB_PASSWORD']) assert(!process.env[key], key);
  const dir = path.join(root, name);
  fs.mkdirSync(path.join(dir, 'plugins/VoidClear/menus'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'config'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'cache'), { recursive: true });
  fs.cpSync(path.join(runtime, 'libraries'), path.join(dir, 'libraries'), { recursive: true, force: false });
  assert.equal(sha256(path.join(runtime, 'cache/mojang_26.3.jar')), 'd052f14d7a173734fba553711e5b570162e2f2a313267ee31a21b975a679be64');
  fs.copyFileSync(path.join(runtime, 'cache/mojang_26.3.jar'), path.join(dir, 'cache/mojang_26.3.jar'));
  fs.copyFileSync(core, path.join(dir, 'server.jar'));
  fs.copyFileSync(candidate, path.join(dir, 'plugins/VoidClear.jar'));
  fs.copyFileSync(path.resolve(__dirname, '../target/canvas-probe/VoidClearCanvasProbe.jar'), path.join(dir, 'plugins/VoidClearCanvasProbe.jar'));
  for (const file of ['ViaVersion-5.12.2.jar', 'ViaBackwards-5.12.2.jar']) fs.copyFileSync(path.join(runtime, 'plugins', file), path.join(dir, 'plugins', file));
  for (const file of ['config.yml', 'messages.yml', 'menus/bin.yml']) fs.copyFileSync(path.resolve(__dirname, '../src/main/resources', file), path.join(dir, 'plugins/VoidClear', file));
  fs.writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n');
  fs.writeFileSync(path.join(dir, 'server.properties'), `server-ip=127.0.0.1\nserver-port=${port}\nonline-mode=false\nenforce-secure-profile=false\nwhite-list=false\nenforce-whitelist=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=4\nmotd=VoidClear isolated Canvas acceptance\n`);
  const log = fs.createWriteStream(path.join(dir, 'acceptance.log'), { flags: 'a' });
  log.write(`\nTEST START ${new Date().toISOString()} candidate=${report.sha256}\n`);
  const child = spawn('java', ['-Xms256M', '-Xmx1536M', '-Dvoidclear.probe=true', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Dorg.bukkit.plugin.java.LibraryLoader.centralURL=https://maven.aliyun.com/repository/central', '-jar', 'server.jar', '--nogui'], { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'] });
  const s = { name, dir, port, child, output: '', exited: false };
  s.done = new Promise(resolve => child.once('close', code => { s.exited = true; log.end(() => resolve(code)); }));
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { s.output += bytes.toString('utf8'); log.write(bytes); });
  child.on('error', error => { s.output += error.stack; });
  s.cmd = command => { if (!s.exited) child.stdin.write(command + '\n'); };
  h.servers.push(s);
  await h.until(() => s.exited || s.output.includes('Done ('), name + ' startup', 240000);
  assert(!s.exited && !s.output.includes('Error occurred while enabling VoidClear'), s.output.slice(-12000));
  assert(s.output.includes('Canvas'), 'Not a Canvas runtime');
  assert(s.output.includes('玩家收据验证目录：'), s.output.slice(-10000));
  console.log('READY CANVAS', name, port);
  return s;
}

async function logout(s, c) {
  const offset = s.output.length; c.end('Acceptance logout');
  await h.until(() => s.output.slice(offset).includes(c.username + ' left the game'), 'Canvas quit');
}

async function session(s, uuid, closed = false) {
  const offset = s.output.length; s.cmd('vccanvas session ' + uuid);
  const poll = closed ? setInterval(() => s.cmd('vccanvas session ' + uuid), 100) : null;
  try {
    await h.until(() => s.output.slice(offset).includes('VC_CANVAS_SESSION ' + uuid), 'Canvas session status');
    if (closed) await h.until(() => s.output.slice(offset).includes('sessions=false joined=false retired=false session=null online=false'), 'Canvas session release');
    const state = s.output.slice(offset).split(/\r?\n/).filter(line => line.includes('VC_CANVAS_SESSION ' + uuid)).at(-1);
    console.log(state);
    return state.split('VC_CANVAS_SESSION ')[1];
  } finally { if (poll) clearInterval(poll); }
}

async function denied(s, username) {
  let reason;
  try { await h.connect(s, username); } catch (error) { reason = error.message; }
  assert(reason && /VoidClear|会话|交易/.test(reason), 'Duplicate login was admitted or failed for an unrelated reason: ' + reason);
  return reason;
}

(async () => {
  let s, c;
  const watchdog = setTimeout(() => { for (const server of h.servers) server.cmd('stop'); process.exitCode = 1; }, 600000);
  try {
    s = await start('normal', 25660);
    c = await h.connect(s, 'CanvasReceipt'); await h.open(c);
    await h.deposit(c, 'diamond', 16);
    assert.equal(h.total(c, 'diamond', 54, 90), 0);
    const depositedFile = path.join(s.dir, 'world/players/data', c.uuid + '.dat');
    assert(fs.existsSync(depositedFile), 'Actual 26.3 player file missing');
    h.sendClick(c, 0);
    await h.until(() => h.total(c, 'diamond') === 0 && h.total(c, 'diamond', 54, 90) === 16, 'Canvas withdrawal');
    report.checks.push({ name: 'new player can deposit and withdraw 16 on Canvas 26.3', passed: true });
    const live = await session(s, c.uuid);
    report.duplicateReason = await denied(s, c.username);
    assert.equal(await session(s, c.uuid), live, 'Duplicate login replaced the live owner');
    await h.open(c);
    assert.equal(h.total(c, 'diamond', 54, 90), 16); assert.equal(h.total(c, 'diamond'), 0);
    report.checks.push({ name: 'Canvas duplicate login leaves the original session and inventory usable', passed: true });
    const delayOffset = s.output.length; s.cmd('vccanvas delayquit 20');
    await h.until(() => s.output.slice(delayOffset).includes('VC_CANVAS_DELAY_ARMED'), 'arm deterministic quit race');
    const leaving = logout(s, c);
    await h.until(() => s.output.slice(delayOffset).includes('VC_CANVAS_DELAY_ENTER online=true'), 'hold old online connection');
    report.pendingQuitReason = await denied(s, c.username);
    assert(!s.output.slice(delayOffset).includes('VC_CANVAS_DELAY_RELEASE'), 'Duplicate attempt missed the delayed-quit window');
    assert((await session(s, c.uuid)).includes('sessions=true joined=false retired=false'), 'Pending quit released ownership while the old connection was registered');
    await leaving;
    assert(s.output.slice(delayOffset).includes('VC_CANVAS_DELAY_RELEASE online=true'), 'Fixture did not hold the closed connection in the online registry');
    report.checks.push({ name: 'Canvas pending quit denies a replacement until the original connection is removed', passed: true });
    report.quitSession = await session(s, c.uuid, true);
    c = await h.connect(s, 'CanvasReceipt'); await h.open(c);
    assert.equal(h.total(c, 'diamond', 54, 90), 16); assert.equal(h.total(c, 'diamond'), 0);
    report.checks.push({ name: 'delayed logout and rejoin retains 16', passed: true });
    report.rejoinSessions = [];
    for (let round = 0; round < 3; round++) {
      await logout(s, c); await session(s, c.uuid, true);
      c = await h.connect(s, 'CanvasReceipt'); await h.open(c);
      assert.equal(h.total(c, 'diamond', 54, 90), 16); assert.equal(h.total(c, 'diamond'), 0);
      const owner = (await session(s, c.uuid)).match(/session=([a-f0-9-]{36})/)[1];
      assert(!report.rejoinSessions.includes(owner), 'Ordinary quit reused a released session');
      report.rejoinSessions.push(owner);
      await denied(s, c.username); await h.open(c);
      assert((await session(s, c.uuid)).includes('session=' + owner), 'Old cleanup retired a newer connection');
      assert.equal(h.total(c, 'diamond', 54, 90), 16);
    }
    report.checks.push({ name: 'three immediate rejoin cycles keep distinct live sessions and exactly 16 items', passed: true });
    await logout(s, c); await session(s, c.uuid, true); await h.stop(s);
    s = await start('normal', 25660); c = await h.connect(s, 'CanvasReceipt'); await h.open(c);
    assert.equal(h.total(c, 'diamond', 54, 90), 16); assert.equal(h.total(c, 'diamond'), 0);
    report.checks.push({ name: 'clean restart and rejoin retains 16', passed: true });
    await logout(s, c); await session(s, c.uuid, true); await h.stop(s);
    const saved = fs.readFileSync(depositedFile);
    s = await start('orphan-receipt', 25661);
    const playerFile = path.join(s.dir, 'world/players/data', c.uuid + '.dat');
    fs.mkdirSync(path.dirname(playerFile), { recursive: true }); fs.writeFileSync(playerFile, saved);
    let loginDenied = false;
    try { c = await h.connect(s, 'CanvasReceipt'); } catch (error) { loginDenied = true; console.log('ORPHAN LOGIN REJECTED', error.message); }
    report.orphanReceipt = { loginDenied, playerFileUnchanged: fs.readFileSync(playerFile).equals(saved) };
    console.log('ORPHAN RESULT', report.orphanReceipt);
    assert(loginDenied, 'Expected orphan receipt fence was not reached');
    assert(report.orphanReceipt.playerFileUnchanged);
    report.checks.push({ name: 'unusable orphan-receipt condition reproduced without altering saved player', passed: true, note: 'Reproduction only, not a fix or availability acceptance' });
  } catch (error) { report.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(client => client.end('Cleanup')); await Promise.all(h.servers.map(h.stop)); clearTimeout(watchdog);
    report.finishedAt = new Date().toISOString();
    fs.writeFileSync(path.join(__dirname, `canvas263-${stamp}.json`), JSON.stringify(report, null, 2));
    console.log('CANVAS REPORT', path.join(__dirname, `canvas263-${stamp}.json`));
  }
})();
