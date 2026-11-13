// Real survival-mode menu, native managed pickup, death drops, and clean rejoin.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const h = require('./platforms.cjs');
const { sweep } = require('./sweep-completion.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
async function probe(s, command, marker) {
  const offset = s.output.length;
  s.cmd(command);
  await h.until(() => s.output.slice(offset).includes(marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), command);
  const output = s.output.slice(offset);
  assert(!output.includes('VC_PROBE_FAILED'), output);
  return output;
}
async function ground(s, token) {
  const output = await probe(s, `vcprobe ground count SurvivalQA ${token}`, `VC_GROUND_${token}=`);
  return Number(output.match(new RegExp(`VC_GROUND_${token}=(\\d+)`))[1]);
}
(async () => {
  let s;
  try {
    s = await h.start('resilience-survival', 25655, false);
    let c = await h.connect(s, 'SurvivalQA');
    c.write('player_loaded', {});
    s.cmd('gamemode survival SurvivalQA');
    await h.open(c);
    await h.deposit(c, 'diamond', 16);
    s.cmd('give SurvivalQA stone 2304');
    await h.until(() => h.total(c, 'stone', 54, 90) === 2304, 'full survival storage');
    h.sendClick(c, 0, 4, 1);
    await h.until(() => h.total(c, 'diamond') === 0, 'survival drop debited');
    s.cmd('execute as @e[type=item] run data merge entity @s {PickupDelay:32767s,NoGravity:1b}');
    c.write('close_window', { windowId: c.window.id });
    await probe(s, 'vcprobe rejoindrop SurvivalQA preparefull 0', 'VC_REJOIN_DROP_preparefull_OK');
    s.cmd('execute at SurvivalQA run tp @e[type=item] ~ ~ ~');
    s.cmd('execute as @e[type=item] run data merge entity @s {PickupDelay:0s}');
    await h.open(c);
    await h.until(() => h.total(c, 'diamond', 54, 90) === 16, 'native survival managed pickup');
    await probe(s, 'vcprobe rejoindrop SurvivalQA verifyfull 16', 'VC_REJOIN_DROP_verifyfull_OK');
    results.checks.push('survival managed drop native pickup preserves 16 diamonds and terminal receipt');
    s.cmd('give SurvivalQA quartz 7');
    await h.until(() => h.total(c, 'quartz', 54, 90) === 7, 'death sentinel');
    s.cmd('gamerule keepInventory false');
    c.write('close_window', { windowId: c.window.id });
    const offset = s.output.length;
    s.cmd('kill SurvivalQA');
    await h.until(() => s.output.slice(offset).includes('Killed SurvivalQA'), 'survival death');
    for (let attempt = 0; attempt < 20; attempt++) {
      s.cmd('data get entity SurvivalQA Health');
      await h.sleep(150);
      if (/SurvivalQA has the following entity data: 0\.0f/.test(s.output.slice(offset).replace(/\x1b\[[0-9;]*m/g, ''))) break;
    }
    assert(/SurvivalQA has the following entity data: 0\.0f/.test(s.output.slice(offset).replace(/\x1b\[[0-9;]*m/g, '')), 'Player never died');
    assert.equal(await ground(s, 'afterdeath'), 7);
    await sweep(s, h.until);
    assert.equal(await ground(s, 'aftersweep'), 0);
    c.end('Death logout');
    await h.until(() => s.output.slice(offset).includes('SurvivalQA left the game'), 'death logout');
    await h.sessionClosed(s, 'SurvivalQA');
    await h.stop(s);
    s = await h.start('resilience-survival', 25655, false);
    c = await h.connect(s, 'SurvivalQA');
    c.write('client_command', { actionId: 0 });
    await h.sleep(500);
    await h.open(c);
    assert.equal(h.total(c, 'quartz'), 7);
    assert.equal(h.total(c, 'diamond'), 16);
    assert.equal(h.total(c, 'quartz', 54, 90) + h.total(c, 'diamond', 54, 90), 0);
    results.checks.push('survival death, sweep, logout and clean restart conserve diamond/quartz sentinels');
    assert(!/Could not pass event|VC_PROBE_FAILED|玩家物品交易已隔离/.test(s.output), s.output.slice(-8000));
    console.log('PASS', results.checks);
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(c => c.end('Cleanup'));
    await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `survival-audit-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
