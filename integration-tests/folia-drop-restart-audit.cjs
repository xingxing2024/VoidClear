// Protocol-only Folia lifecycle check; never accesses player/world state from a console probe.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
(async () => {
  let s;
  try {
    s = await h.start('folia-source-restart', 25652, false, true);
    let c = await h.connect(s, 'FoliaSourceQA');
    c.write('player_loaded', {});
    c.chat('/tp @s 1000 -60 1000');
    await h.until(() => c.pos?.x > 999 && c.pos?.z > 999, 'distant Folia source');
    await h.open(c);
    await h.deposit(c, 'diamond', 16);
    c.chat('/give @s stone 2304');
    await h.until(() => h.total(c, 'stone', 54, 90) === 2304, 'full Folia inventory');
    h.sendClick(c, 0, 4, 1);
    await h.until(() => h.total(c, 'diamond') === 0, 'Folia drop debit');
    c.write('close_window', { windowId: c.window.id });
    c.end('Clean source restart');
    await h.until(() => s.output.includes('FoliaSourceQA left the game'), 'Folia logout');
    await h.stop(s);
    assert(!/停用清理失败|Could not pass event|Thread failed|not owned by current region/.test(s.output), s.output.slice(-9000));
    s = await h.start('folia-source-restart', 25652, false, true);
    c = await h.connect(s, 'FoliaSourceQA');
    c.write('player_loaded', {});
    await h.open(c);
    assert.equal(h.total(c, 'diamond') + h.total(c, 'diamond', 54, 90), 0);
    results.checks.push({ name: 'Folia distant persisted source permits owner rejoin after clean restart', passed: true });
    c.chat('/clear @s stone');
    await h.until(() => h.total(c, 'diamond', 54, 90) === 16, 'native Folia source pickup', 30000);
    assert.equal(h.total(c, 'diamond'), 0);
    c.end('Final restart');
    await h.until(() => s.output.includes('FoliaSourceQA left the game'), 'Folia pickup logout');
    await h.stop(s);
    assert(!/停用清理失败|Could not pass event|Thread failed|not owned by current region/.test(s.output), s.output.slice(-9000));
    s = await h.start('folia-source-restart', 25652, false, true);
    c = await h.connect(s, 'FoliaSourceQA');
    await h.open(c);
    assert.equal(h.total(c, 'diamond', 54, 90), 16);
    assert.equal(h.total(c, 'diamond'), 0);
    results.checks.push({ name: 'Folia native source pickup and second restart conserve 16 diamonds', passed: true });
    c.end('Cleanup');
    await h.stop(s);
    assert(!/停用清理失败|Could not pass event|Thread failed|not owned by current region/.test(s.output), s.output.slice(-9000));
    console.log('PASS', results.checks);
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(c => c.end('Cleanup'));
    await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `folia-drop-restart-audit-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
