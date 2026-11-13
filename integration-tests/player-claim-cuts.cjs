// Claim-specific fixed cuts on isolated worlds; no grants or replay after restart.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const h = require('./platforms.cjs');
const port = Number(process.env.VOIDCLEAR_CLAIM_PORT || 25658);
assert([25655, 25658].includes(port), 'Only isolated claim ports are allowed');
const nbt = require('node:module').createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'))('prismarine-nbt');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
const backends = (process.env.VOIDCLEAR_CLAIM_BACKENDS || 'sqlite').split(',');
assert(backends.every(value => ['sqlite', 'mysql'].includes(value)), 'Unknown claim backend');
async function probe(s, command, marker) {
  const offset = s.output.length; s.cmd(command);
  await h.until(() => {
    const output = s.output.slice(offset), start = output.indexOf(marker);
    return start >= 0 && output.indexOf('\n', start) >= 0 || output.includes('VC_PROBE_FAILED');
  }, command, 30000);
  const output = s.output.slice(offset); assert(!output.includes('VC_PROBE_FAILED'), output); return output;
}
async function amount(encoded) {
  if (encoded === 'empty') return 0;
  const parsed = await nbt.parse(Buffer.from(encoded.replace(/\\=/g, '='), 'base64'));
  const item = nbt.simplify(parsed.parsed);
  return item.id === 'minecraft:diamond' ? Number(item.count || item.Count || 0) : 0;
}
(async () => {
  try {
    for (const backend of backends) for (const kind of ['full', 'partial', 'settled']) for (const stage of kind === 'full' ? ['prepared', 'storage', 'receipt', 'confirmed'] : ['receipt', 'confirmed']) {
      const partial = kind !== 'full', settled = kind === 'settled';
      let s, c, other;
      const name = 'Claim' + stage;
      const world = `resilience-claim-${backend}-${kind}-${stage}`;
      try {
        s = await h.start(world, port, backend === 'mysql'); c = await h.connect(s, name); await h.open(c);
        await h.deposit(c, 'diamond', 16); await h.until(() => h.total(c, 'diamond') === 16, 'seed source');
        await h.settled(s, c);
        s.cmd(`give ${name} stone 2304`);
        await h.until(() => h.total(c, 'stone', 54, 90) === 2304, 'prevent natural pickup');
        h.sendClick(c, 0, 4, 1); await h.until(() => h.total(c, 'diamond') === 0, 'drop debit');
        await h.settled(s, c);
        s.cmd('execute as @e[type=item] run data merge entity @s {PickupDelay:32767s,NoGravity:1b}');
        c.write('close_window', { windowId: c.window.id });
        const setup = partial ? 'fillpartial' : 'preparefull';
        await probe(s, `vcprobe rejoindrop ${name} ${setup} ${partial ? 60 : 0}`, `VC_REJOIN_DROP_${setup}_OK`);
        if (settled) {
          const offset = s.output.length; s.cmd('save-all flush');
          await h.until(() => s.output.slice(offset).includes('Saved the game'), 'persist source before partial cut', 30000);
        }
        s.cmd('save-off');
        await probe(s, `vcprobe playercut ${stage}`, `VC_PLAYER_CUT_ARMED_${stage}`);
        s.cmd(`vcprobe rejoindrop ${name} ${partial ? 'pickup' : 'pickupfull'} ${partial ? 64 : 16}`);
        await h.until(() => new RegExp(`VC_PLAYER_CUT_${stage}(?:\r?\n)`).test(s.output), 'claim blocked cut');
        s.child.kill('SIGKILL'); await s.done; c.end('Claim crash');
        const playerFile = path.join(s.dir, 'world/playerdata', c.uuid + '.dat');
        const player = nbt.simplify((await nbt.parse(fs.readFileSync(playerFile))).parsed);
        const held = player.Inventory.filter(item => item.id === 'minecraft:diamond').reduce((n, item) => n + Number(item.count || item.Count || 0), 0);
        const saved = ['receipt', 'confirmed'].includes(stage);
        assert.equal(held, partial ? 64 : saved ? 16 : 0, 'Actual player file quantity at crash boundary');
        s = await h.start(world, port, backend === 'mysql');
        const output = await probe(s, `vcprobe playerjournal ${name} claim claim`, 'VC_PLAYER_JOURNAL_claim=');
        const match = output.match(/VC_PLAYER_JOURNAL_claim=(\w+)\|([A-Za-z0-9+/=]+)/); assert(match);
        const evidence = Buffer.from(match[2], 'base64').toString('latin1');
        const properties = Object.fromEntries(evidence.split(/\r?\n/).filter(line => !line.startsWith('#') && line.includes('='))
          .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
        let before = 0, planned = 0;
        for (let i = 0; i < 36; i++) { before += await amount(properties['before.storage.' + i]); planned += await amount(properties['planned.storage.' + i]); }
        assert.equal(before, partial ? 60 : 0); assert.equal(planned, partial ? 64 : 16);
        assert.equal(await amount(properties.source), 16); assert.equal(await amount(properties.remainder), partial ? 12 : 0);
        assert.equal(match[1], stage === 'confirmed' ? 'DONE' : 'PREPARED');
        let denied = false;
        try { c = await h.connect(s, name); }
        catch (error) { denied = true; assert(/VoidClear|未确认|交易/.test(error.message)); }
        assert.equal(denied, !saved || partial && !settled, 'Saved claim requires a persisted remainder or terminal source');
        if (settled) {
          other = await h.connect(s, 'RemainderWitness');
          await probe(s, 'vcprobe rejoindrop RemainderWitness consume 12', 'VC_REJOIN_DROP_consume_OK');
          await h.open(c);
          assert.equal(h.total(c, 'diamond'), 0); assert.equal(h.total(c, 'diamond', 54, 90), 64);
          await probe(s, `vcprobe rejoindrop ${name} verifyfull 64`, 'VC_REJOIN_DROP_verifyfull_OK');
          await h.open(other); assert.equal(h.total(other, 'diamond', 54, 90), 12);
          const confirmed = await probe(s, `vcprobe playerjournal ${name} recovered claim`, 'VC_PLAYER_JOURNAL_recovered=');
          assert(confirmed.includes('VC_PLAYER_JOURNAL_recovered=DONE|'));
          const offset = s.output.length;
          c.end('Settled original'); other.end('Settled witness');
          await h.until(() => s.output.slice(offset).includes(name + ' left the game')
            && s.output.slice(offset).includes('RemainderWitness left the game'), 'settled logouts');
          await h.sessionClosed(s, name); await h.sessionClosed(s, 'RemainderWitness'); await h.stop(s);
          s = await h.start(world, port, backend === 'mysql');
          c = await h.connect(s, name); other = await h.connect(s, 'RemainderWitness');
          await h.open(c); await h.open(other);
          assert.equal(h.total(c, 'diamond', 54, 90), 64); assert.equal(h.total(other, 'diamond', 54, 90), 12);
          assert.equal(h.total(c, 'diamond'), 0);
          await probe(s, `vcprobe rejoindrop ${name} verifyfull 64`, 'VC_REJOIN_DROP_verifyfull_OK');
        }
        if (!denied && !partial) {
          await h.open(c);
          assert.equal(h.total(c, 'diamond'), 0);
          assert.equal(h.total(c, 'diamond', 54, 90), 16);
          await probe(s, `vcprobe rejoindrop ${name} verifyfull 16`, 'VC_REJOIN_DROP_verifyfull_OK');
          const confirmed = await probe(s, `vcprobe playerjournal ${name} recovered claim`, 'VC_PLAYER_JOURNAL_recovered=');
          assert(confirmed.includes('VC_PLAYER_JOURNAL_recovered=DONE|'));
          // A second restart must not grant the claim again.
          const logoutOffset = s.output.length;
          c.end('Verified claim'); await h.until(() => s.output.slice(logoutOffset).includes(name + ' left the game'), 'claim logout');
          await h.sessionClosed(s, name); await h.stop(s);
          s = await h.start(world, port, backend === 'mysql'); c = await h.connect(s, name); await h.open(c);
          assert.equal(h.total(c, 'diamond'), 0); assert.equal(h.total(c, 'diamond', 54, 90), 16);
          await probe(s, `vcprobe rejoindrop ${name} verifyfull 16`, 'VC_REJOIN_DROP_verifyfull_OK');
        }
        results.checks.push({ backend, partial, settled, stage, passed: true, held, before, planned, source: 16, remainder: partial ? 12 : 0,
          state: match[1], loginDenied: denied, ...(settled ? { resumedAfterTerminal: true, originalHeld: 64, witnessHeld: 12, total: 76 } : {}) });
        console.log('PASS claim cut', backend, kind, stage);
      } finally { if (c) c.end('Cleanup'); if (other) other.end('Cleanup'); if (s) await h.stop(s); }
    }
    assert.equal(results.checks.length, backends.length * 8);
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(c => c.end('Cleanup')); await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `player-claim-cuts-${stamp}.json`), JSON.stringify(results, null, 2));
  }
})();
