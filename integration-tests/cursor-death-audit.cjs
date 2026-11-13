// Death while holding a bin withdrawal on the cursor: real protocol, no injected delivery failures.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const h = require('./platforms.cjs');
const { sweep } = require('./sweep-completion.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const mysql = process.env.VOIDCLEAR_AUDIT_BACKEND === 'mysql';
const results = { candidate, backend: mysql ? 'mysql' : 'sqlite', sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [], observations: [] };
async function probe(s, command, marker) {
  const offset = s.output.length; s.cmd(command);
  await h.until(() => s.output.slice(offset).includes(marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), command);
  const output = s.output.slice(offset);
  assert(!output.includes('VC_PROBE_FAILED'), output);
  return output;
}
async function settled(s, c) {
  if (!mysql) return;
  for (let attempt = 0; attempt < 100; attempt++) {
    const output = await probe(s, `vcprobe dbpending ${c.username}`, 'VC_DB_PENDING=');
    if (output.includes('VC_DB_PENDING=false')) return;
    await h.sleep(100);
  }
  assert.fail('Death fixture transaction did not settle');
}
(async () => {
  try {
    const s = await h.start('resilience-cursor-death', 25655, mysql);
    const c = await h.connect(s, 'CursorDeathQA');
    c.write('player_loaded', {});
    s.cmd('gamemode survival CursorDeathQA');
    await h.open(c);
    await h.deposit(c, 'quartz', 16);
    await settled(s, c);
    h.sendClick(c, 0, 0);
    await h.until(() => c.window.cursor?.itemCount === 16 && h.total(c, 'quartz') === 0, 'cursor withdrawal');
    await settled(s, c);
    const offset = s.output.length;
    s.cmd('kill CursorDeathQA');
    await h.until(() => s.output.slice(offset).includes('Killed CursorDeathQA'), 'death command');
    await h.until(() => c.health === 0, 'actual death health');
    await h.sleep(1500);
    await settled(s, c);
    const ground = await probe(s, 'vcprobe ground count CursorDeathQA death', 'VC_GROUND_death=');
    results.observations.push({ stage: 'after death before respawn', ground: Number(ground.match(/VC_GROUND_death=(\d+)/)[1]),
      cursor: c.window?.cursor?.itemCount || 0, windowOpen: !!c.window });
    assert.equal(results.observations.at(-1).ground, 16, 'Death must preserve the cursor as a managed source');
    assert(!s.output.slice(offset).includes('玩家物品交易已隔离'), s.output.slice(offset));
    c.write('client_command', { actionId: 0 });
    await h.sleep(500);
    await h.open(c);
    results.observations.push({ stage: 'after respawn', bin: h.total(c, 'quartz'), player: h.total(c, 'quartz', 54, 90),
      cursor: c.window.cursor?.itemCount || 0 });
    const journal = await probe(s, 'vcprobe playerjournal CursorDeathQA afterdeath', 'VC_PLAYER_JOURNAL_afterdeath=');
    results.observations.push({ stage: 'journal after death', state: journal.match(/VC_PLAYER_JOURNAL_afterdeath=(\w+)/)?.[1] });
    assert(['DROPS', 'DONE'].includes(results.observations.at(-1).state), 'Death or native pickup must have a confirmed journal');
    await sweep(s, h.until);
    c.end('Death cursor logout');
    await h.until(() => s.output.slice(offset).includes('CursorDeathQA left the game'), 'logout');
    await h.sessionClosed(s, 'CursorDeathQA');
    await h.stop(s);
    const restarted = await h.start('resilience-cursor-death', 25655, mysql);
    const rejoined = await h.connect(restarted, 'CursorDeathQA');
    await h.open(rejoined);
    const finalGround = await probe(restarted, 'vcprobe ground count CursorDeathQA final', 'VC_GROUND_final=');
    const final = { stage: 'after clean restart', bin: h.total(rejoined, 'quartz'), player: h.total(rejoined, 'quartz', 54, 90),
      ground: Number(finalGround.match(/VC_GROUND_final=(\d+)/)[1]) };
    final.total = final.bin + final.player + final.ground;
    results.observations.push(final);
    results.checks.push({ name: 'death with bin withdrawal on cursor conserves 16 quartz', passed: final.total === 16 });
    if (final.total !== 16) process.exitCode = 1;
    console.log('OBSERVED', results);
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(c => c.end('Cleanup'));
    await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `cursor-death-audit-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
