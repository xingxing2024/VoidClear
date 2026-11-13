'use strict';
// Only controls the isolated localhost regression server.
const net = require('node:net');
const assert = require('node:assert/strict');
function packet(id, type, text) {
  const body = Buffer.from(text, 'utf8');
  const result = Buffer.alloc(body.length + 14);
  result.writeInt32LE(body.length + 10, 0);
  result.writeInt32LE(id, 4);
  result.writeInt32LE(type, 8);
  body.copy(result, 12);
  return result;
}
async function command(text, target = 'paper') {
    if (!['paper', 'folia', 'review'].includes(target)) throw new Error('Unknown isolated test target');
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: '127.0.0.1', port: target === 'review' ? 25642 : target === 'folia' ? 25590 : 25588 });
    let pending = Buffer.alloc(0);
    const timer = setTimeout(() => finish(new Error('RCON timeout')), 10000);
    function finish(error, value) {
      clearTimeout(timer);
      socket.destroy();
      if (error) reject(error); else resolve(value);
    }
    socket.on('error', finish);
    socket.on('end', () => finish(new Error('RCON ended before response')));
    socket.on('connect', () => socket.write(packet(1, 3, target === 'review' ? 'vc-review-local-only' : target === 'folia' ? 'sg-folia-91c7b4a2' : 'sg-bot-6d937f1a')));
    socket.on('data', chunk => {
      pending = Buffer.concat([pending, chunk]);
      while (pending.length >= 4) {
        const length = pending.readInt32LE(0);
        if (length < 10 || length > 1048576) return finish(new Error('Invalid RCON frame'));
        if (pending.length < length + 4) return;
        const frame = pending.subarray(0, length + 4);
        pending = pending.subarray(length + 4);
        const id = frame.readInt32LE(4);
        const type = frame.readInt32LE(8);
        if (id === -1) return finish(new Error('RCON authentication refused'));
        if (id === 1 && type === 2) socket.write(packet(2, 2, text));
        // shortcut: commands here have short responses; add multi-frame aggregation before large list queries.
        if (id === 2) return finish(null, frame.toString('utf8', 12, frame.length - 2));
      }
    });
  });
}
if (require.main === module) {
  if (process.argv[2] === '--self-test') {
    const p = packet(7, 2, '中文');
    assert.equal(p.readInt32LE(0), p.length - 4);
    assert.equal(p.readInt32LE(4), 7);
    assert.equal(p.toString('utf8', 12, p.length - 2), '中文');
    assert.equal(p.readUInt16LE(p.length - 2), 0);
    console.log('RCON packet PASS');
  } else if (!process.argv[2]) {
    console.error('Usage: node rcon.cjs <command>');
    process.exitCode = 1;
  } else command(process.argv.slice(2).join(' ')).then(console.log).catch(e => {
    console.error(e);
    process.exitCode = 1;
  });
}
module.exports = { command };
