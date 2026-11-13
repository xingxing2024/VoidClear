// Isolated Paper/SQLite regressions for the maintenance audit fixes, including fail-closed startup.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
async function probe(s, command, marker) {
  const offset = s.output.length;
  s.cmd(command);
  await h.until(() => s.output.slice(offset).includes(marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), command);
  assert(!s.output.slice(offset).includes('VC_PROBE_FAILED'), s.output.slice(offset));
}
async function startupFailure(s, config, label) {
  fs.writeFileSync(path.join(s.dir, 'plugins/VoidClear/config.yml'), config);
  const child = spawn(s.child.spawnfile, s.child.spawnargs.slice(1), { cwd: s.dir, stdio: ['pipe', 'pipe', 'pipe'] });
  let output = '', exited = false;
  const done = new Promise(resolve => child.once('close', code => { exited = true; resolve(code); }));
  for (const stream of [child.stdout, child.stderr]) stream.on('data', chunk => { output += chunk; });
  child.on('error', error => { output += error.stack; });
  try {
    await h.until(() => exited, label + ' shuts server down', 180000);
    assert(output.includes('VoidClear 启动核对失败，正在停止服务器'), output.slice(-8000));
    assert(/Stopping server/i.test(output), output.slice(-8000));
    assert(!output.includes('[VoidClear] 已启动。'), output.slice(-8000));
    results.checks.push(label + ': server stopped without transaction protection');
  } finally {
    fs.writeFileSync(path.join(s.dir, `startup-${label}.log`), output);
    if (!exited) { child.stdin.write('stop\n'); await done; }
  }
}
(async () => {
  let s;
  try {
    s = await h.start('resilience-maintenance', 25658, false);
    await probe(s, 'vcprobe reloadtimers', 'VC_RELOAD_TIMERS_OK');
    results.checks.push('reload changes interval/mode/daily time; unchanged and rejected reload retain deadline');
    const history = path.join(s.dir, 'plugins/VoidClear/history.log');
    const rows = () => fs.existsSync(history) ? fs.readFileSync(history, 'utf8').trim().split(/\r?\n/).filter(line => line.includes(' SWEEP ')) : [];
    s.cmd('vc sweep');
    await h.until(() => rows().length === 1, 'manual sweep history');
    await probe(s, 'vcprobe autosweep', 'VC_AUTO_SWEEP_ARMED');
    await h.until(() => rows().length === 2, 'automatic sweep history');
    await h.sleep(1200);
    assert.equal(rows().length, 2, 'each completed sweep logs exactly once');
    results.checks.push('manual and automatic sweep each write one history entry');
    let player = await h.connect(s, 'CursorGuard');
    await probe(s, 'vcprobe playerdrop', 'VC_PLAYER_DROP_OK');
    results.checks.push('nonempty cursor defers managed pickup without changing journal/items; empty cursor and partial claims work');
    player.end('Normal logout');
    await h.until(() => s.output.includes('CursorGuard left the game'), 'logout');
    await h.sessionClosed(s, 'CursorGuard');
    player = await h.connect(s, 'CursorGuard');
    await h.open(player);
    results.checks.push('managed pickup guard still allows normal logout/rejoin');
    assert(!/Could not pass event|Error occurred while enabling VoidClear/.test(s.output), s.output.slice(-8000));
    h.clients.forEach(c => c.end('Cleanup'));
    await h.stop(s);
    await startupFailure(s, s.config.replace('file: bin.db', "file: '.'"), 'database-failure');
    await startupFailure(s, s.config.replace('mode: interval', 'mode: invalid'), 'config-failure');
    fs.writeFileSync(path.join(s.dir, 'plugins/VoidClear/config.yml'), s.config);
    console.log('PASS', results.checks);
  } catch (error) {
    results.error = error.stack;
    console.error(error);
    process.exitCode = 1;
  } finally {
    h.clients.forEach(c => c.end('Cleanup'));
    if (s) await h.stop(s);
    else await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `maintenance-fixes-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
