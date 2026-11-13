// Shared-MySQL same-player login must not admit two independently mutable inventories.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const h = require('./platforms.cjs');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const stamp = new Date().toISOString().replace(/[:.]/g, '-');
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
async function expectDenied(s, name) {
  try { await h.connect(s, name); assert.fail('Unexpected second login: ' + name); }
  catch (error) { assert(/VoidClear|会话|交易/.test(error.message), error.message); }
}
async function logout(s, client) {
  await h.settled(s, client);
  const offset = s.output.length;
  client.end('Verified logout');
  await h.until(() => s.output.slice(offset).includes(client.username + ' left the game'), 'logout');
  await h.sessionClosed(s, client.username);
}
(async () => {
  try {
    let a = await h.start('resilience-session-a', 25653, true);
    const b = await h.start('resilience-session-b', 25654, true);
    const one = await h.connect(a, 'SessionSame'); await h.open(one);
    let denied = false, two;
    try { two = await h.connect(b, 'SessionSame'); }
    catch (error) { denied = true; assert(/VoidClear|会话|交易/.test(error.message), error.message); }
    if (!denied) {
      await h.open(two);
      results.observed = { uuidA: one.uuid, uuidB: two.uuid, menuOpenA: !!one.window, menuOpenB: !!two.window };
      assert.equal(one.uuid, two.uuid, 'Fixture did not use the same offline identity');
    }
    assert(denied, 'Same UUID was admitted concurrently on two shared-database servers');
    results.checks.push({ name: 'shared database player session exclusive', passed: true });
    console.log('PASS session exclusive');
    await expectDenied(a, 'SessionSame');
    assert(one.window, 'Local duplicate destroyed first menu');
    results.checks.push({ name: 'same-process duplicate denied without replacing live owner', passed: true });
    await logout(a, one);
    const rejoin = await h.connect(a, 'SessionSame'); await h.open(rejoin);
    await logout(a, rejoin);
    const handoff = await h.connect(b, 'SessionSame'); await h.open(handoff);
    await logout(b, handoff);
    results.checks.push({ name: 'verified normal quit allows local rejoin and foreign handoff', passed: true });

    const source = await h.connect(a, 'SessionItems'); await h.open(source);
    await h.deposit(source, 'diamond', 16);
    await h.until(() => h.total(source, 'diamond') === 16, 'durable handoff deposit');
    assert.equal(h.total(source, 'diamond', 54, 90), 0);
    const playerFile = source.uuid + '.dat';
    await logout(a, source);
    // Deployment fixture: transfer the verified saved player file, never invent an entitlement from a journal.
    fs.copyFileSync(path.join(a.dir, 'world/playerdata', playerFile),
      path.join(b.dir, 'world/playerdata', playerFile), fs.constants.COPYFILE_EXCL);
    const destination = await h.connect(b, 'SessionItems'); await h.open(destination);
    assert.equal(h.total(destination, 'diamond'), 16);
    assert.equal(h.total(destination, 'diamond', 54, 90), 0);
    h.sendClick(destination, 0);
    await h.until(() => h.total(destination, 'diamond', 54, 90) === 16, 'handoff withdrawal');
    assert.equal(h.total(destination, 'diamond'), 0);
    await logout(b, destination);
    results.checks.push({ name: 'verified file handoff preserves actual deposit/withdraw quantity', passed: true, bin: 0, player: 16 });

    const crash = await h.connect(a, 'SessionCrash'); await h.open(crash);
    const identityFile = path.join(a.dir, 'plugins/VoidClear/server-owner.id');
    a.child.kill('SIGKILL'); await a.done; crash.end('Crash');
    const identity = fs.readFileSync(identityFile, 'utf8');
    await expectDenied(b, 'SessionCrash');
    a = await h.start('resilience-session-a', 25653, true);
    const resumed = await h.connect(a, 'SessionCrash'); await h.open(resumed);
    await expectDenied(b, 'SessionCrash');
    await logout(a, resumed);
    const afterCrash = await h.connect(b, 'SessionCrash'); await h.open(afterCrash);
    await logout(b, afterCrash);
    await h.stop(a);
    assert.equal(fs.readFileSync(identityFile, 'utf8'), identity);
    results.checks.push({ name: 'crash keeps foreign fence; same installation restart resumes before verified handoff', passed: true });
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(c => c.end('Cleanup')); await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `player-session-exclusive-${stamp}.json`), JSON.stringify(results, null, 2));
  }
})();
