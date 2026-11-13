// Fresh, loopback-only worlds. Kill only child servers created here, after a probe-blocked cut.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const h = require('./platforms.cjs');
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh VOIDCLEAR_TEST_NAMESPACE');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { stamp, candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
const scenarios = (process.env.VOIDCLEAR_CUT_SCENARIOS || 'deposit,withdraw').split(',');
assert(scenarios.every(value => ['deposit', 'withdraw', 'cursor', 'drop', 'close', 'overflow', 'drag', 'quit'].includes(value)), 'Unknown cut scenario');
const stages = (process.env.VOIDCLEAR_CUT_STAGES || 'prepared,storage,receipt,confirmed').split(',');
assert(stages.every(value => ['prepared', 'storage', 'cursor', 'receipt', 'confirmed'].includes(value)), 'Unknown cut stage');
const backends = (process.env.VOIDCLEAR_CUT_BACKENDS || 'sqlite,mysql').split(',');
assert(backends.every(value => ['sqlite', 'mysql'].includes(value)), 'Unknown backend');
const port = Number(process.env.VOIDCLEAR_CUT_PORT || 25657);
assert([25657, 25658].includes(port), 'Only isolated cut ports are allowed');
function save() { fs.writeFileSync(path.join(__dirname, `player-cuts-${stamp}.json`), JSON.stringify(results, null, 2)); }
async function probe(s, command, marker) {
   const offset = s.output.length; s.cmd(command);
   // Large evidence lines can span stdout chunks; the marker alone is not the complete receipt.
   await h.until(() => {
     const output = s.output.slice(offset), start = output.indexOf(marker);
     return start >= 0 && output.indexOf('\n', start) >= 0 || output.includes('VC_PROBE_FAILED');
   }, command, 30000);
  const output = s.output.slice(offset); assert(!output.includes('VC_PROBE_FAILED'), output); return output;
}
async function cut(mysql, direction, stage) {
  const backend = mysql ? 'mysql' : 'sqlite';
  const name = `resilience-cut-${backend}-${direction}-${stage}`;
  const username = `Cut${mysql ? 'M' : 'S'}${direction.slice(0, 3)}${stage.slice(0, 4)}`;
  let s, c;
  try {
    s = await h.start(name, port, mysql); c = await h.connect(s, username); await h.open(c);
    s.cmd('vc trash clear confirm'); s.cmd(`clear ${username}`); await h.sleep(800); await h.open(c);
    if (direction !== 'deposit') {
      await h.deposit(c, 'diamond', 16); await h.until(() => h.total(c, 'diamond') === 16, 'cut bin seed');
      await h.settled(s, c);
    } else {
      s.cmd(`give ${username} diamond 16`); await h.until(() => h.total(c, 'diamond', 54, 90) === 16, 'cut player seed');
    }
    if (['close', 'overflow', 'drag', 'quit'].includes(direction)) {
      h.sendClick(c, 0, 0);
      await h.until(() => c.window.cursor?.itemCount === 16, 'close cursor seed');
      await h.settled(s, c);
      if (direction === 'overflow') {
        s.cmd(`give ${username} stone 2304`);
        await h.until(() => h.total(c, 'stone', 54, 90) === 2304, 'full storage for close overflow');
      }
    }
    await probe(s, `vcprobe checkpoint ${username} baseline`, 'VC_CHECKPOINT_baseline');
    s.cmd('save-off');
    await probe(s, `vcprobe playercut ${stage}`, `VC_PLAYER_CUT_ARMED_${stage}`);
    const slot = direction !== 'deposit' ? 0 : c.window.items.findIndex((item, n) => n >= 54 && item?.itemCount === 16);
    if (direction === 'quit') c.end('Quit with cursor');
    else if (direction === 'drag') {
      for (const [slot, button] of [[-999, 0], [20, 1], [21, 1], [-999, 2]]) h.sendClick(c, slot, 5, button);
    }
    else if (direction === 'close' || direction === 'overflow') c.write('close_window', { windowId: c.window.id });
    else h.sendClick(c, slot, direction === 'cursor' ? 0 : direction === 'drop' ? 4 : 1, direction === 'drop' ? 1 : 0);
    await h.until(() => new RegExp(`VC_PLAYER_CUT_${stage}(?:\\r?\\n)`).test(s.output), 'blocked cut', 30000);
    // The exact marker has a separate line; ARMED must never be mistaken for reaching the boundary.
    assert(new RegExp(`VC_PLAYER_CUT_${stage}(?:\\r?\\n)`).test(s.output));
    s.child.kill('SIGKILL'); await s.done; c.end('Crash cut');
    s = await h.start(name, port, mysql);
    const output = await probe(s, `vcprobe playerjournal ${username} restart`, 'VC_PLAYER_JOURNAL_restart=');
    const match = output.match(/VC_PLAYER_JOURNAL_restart=(\w+)\|([A-Za-z0-9+/=]+)/); assert(match, output);
    const evidence = Buffer.from(match[2], 'base64').toString('latin1');
    assert(evidence.includes('before.storage.length=36') && evidence.includes('planned.storage.length=36'));
    assert(evidence.includes('planned.top.') && evidence.includes('before.top.'));
    const properties = Object.fromEntries(evidence.split(/\r?\n/).filter(line => !line.startsWith('#') && line.includes('='))
      .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
    const nbt = require('node:module').createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'))('prismarine-nbt');
    async function amount(value) {
      if (!value || value === 'empty') return 0;
      const bytes = Buffer.from(value.replace(/\\=/g, '='), 'base64');
      const parsed = await nbt.parse(bytes); const simple = nbt.simplify(parsed.parsed);
      return simple.id === 'minecraft:diamond' ? Number(simple.count || simple.Count || 0) : 0;
    }
    let beforeTotal = 0, plannedTotal = 0;
    for (const [key, value] of Object.entries(properties)) {
      if (/^before\.(storage|top|cursor|offhand)\.\d+$/.test(key)) beforeTotal += await amount(value);
      if (/^planned\.(storage|top|cursor|offhand|drops)\.\d+$/.test(key)) plannedTotal += await amount(value);
    }
    assert.equal(beforeTotal, 16, 'Retained before bytes must contain the actual 16 items');
    assert.equal(plannedTotal, 16, 'Retained planned bytes must contain the actual 16 items');
    let denied = false;
    try { c = await h.connect(s, username); }
    catch (error) { denied = true; assert(/VoidClear|未确认|交易/.test(error.message), error.message); }
     const expectedState = stage !== 'confirmed' ? 'PREPARED' : direction === 'cursor' ? 'CURSOR'
       : ['drop', 'overflow'].includes(direction) ? 'DROPS' : 'DONE';
     assert.equal(match[1], expectedState);
     // A saved player receipt lets login finish the acknowledgment; cursor and drop work still needs review.
     const recovered = stage === 'receipt' && !['cursor', 'drop', 'overflow'].includes(direction);
     assert.equal(denied, expectedState !== 'DONE' && !recovered);
     if (recovered) {
       const confirmation = await probe(s, `vcprobe playerjournal ${username} recovered`, 'VC_PLAYER_JOURNAL_recovered=');
       assert(confirmation.includes('VC_PLAYER_JOURNAL_recovered=DONE|'), 'Login did not durably finish acknowledgment');
     }
    if (!denied) {
      await h.open(c);
      assert.equal(h.total(c, 'diamond'), ['deposit', 'drag'].includes(direction) ? 16 : 0);
      assert.equal(h.total(c, 'diamond', 54, 90), ['withdraw', 'close', 'quit'].includes(direction) ? 16 : 0);
    }
     const row = { backend, direction, stage, state: match[1], recovered, loginDenied: denied, beforeTotal, plannedTotal,
      evidenceBytes: Buffer.from(match[2], 'base64').length, passed: true };
    results.checks.push(row); save(); console.log('PASS player cut', row);
  } finally { if (c) c.end('Cleanup'); if (s) await h.stop(s); }
}
(async () => {
  try {
    for (const backend of backends) for (const direction of scenarios)
      for (const stage of stages) await cut(backend === 'mysql', direction, stage);
    assert.equal(results.checks.length, backends.length * scenarios.length * stages.length);
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally { h.clients.forEach(c => c.end('Cleanup')); await Promise.all(h.servers.map(h.stop)); save(); }
})();
