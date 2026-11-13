// Reject old/corrupt player files even when a real receipt cut was reached before the crash.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
async function probe(s, command, marker) {
  const offset = s.output.length; s.cmd(command);
  await h.until(() => {
    const output = s.output.slice(offset), start = output.indexOf(marker);
    return start >= 0 && output.indexOf('\n', start) >= 0 || output.includes('VC_PROBE_FAILED');
  }, command, 30000);
  const output = s.output.slice(offset); assert(!output.includes('VC_PROBE_FAILED'), output); return output;
}
(async () => {
  try {
    for (const fault of ['old-file', 'bad-crc']) {
      let s, c, other;
      const name = fault === 'old-file' ? 'OldReceipt' : 'BadReceipt';
      const world = 'resilience-recovery-negative-' + fault;
      try {
        s = await h.start(world, 25655, false); c = await h.connect(s, name); await h.open(c);
        s.cmd(`give ${name} diamond 16`);
        await h.until(() => h.total(c, 'diamond', 54, 90) === 16, 'player baseline');
        await probe(s, `vcprobe checkpoint ${name} baseline`, 'VC_CHECKPOINT_baseline');
        const playerFile = path.join(s.dir, 'world/playerdata', c.uuid + '.dat');
        const baseline = fs.readFileSync(playerFile);
        s.cmd('save-off');
        await probe(s, 'vcprobe playercut receipt', 'VC_PLAYER_CUT_ARMED_receipt');
        const slot = c.window.items.findIndex((item, i) => i >= 54 && item?.itemCount === 16);
        assert(slot >= 54); h.sendClick(c, slot);
        await h.until(() => /VC_PLAYER_CUT_receipt(?:\r?\n)/.test(s.output), 'receipt cut');
        s.child.kill('SIGKILL'); await s.done; c.end('Crash');
        const saved = fs.readFileSync(playerFile);
        fs.writeFileSync(path.join(s.dir, 'player-before-negative-test.dat'), saved);
        if (fault === 'old-file') fs.writeFileSync(playerFile, baseline);
        else { const corrupt = Buffer.from(saved); corrupt[corrupt.length - 8] ^= 1; fs.writeFileSync(playerFile, corrupt); }
        s = await h.start(world, 25655, false);
        let denied = false;
        try { c = await h.connect(s, name); }
        catch (error) { denied = true; assert(/VoidClear|未确认|交易/.test(error.message), error.message); }
        assert(denied, 'Unproven receipt was allowed to recover');
        let retryDenied = false;
        try { c = await h.connect(s, name); }
        catch (error) { retryDenied = true; assert(/VoidClear|未确认|交易/.test(error.message), error.message); }
        assert(retryDenied, 'Retry bypassed the invalid receipt fence');
        const journal = await probe(s, `vcprobe playerjournal ${name} negative`, 'VC_PLAYER_JOURNAL_negative=');
        assert(journal.includes('VC_PLAYER_JOURNAL_negative=PREPARED|'), 'Unproven checkpoint advanced');
        other = await h.connect(s, 'FenceWitness'); await h.open(other);
        assert.equal(h.total(other, 'diamond'), 16);
        h.sendClick(other, 0);
        const token = 'fence_' + fault;
        other.chat('/vcprobe clientsync ' + token);
        await h.until(() => other.chats.some(line => line.includes('VC_CLIENT_SYNC_' + token)), 'owner-thread fence check');
        await h.sleep(500);
        assert.equal(h.total(other, 'diamond'), 16, 'Unproven deposit circulated');
        assert.equal(h.total(other, 'diamond', 54, 90), 0, 'Witness received unproven deposit');
        results.checks.push({ fault, passed: true, loginDenied: true, retryDenied, state: 'PREPARED', bin: 16, witness: 0 });
        console.log('PASS recovery negative', fault);
      } finally { if (c) c.end('Cleanup'); if (other) other.end('Cleanup'); if (s) await h.stop(s); }
    }
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(c => c.end('Cleanup')); await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `player-recovery-negative-${stamp}.json`), JSON.stringify(results, null, 2));
  }
})();
