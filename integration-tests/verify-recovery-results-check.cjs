// Exercise the report gate without a database, server or filesystem writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const source = fs.readFileSync(path.join(__dirname, 'verify-recovery-results.cjs'), 'utf8');
const bytes = Buffer.from('candidate');
const hash = crypto.createHash('sha256').update(bytes).digest('hex');
const reports = {};
const checks = count => Array.from({ length: count }, (_, i) => ({ name: String(i), passed: true }));
const add = (file, data) => reports[file + '-a.json'] = { sha256: hash, ...data };
add('results', { checks: checks(24), capacity: { papi: '640000|10000|10000|0' } });
for (const [file, count] of [['maintenance-fixes', 6], ['item-fences', 3], ['folia-drop-restart-audit', 2],
  ['survival-audit', 2], ['player-session-exclusive', 5]]) add(file, { checks: checks(count) });
add('drop-restart-audit', { observations: [{ allowed: true, totalDiamond: 16 },
  { name: 'normal server restart', loginDenied: false }, { name: 'missing actual source', passed: true },
  { name: 'shutdown with an active login', passed: true }, ...checks(3)] });
add('login-abort-audit', { observations: [{ reconnectDenied: false }, ...checks(2)] });
add('cursor-death-audit', { backend: 'sqlite', checks: checks(1), observations: [{ stage: 'after clean restart', total: 16 }] });
add('bounded-soak-audit', { checks: [{ passed: true, rounds: 50, durationMs: 120000 }, ...checks(1)] });
add('resilience-results', { checks: checks(9), mode: 'focused-faults-and-sweep', observationCompleted: true,
  crashAcceptancePassed: true, finishedAt: 'done', crashBoundaries: [1, 2, 3, 4] });
reports['resilience-results-a.json'].checks[4].evidence = { actualSavedNbt: true, sameProcessLoginDenied: true,
  playerEmerald: 9, persistedEmerald: 0, priorDiamond: 16 };
add('platform-results-all', { checks: [['MySQL:', 6], ['Folia:', 7], ['Folia+MySQL:', 7]]
  .flatMap(([prefix, count]) => checks(count).map(c => ({ ...c, name: prefix + c.name }))), finishedAt: 'done' });
const claims = [], cuts = [];
for (const backend of ['sqlite', 'mysql']) {
  for (const [partial, settled, stages] of [[false, false, ['prepared', 'storage', 'receipt', 'confirmed']],
    [true, false, ['receipt', 'confirmed']], [true, true, ['receipt', 'confirmed']]])
    for (const stage of stages) claims.push({ backend, partial, settled, stage, passed: true,
      loginDenied: ['prepared', 'storage'].includes(stage) || partial && !settled,
      resumedAfterTerminal: settled, total: settled ? 76 : 16 });
  for (const direction of ['deposit', 'withdraw']) for (const stage of ['prepared', 'storage', 'receipt', 'confirmed'])
    cuts.push({ backend, direction, stage, passed: true, loginDenied: ['prepared', 'storage'].includes(stage) });
}
add('player-claim-cuts', { checks: claims });
add('player-cuts', { checks: cuts });
function run(candidate = 'candidate.jar') {
  vm.runInNewContext(source, { require(name) {
    if (name !== 'node:fs') return require(name);
    return { readdirSync: () => Object.keys(reports), readFileSync: file => file.endsWith('.jar')
      ? bytes : JSON.stringify(reports[path.basename(file)]) };
  }, process: { argv: ['node', 'verify', candidate] }, __dirname: 'fixtures', console: { log() {} } });
}
run();
assert.throws(() => run('async-db-candidate.jar'), /pending Join quit tickets: missing/);
add('join-quit-tickets', { checks: checks(1) });
assert.throws(() => run('async-db-candidate.jar'), /abandoned pickup and retired callbacks: missing/);
add('abandoned-pickup-audit', { checks: checks(2) });
assert.throws(() => run('async-db-candidate.jar'), /asynchronous MySQL lifecycle: missing/);
add('async-database-audit', { checks: [
  { passed: true, evidence: { tickPingMs: [50, 60, 55, 65, 50], clickToConfirmationMs: 4000 } },
  { passed: true, evidence: { reconnect: true, groundBeforeRespawn: 7 } },
  { passed: true, evidence: { reconnect: true } },
  { passed: true, evidence: { state: 'QUARANTINED', oldPlanApplied: false } },
  { passed: true, evidence: { playerEmerald: 9, playerGold: 3, playerDiamond: 16 } }
] });
assert.throws(run, /Folia asynchronous MySQL lifecycle: missing/);
add('folia-async-database-audit', structuredClone(reports['async-database-audit-a.json']));
assert.throws(run, /MySQL cursor death: missing/);
reports['cursor-death-audit-mysql.json'] = { ...reports['cursor-death-audit-a.json'], backend: 'mysql' };
run();
const sqliteDeath = reports['cursor-death-audit-a.json'];
delete reports['cursor-death-audit-a.json'];
assert.throws(run, /SQLite cursor death: missing/);
reports['cursor-death-audit-a.json'] = sqliteDeath;
reports['resilience-results-a.json'].checks[4].evidence.actualSavedNbt = false;
assert.throws(run, /saved inventory and isolation evidence/);
reports['resilience-results-a.json'].checks[4].evidence.actualSavedNbt = true;
reports['folia-async-database-audit-a.json'].sha256 = 'wrong';
assert.throws(run, /Folia asynchronous MySQL lifecycle: missing/);
reports['folia-async-database-audit-a.json'].sha256 = hash;
reports['async-database-audit-a.json'].checks[0].evidence.tickPingMs[0] = 2000;
assert.throws(run, /asynchronous MySQL lifecycle: missing/);
delete reports['async-database-audit-a.json'];
delete reports['folia-async-database-audit-a.json'];
const valid = reports['maintenance-fixes-a.json'];
for (const bad of [{ ...valid, error: 'failed' }, { ...valid, fatal: 'failed' }, { ...valid, sha256: 'wrong' },
  { ...valid, checks: checks(5) }, { ...valid, checks: checks(6).map((c, i) => i ? c : { ...c, passed: false }) }]) {
  reports['maintenance-fixes-a.json'] = bad;
  assert.throws(run, /maintenance: missing/);
}
reports['maintenance-fixes-a.json'] = valid;
const original = claims[1];
claims[1] = claims[0];
assert.throws(run, /claim cuts: missing/);
claims[1] = original;
claims[0].loginDenied = false;
assert.throws(run, /claim cuts: missing/);
claims[0].loginDenied = true;
run();
console.log('Report gate check: valid matrix passes; failed, incomplete, wrong-hash and duplicate reports are rejected');
