const fs = require('node:fs');
const assert = require('node:assert/strict');
// Feed `jfr print --json --stack-depth 64 --events jdk.FileWrite <recording>` output.
const events = JSON.parse(fs.readFileSync(process.argv[2], 'utf8').replace(/^\uFEFF/, '')).recording.events;
const writes = events.filter(event => event.type === 'jdk.FileWrite' && /[\\/]playerdata[\\/]/.test(event.values.path || ''));
const redundant = writes.filter(event => {
  const frames = event.values.stackTrace?.frames || [];
  return frames.some(frame => /CraftPlayer$/.test(frame.method.type.name) && frame.method.name === 'saveData') &&
    frames.some(frame => /PlayerTransfers$/.test(frame.method.type.name) && ['quit', 'quitAsync'].includes(frame.method.name)) &&
    !frames.some(frame => /PlayerTransfers$/.test(frame.method.type.name) && frame.method.name === 'saveReceipt');
});
const nativeQuitWrites = writes.filter(event => {
  const frames = event.values.stackTrace?.frames || [];
  return frames.some(frame => /PlayerList$/.test(frame.method.type.name) && frame.method.name === 'remove') &&
    !frames.some(frame => /CraftPlayer$/.test(frame.method.type.name) && frame.method.name === 'saveData');
}).length;
assert(nativeQuitWrites > 0, 'Recording did not exercise native quit saves');
console.log(JSON.stringify({ playerDataWrites: writes.length, nativeQuitWrites, redundantQuitWrites: redundant.length }));
assert.equal(redundant.length, 0, 'Quit listener performed redundant synchronous player saves');
