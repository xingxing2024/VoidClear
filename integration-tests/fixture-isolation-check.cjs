const assert = require('node:assert/strict');
const { tablePrefix } = require('./platforms.cjs');
const cases = ['resilience-faults', 'resilience-menu-faults', 'resilience-soak-mysql',
  ...['deposit', 'withdraw'].flatMap(direction => ['saved', 'unsaved'].map(stage => `resilience-crash-mysql-${direction}-${stage}`)),
  'resilience-sweep-mysql-saved', 'resilience-sweep-mysql-unsaved', 'resilience-cut-mysql-deposit-prepared'];
assert.equal(new Set(cases.map(tablePrefix)).size, cases.length, 'Independent worlds share durable sessions');
for (const name of cases) assert.equal(tablePrefix(name), tablePrefix(name), 'Restart changes database namespace');
assert.equal(tablePrefix('resilience-session-a'), tablePrefix('resilience-session-b'), 'Session exclusivity fixture no longer shares SQL');
assert.equal(tablePrefix('paper-a'), tablePrefix('paper-b'), 'Cross-server fixture no longer shares SQL');
assert.notEqual(tablePrefix('resilience-session-a'), tablePrefix('resilience-sweep-mysql-saved'));
console.log('Fixture isolation: ALL OK');
