// Copy only a saved player into a fresh isolated database; never delete production journal rows.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const results = { candidate, sha256: crypto.createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
(async () => {
  let s, c;
  try {
    const name = 'OrphanReceipt';
    s = await h.start('resilience-orphan-donor', 25655, false);
    c = await h.connect(s, name); await h.open(c); await h.deposit(c, 'diamond', 16);
    const uuid = c.uuid;
    c.end('Donor logout'); await h.until(() => s.output.includes(name + ' left the game'), 'donor quit');
    await h.sessionClosed(s, name); await h.stop(s);
    const saved = fs.readFileSync(path.join(s.dir, 'world/playerdata', uuid + '.dat'));
    s = await h.start('resilience-orphan-empty-db', 25655, false);
    const file = path.join(s.dir, 'world/playerdata', uuid + '.dat');
    fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, saved);
    for (let attempt = 0; attempt < 2; attempt++) {
      const offset = s.output.length;
      await assert.rejects(() => h.connect(s, name), /VoidClear|未确认|交易/);
      const output = s.output.slice(offset);
      assert(!output.includes(name + ' joined the game'));
      assert(output.includes('登录前玩家收据不一致') && output.includes('current=null'), output);
      assert.deepEqual(fs.readFileSync(file), saved, 'Rejected prelogin rewrote the saved player');
    }
    c = await h.connect(s, 'FreshWitness'); await h.open(c);
    assert.equal(h.total(c, 'diamond'), 0);
    await h.deposit(c, 'emerald', 9);
    assert.equal(h.total(c, 'emerald'), 9);
    results.checks.push({ name: 'orphan receipt rejected before join twice without changing player file; new player unaffected', passed: true });
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(client => client.end('Cleanup')); await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `player-orphan-receipt-${stamp}.json`), JSON.stringify(results, null, 2));
  }
})();
