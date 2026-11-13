const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { sweep } = require('./sweep-completion.cjs');
const root = fs.mkdtempSync(path.resolve(__dirname, '../target/sweep-completion-'));
const file = path.join(root, 'plugins/VoidClear/history.log');
fs.mkdirSync(path.dirname(file), { recursive: true });
async function until(condition, label) {
  for (let attempt = 0; attempt < 50; attempt++) { if (condition()) return; await new Promise(resolve => setTimeout(resolve, 5)); }
  throw new Error('Timeout: ' + label);
}
(async () => {
  fs.writeFileSync(file, 'old SWEEP removed=0 failed_tasks=0\n');
  let published = false, commands = 0;
  const server = { dir: root, cmd(command) {
    assert.equal(command, 'vc sweep'); commands++;
    setTimeout(() => { fs.appendFileSync(file, 'new SWEEP removed=16 collected_groups=1 failed_tasks=0\n'); published = true; }, 30);
  }};
  const line = await sweep(server, until);
  assert(published && commands === 1 && line.startsWith('new '), 'Old successful sweep satisfied the barrier');
  server.cmd = () => fs.appendFileSync(file, 'bad SWEEP removed=0 failed_tasks=1\n');
  await assert.rejects(sweep(server, until), /failed tasks/);
  server.cmd = () => fs.appendFileSync(file, 'missing-counter SWEEP removed=0\n');
  await assert.rejects(sweep(server, until), /failed tasks/);
  server.cmd = () => fs.appendFileSync(file, 'ordinary ADMIN log event\n');
  await assert.rejects(sweep(server, until), /Timeout/);
  console.log('Sweep completion barrier: old entries, failed/missing counters and unrelated rows rejected');
})().catch(error => { console.error(error); process.exitCode = 1; });
