const assert = require('node:assert/strict');
const path = require('node:path');
const req = require('node:module').createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'));
const diamond = req('minecraft-data')('1.21.8').itemsByName.diamond.id;
const h = require('./platforms.cjs');
const client = {
  window: { id: 1, state: 0, items: Array(90).fill(null) },
  chat() { this.window.items[54] = { itemId: diamond, itemCount: 16 }; },
  write() {
    this.window.items[0] = { itemId: diamond, itemCount: 20 };
    setTimeout(() => { this.window.items[54] = null; }, 600);
  },
};
client.window.items[0] = { itemId: diamond, itemCount: 4 };
(async () => {
  await h.deposit(client, 'diamond', 16);
  assert.equal(h.total(client, 'diamond'), 20);
  assert.equal(h.total(client, 'diamond', 54, 90), 0, 'Returned before both sides arrived');
  console.log('Deposit waits for both sides: ALL OK');
})().catch(error => { console.error(error); process.exitCode = 1; });
