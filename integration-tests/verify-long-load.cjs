// Read-only gate: a short smoke, partial report or different package cannot prove a long run.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const crypto = require('node:crypto');
const items = ['diamond', 'emerald', 'iron_ingot', 'gold_ingot'];
function validate(report, sha256) {
  assert.equal(report.sha256, sha256, 'Candidate hash mismatch');
  assert(!report.error && !report.fatal && report.finishedAt && report.cleanupFinishedAt, 'Run did not finish cleanly');
  assert(!report.serverFailures?.length, 'Server log failures recorded');
  assert(report.requestedSeconds >= 1800 && report.activeLoadMs >= report.requestedSeconds * 1000, 'At least 30 minutes of load required');
  assert.deepEqual(report.expected, Object.fromEntries(items.map(item => [item, 256])));
  assert.equal(report.checks?.length, 4, 'All stock, load, handoff and restart checks required');
  assert(report.checks.every(check => check.passed === true), 'A load check failed');
  assert.deepEqual(report.phases?.map(phase => phase.players), [4, 8, 16], 'All client ramps required');
  assert(report.phases.every(phase => phase.rounds > 0 && phase.durationMs > 0));
  assert.equal(report.nativeAutosaves?.length, 2, 'Both native saves required');
  assert(new Set(report.nativeAutosaves.map(save => save.server)).size === 2);
  assert(report.nativeAutosaves.every(save => save.finalModifiedMs > save.initialModifiedMs), 'Native world save not observed');
  assert(report.handoffs?.length >= 1 && report.handoffs.every(handoff => handoff.source !== handoff.destination
    && handoff.duplicateLoginDenied === true && handoff.copiedBytesMatch === true
    && /^[a-f0-9]{64}$/.test(handoff.sha256) && handoff.receipt && handoff.logout), 'Verified cross-server handoff required');
  assert.equal(report.savedPlayers?.length, 16, 'All actual saved players required');
  assert.equal(new Set(report.savedPlayers.map(player => player.uuid)).size, 16);
  assert(report.savedPlayers.every(player => /^[a-f0-9]{64}$/.test(player.sha256) && player.logout
    && items.every(item => Number.isInteger(player.counts?.[item]) && player.counts[item] >= 0)));
  assert(report.samples?.length >= 2 && report.samples.every(sample => sample.servers?.length === 2
    && sample.servers.every(server => Number.isFinite(server.responseMs) && server.responseMs >= 0
      && server.tps?.length === 3 && server.tps.every(Number.isFinite)
      && Number.isFinite(server.averageTickMs) && Number.isFinite(server.heapUsed)
      && Number.isFinite(server.gcMillis))), 'Both servers need resource samples');
  assert(report.batches?.length > 0 && report.batches.every(batch => [4, 8, 16].includes(batch.players)
    && typeof batch.withdraw === 'boolean' && Number.isInteger(batch.attempts) && Number.isInteger(batch.changedInventories)
    && batch.players >= batch.attempts && batch.attempts >= batch.changedInventories
    && batch.changedInventories >= 0 && Number.isFinite(batch.elapsedMs) && batch.elapsedMs >= 0));
  for (const phase of report.phases) for (const withdraw of [true, false]) {
    assert(report.batches.some(batch => batch.players === phase.players && batch.withdraw === withdraw
      && batch.changedInventories > 0), 'Every client ramp must make actual withdrawal and deposit progress');
  }
  const elapsed = report.batches.map(batch => batch.elapsedMs).sort((a, b) => a - b);
  assert.equal(report.batchP95Ms, elapsed[Math.ceil(elapsed.length * .95) - 1], 'Batch P95 must match raw records');
}
if (process.argv.includes('--self-check')) {
  const sha256 = 'a'.repeat(64), counts = Object.fromEntries(items.map(item => [item, 0]));
  const report = { sha256, requestedSeconds: 1800, activeLoadMs: 1800000, finishedAt: 'done', cleanupFinishedAt: 'done',
    expected: Object.fromEntries(items.map(item => [item, 256])), checks: Array.from({ length: 4 }, () => ({ passed: true })),
    phases: [4, 8, 16].map(players => ({ players, rounds: 1, durationMs: 600000 })),
    nativeAutosaves: ['a', 'b'].map(server => ({ server, initialModifiedMs: 1, finalModifiedMs: 2 })),
    handoffs: [{ source: 'a', destination: 'b', duplicateLoginDenied: true, copiedBytesMatch: true, sha256, receipt: 'r', logout: 'l' }],
    savedPlayers: Array.from({ length: 16 }, (_, uuid) => ({ uuid, sha256, logout: 'l', counts })),
    samples: Array.from({ length: 2 }, () => ({ servers: ['a', 'b'].map(server => ({ server, responseMs: 50, tps: [20, 20, 20], averageTickMs: 2, heapUsed: 1, gcMillis: 1 })) })),
    batches: [4, 8, 16].flatMap(players => [true, false].map(withdraw => ({ players, withdraw,
      attempts: players, changedInventories: 1, elapsedMs: 500 }))), batchP95Ms: 500 };
  validate(report, sha256);
  for (const change of [r => { r.sha256 = 'b'.repeat(64); }, r => { r.requestedSeconds = 60; },
    r => { r.error = 'failure'; }, r => { delete r.cleanupFinishedAt; }, r => { r.checks[3].passed = false; },
    r => { r.phases.pop(); }, r => { r.nativeAutosaves[1].finalModifiedMs = 1; },
    r => { r.handoffs[0].copiedBytesMatch = false; }, r => { r.savedPlayers.pop(); }, r => { r.samples[0].servers.pop(); },
    r => { r.batches = r.batches.filter(batch => batch.players !== 8); },
    r => { r.batches.filter(batch => batch.players === 16 && !batch.withdraw).forEach(batch => { batch.changedInventories = 0; }); },
    r => { r.batches[0].changedInventories = 5; }, r => { r.batchP95Ms = 501; },
    r => { r.serverFailures = [{ server: 'b', line: "Can't keep up!" }]; }]) {
    const broken = structuredClone(report); change(broken); assert.throws(() => validate(broken, sha256));
  }
  console.log('Long-load gate self-check passed: short, failed, incomplete and wrong-hash runs rejected');
} else {
  assert(process.argv[2] && process.argv[3], 'Usage: node integration-tests/verify-long-load.cjs <candidate.jar> <report.json>');
  const sha256 = crypto.createHash('sha256').update(fs.readFileSync(process.argv[2])).digest('hex');
  const report = JSON.parse(fs.readFileSync(process.argv[3], 'utf8')); validate(report, sha256);
  console.log(JSON.stringify({ sha256, activeLoadMs: report.activeLoadMs, phases: report.phases,
    handoffs: report.handoffs.length, batchP95Ms: report.batchP95Ms, status: 'specified two-server long-load checks complete' }));
}
