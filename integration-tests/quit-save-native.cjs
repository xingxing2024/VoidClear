// Actual quit files, receipt renewal and reconnect on both thread models; no player files are fabricated.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const req = createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'));
const nbt = req('prismarine-nbt');
const h = require('./platforms.cjs');
const namespace = process.env.VOIDCLEAR_TEST_NAMESPACE;
assert(namespace && /^[a-z0-9_]{1,24}$/.test(namespace), 'Fresh isolated namespace required');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
const report = path.join(__dirname, `quit-save-native-${namespace}.json`);
assert(!fs.existsSync(report), 'Preserve existing result');
async function saved(file) { return nbt.simplify((await nbt.parse(fs.readFileSync(file))).parsed); }
function count(value, item) { return (value.Inventory || []).filter(stack => stack.id === 'minecraft:' + item).reduce((n, stack) => n + Number(stack.count ?? stack.Count ?? 0), 0); }
async function quit(s, client) {
  await h.settled(s, client);
  const offset = s.output.length;
  client.end('Native save audit');
  await h.until(() => s.output.slice(offset).includes(client.username + ' left the game'), 'native quit');
  await h.sessionClosed(s, client.username);
}
(async () => {
  try {
    for (const folia of [false, true]) {
      const mode = folia ? 'folia' : 'paper';
      let s, client;
      try {
        s = await h.start('resilience-quit-native-' + mode, 25655, false, folia, 33317, { probe: true });
        client = await h.connect(s, 'NativeQuit'); await h.open(client);
        await h.deposit(client, 'diamond', 16); await h.settled(s, client);
        const file = path.join(s.dir, 'world/playerdata', client.uuid + '.dat');
        const before = await saved(file);
        assert.equal(count(before, 'emerald'), 0);
        client.chat('/give @s emerald 11');
        await h.until(() => h.total(client, 'emerald', 54, 90) === 11, 'unsaved inventory change');
        await quit(s, client);
        const first = await saved(file);
        const token = first.BukkitValues?.['voidclear:session_logout'];
        assert.match(token, /^[0-9a-f-]{36}$/);
        assert.notEqual(token, before.BukkitValues?.['voidclear:session_logout']);
        assert.equal(count(first, 'emerald'), 11);
        assert.equal(count(first, 'diamond'), 0);
        client = await h.connect(s, 'NativeQuit'); await h.open(client);
        assert.equal(h.total(client, 'emerald', 54, 90), 11);
        assert.equal(h.total(client, 'diamond'), 16);
        h.sendClick(client, 0);
        await h.until(() => h.total(client, 'diamond', 54, 90) === 16, 'withdraw after native quit');
        await quit(s, client);
        const second = await saved(file);
        assert.notEqual(second.BukkitValues?.['voidclear:session_logout'], token);
        assert.equal(count(second, 'diamond'), 16); assert.equal(count(second, 'emerald'), 11);
        client = await h.connect(s, 'NativeQuit'); await h.open(client);
        assert.equal(h.total(client, 'diamond'), 0);
        assert.equal(h.total(client, 'diamond', 54, 90), 16);
        await quit(s, client);
        await h.stop(s);
        assert.equal(await s.done, 0);
        assert(!/VC_PROBE_FAILED|Could not pass event|Error occurred while|not owned by current region|退出后未确认安全释放/.test(s.output), s.output.slice(-7000));
        results.checks.push({ mode, backend: 'sqlite', passed: true, nativeInventory: 11, transferredInventory: 16, renewedQuitReceipt: true, reconnects: 2 });
        console.log('PASS native quit', results.checks.at(-1));
      } finally { client?.end('Cleanup'); if (s) await h.stop(s); }
    }
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally { h.clients.forEach(client => client.end('Cleanup')); await Promise.all(h.servers.map(h.stop)); fs.writeFileSync(report, JSON.stringify(results, null, 2)); }
})();
