// Read-only acceptance gate: only complete reports for the exact packaged bytes count.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
assert(process.argv[2], 'Usage: node integration-tests/verify-recovery-results.cjs <candidate.jar>');
const candidate = path.resolve(process.argv[2]);
const sha256 = crypto.createHash('sha256').update(fs.readFileSync(candidate)).digest('hex');
const reports = fs.readdirSync(__dirname).filter(file => file.endsWith('.json')).flatMap(file => {
  try {
    const data = JSON.parse(fs.readFileSync(path.join(__dirname, file), 'utf8'));
    return (data.sha256 || data.candidateSha256 || data.candidate?.sha256) === sha256 ? [{ file, data }] : [];
  } catch { return []; }
});
function requireReport(label, prefix, count, validate = () => {}) {
  const matches = reports.filter(({ file, data }) => file.startsWith(prefix) && !data.error && !data.fatal
    && (data.checks || data.observations)?.length === count
    && (data.checks || data.observations).every(check => typeof check === 'string'
      || (data.observations ? check.passed !== false : check.passed === true)));
  let accepted;
  for (const report of matches.reverse()) {
    try { validate(report.data); accepted = report; break; } catch { }
  }
  assert(accepted, `${label}: missing complete ${count}-entry report for ${sha256}`);
  const entries = accepted.data.checks || accepted.data.observations;
  const denied = entries.filter(check => check.loginDenied === true);
  const summary = { label, file: accepted.file, entries: count, expectedLoginDenials: denied.length };
  console.log(JSON.stringify(summary));
  return accepted.data;
}
requireReport('Paper/SQLite', 'results-', 24, r => assert(r.capacity?.papi === '640000|10000|10000|0'));
requireReport('maintenance', 'maintenance-fixes-', 6);
requireReport('item fences', 'item-fences-', 3);
requireReport('source lifecycle', 'drop-restart-audit-', 7, r => {
  assert(r.observations.some(c => c.totalDiamond === 16 && c.allowed === true));
  assert(r.observations.some(c => c.name?.includes('normal server restart') && c.loginDenied === false));
  assert(r.observations.some(c => c.name?.includes('missing actual source') && c.passed === true));
  assert(r.observations.some(c => c.name?.includes('shutdown with an active') && c.passed === true));
});
requireReport('early Paper connection', 'login-abort-audit-', 3, r => assert(r.observations[0].reconnectDenied === false));
requireReport('Folia source lifecycle', 'folia-drop-restart-audit-', 2);
requireReport('SQLite cursor death', 'cursor-death-audit-', 1, r => {
  assert.equal(r.backend, 'sqlite');
  assert(r.observations.some(c => c.stage === 'after clean restart' && c.total === 16));
});
requireReport('survival', 'survival-audit-', 2);
requireReport('bounded survival soak', 'bounded-soak-audit-', 2, r => assert(r.checks[0].rounds >= 50 && r.checks[0].durationMs >= 120000));
const faults = requireReport('MySQL faults and sweep kills', 'resilience-results-', 9, r => {
  assert(r.mode === 'focused-faults-and-sweep' && r.observationCompleted && r.crashAcceptancePassed && r.finishedAt);
  assert.equal(r.crashBoundaries.length, 4);
});
requireReport('exclusive sessions', 'player-session-exclusive-', 5);
for (const [label, prefix, directions, stages, expected] of [
  ['claim cuts', 'player-claim-cuts-', null, null, 16],
  ['player cuts', 'player-cuts-', ['deposit', 'withdraw'], ['prepared', 'storage', 'receipt', 'confirmed'], 16]
]) {
  requireReport(label, prefix, expected, r => {
    const identities = new Set(r.checks.map(c => JSON.stringify([c.backend, c.direction, c.partial, c.settled, c.stage])));
    assert.equal(identities.size, expected);
    for (const backend of ['sqlite', 'mysql']) {
      const checks = r.checks.filter(c => c.backend === backend);
      assert.equal(checks.length, 8);
      if (directions) for (const direction of directions) for (const stage of stages)
        assert(checks.some(c => c.direction === direction && c.stage === stage && c.loginDenied === ['prepared', 'storage'].includes(stage)));
      else {
        assert.equal(checks.filter(c => c.loginDenied).length, 4);
        assert.equal(checks.filter(c => c.settled && c.resumedAfterTerminal && c.total === 76).length, 2);
        for (const [partial, settled, stages] of [[false, false, ['prepared', 'storage', 'receipt', 'confirmed']],
          [true, false, ['receipt', 'confirmed']], [true, true, ['receipt', 'confirmed']]])
          for (const stage of stages) assert(checks.some(c => c.partial === partial && c.settled === settled
            && c.stage === stage && c.loginDenied === (['prepared', 'storage'].includes(stage) || partial && !settled)));
      }
    }
  });
}
requireReport('MySQL and Folia platforms', 'platform-results-all-', 20, r => {
  assert(r.finishedAt);
  for (const [prefix, count] of [['MySQL:', 6], ['Folia:', 7], ['Folia+MySQL:', 7]])
    assert.equal(r.checks.filter(c => c.name.startsWith(prefix)).length, count);
});
const performance = faults.checks.find(c => c.evidence?.clickToAckMs)?.evidence;
const asynchronous = path.basename(candidate).includes('async-db-') || reports.some(({ file }) => file.startsWith('async-database-audit-'));
if (asynchronous) {
requireReport('pending Join quit tickets', 'join-quit-tickets-', 1);
requireReport('abandoned pickup and retired callbacks', 'abandoned-pickup-audit-', 2);
assert(faults.checks.some(c => c.evidence?.actualSavedNbt === true && c.evidence.sameProcessLoginDenied === true
  && c.evidence.playerEmerald === 9 && c.evidence.persistedEmerald === 0 && c.evidence.priorDiamond === 16),
  'Asynchronous menu refusal requires saved inventory and isolation evidence');
for (const [label, prefix] of [['asynchronous MySQL lifecycle', 'async-database-audit-'],
  ['Folia asynchronous MySQL lifecycle', 'folia-async-database-audit-']]) requireReport(label, prefix, 5, r => {
  const delay = r.checks.find(c => c.evidence?.tickPingMs)?.evidence;
  assert(delay?.tickPingMs.length >= 5 && delay.tickPingMs.every(ms => Number.isFinite(ms) && ms >= 0)
    && Math.max(...delay.tickPingMs) < 700 && delay.clickToConfirmationMs >= 60);
  assert(r.checks.some(c => c.evidence?.state === 'QUARANTINED' && c.evidence?.oldPlanApplied === false));
  assert(r.checks.some(c => c.evidence?.playerEmerald === 9 && c.evidence?.playerGold === 3 && c.evidence?.playerDiamond === 16));
  assert(r.checks.some(c => c.evidence?.groundBeforeRespawn === 7 && c.evidence?.reconnect === true));
  assert(r.checks.filter(c => c.evidence?.reconnect === true).length >= 2);
});
requireReport('MySQL cursor death', 'cursor-death-audit-', 1, r => {
  assert.equal(r.backend, 'mysql');
  assert(r.observations.some(c => c.stage === 'after clean restart' && c.total === 16));
});
}
console.log(JSON.stringify({ candidate, sha256, clickToAckMs: performance?.clickToAckMs,
  synchronousJdbcRemains: !asynchronous, playerSaveDataStillSynchronous: true,
  status: 'specified regression matrix complete; not a production release' }));
