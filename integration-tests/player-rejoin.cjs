// Same-process logout/relogin must not turn an acknowledged world source into a permanent login ban.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const h = require('./platforms.cjs');
const port = Number(process.env.VOIDCLEAR_REJOIN_PORT || 25658);
assert([25655, 25658].includes(port), 'Only isolated rejoin ports are allowed');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
async function probe(s, name, stage, expected) {
  const offset = s.output.length;
  s.cmd(`vcprobe rejoindrop ${name} ${stage} ${expected}`);
  await h.until(() => /VC_REJOIN_DROP_\w+_OK|VC_PROBE_FAILED/.test(s.output.slice(offset)), stage, 15000);
  assert(!s.output.slice(offset).includes('VC_PROBE_FAILED'), s.output.slice(offset));
}
(async () => {
  try {
    for (const mode of ['live', 'restart', 'stale-file']) for (const partial of [false, true]) {
      let s, c;
      const name = partial ? 'RejoinPartial' : 'RejoinDrop';
      const caseName = name + '-' + mode;
      try {
        s = await h.start('resilience-rejoin-' + caseName, port, false);
        c = await h.connect(s, name); await h.open(c);
        await h.deposit(c, 'diamond', 16);
        await h.until(() => h.total(c, 'diamond') === 16, 'seed bin');
        const playerFile = path.join(s.dir, 'world/playerdata', c.uuid + '.dat');
        const oldPlayerData = fs.readFileSync(playerFile);
        // Prevent natural pickup before the console freezes the new entity.
        s.cmd(`give ${name} stone 2304`);
        await h.until(() => h.total(c, 'stone', 54, 90) === 2304, 'full storage before drop');
        h.sendClick(c, 0, 4, 1);
        await h.until(() => h.total(c, 'diamond') === 0, 'drop debit');
        // Freeze the actual menu-created source, not a replacement fixture.
        s.cmd('execute as @e[type=item] run data merge entity @s {PickupDelay:32767s,NoGravity:1b}');
        c.write('close_window', { windowId: c.window.id });
        await probe(s, name, partial ? 'partial' : 'inspect', partial ? 64 : 0);
        c.end('Normal logout');
        await h.until(() => s.output.includes(name + ' left the game'), 'normal logout');
        await h.sessionClosed(s, name);
        if (mode === 'restart') {
          await h.stop(s);
          s = await h.start('resilience-rejoin-' + caseName, port, false);
          let denied = false;
          try { c = await h.connect(s, name); }
          catch (error) { denied = true; assert(/VoidClear|未确认|交易/.test(error.message), error.message); }
          assert(denied, 'Restart incorrectly retained process-local rejoin permission');
          results.checks.push({ name: caseName, passed: true, loginDenied: true });
          console.log('PASS', caseName);
          continue;
        }
        if (mode === 'stale-file') {
          fs.writeFileSync(path.join(s.dir, 'player-before-stale-test.dat'), fs.readFileSync(playerFile));
          fs.writeFileSync(playerFile, oldPlayerData);
          let denied = false;
          const offset = s.output.length;
          try { c = await h.connect(s, name); }
          catch (error) { denied = true; assert(/VoidClear|未确认|交易/.test(error.message), error.message); }
          // A disk mismatch is rejected before join; still check the join-time fence if admission already reached Bukkit.
          if (!denied) {
            await h.until(() => s.output.slice(offset).includes('玩家存档与数据库收据不符'), 'stale-file join isolation');
            await h.until(() => s.output.slice(offset).includes(name + ' lost connection'), 'stale-file disconnect');
          }
          results.checks.push({ name: caseName, passed: true, staleFileIsolated: true });
          console.log('PASS', caseName);
          continue;
        }
        c = await h.connect(s, name);
        await probe(s, name, 'inspect', partial ? 64 : 0);
        await h.open(c); assert.equal(h.total(c, 'diamond'), 0);
        c.write('close_window', { windowId: c.window.id });
        await probe(s, name, 'consume', partial ? 76 : 16);
        results.checks.push({ name: caseName, passed: true, heldBeforeRejoin: partial ? 64 : 0, groundBeforeRejoin: partial ? 12 : 16, heldAfterConsume: partial ? 76 : 16 });
        console.log('PASS', caseName);
      } catch (error) { results.checks.push({ name: caseName, passed: false, error: error.stack }); console.error('FAIL', caseName, error.message); process.exitCode = 1; }
      finally { if (c) c.end('Cleanup'); if (s) await h.stop(s); }
    }
  } finally {
    h.clients.forEach(c => c.end('Cleanup')); await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `player-rejoin-${stamp}.json`), JSON.stringify(results, null, 2));
  }
})();
