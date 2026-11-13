// Test-only completion barrier: presentation text and chat delivery are not durable completion evidence.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
function latest(directory) {
  const file = path.join(directory, 'plugins/VoidClear/history.log');
  if (!fs.existsSync(file)) return null;
  return fs.readFileSync(file, 'utf8').split(/\r?\n/).findLast(line => line.includes(' SWEEP ')) || null;
}
async function sweep(server, until) {
  const before = latest(server.dir);
  server.cmd('vc sweep');
  let completed;
  await until(() => { completed = latest(server.dir); return completed !== null && completed !== before; },
    'new durable sweep completion', 60000);
  assert.match(completed, /(?:^|\s)failed_tasks=0(?:\s|$)/, 'Sweep did not finish without failed tasks');
  return completed;
}
module.exports = { sweep };
