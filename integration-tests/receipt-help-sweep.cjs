// Feature-only checks on fresh loopback worlds and test databases. Never uses production data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { stripVTControlCharacters } = require('node:util');
const { createRequire } = require('node:module');
const req = createRequire(path.resolve(__dirname, '../../SimpleFly/integration-tests/package.json'));
const nbt = req('prismarine-nbt');

function components(value) {
  if (value?.type) value = nbt.simplify(value);
  if (typeof value === 'string') { try { return components(JSON.parse(value)); } catch { return []; } }
  if (Array.isArray(value)) return value.flatMap(components);
  if (!value || typeof value !== 'object') return [];
  return [value, ...Object.values(value).flatMap(components)];
}
function clicks(packets) {
  return packets.flatMap(components).flatMap(component => {
    const click = component.clickEvent || component.click_event;
    return click ? [{ action: click.action, value: click.value ?? click.command,
      hover: !!(component.hoverEvent || component.hover_event) }] : [];
  });
}
function assertSafeHelp(packets, count) {
  const actions = clicks(packets);
  assert.equal(actions.filter(event => event.action === 'suggest_command').length, count);
  for (const event of actions) {
    assert(event.hover, 'Missing usage hover');
    assert(['suggest_command', 'run_command'].includes(event.action));
    if (event.action === 'run_command') assert.match(event.value, /^\/vc help [1-3]$/);
    assert(!event.value.includes('confirm'), 'Dangerous action on a help button');
  }
  return actions;
}

if (process.argv.includes('--self-check')) {
  const old = { text: 'row', clickEvent: { action: 'suggest_command', value: '/vc trash ' }, hoverEvent: { action: 'show_text' } };
  const modern = { text: 'row', click_event: { action: 'suggest_command', command: '/vc receipt ' }, hover_event: { action: 'show_text' } };
  assertSafeHelp([{ extra: [old, modern] }], 2);
  assert.throws(() => assertSafeHelp([{ ...old, clickEvent: { action: 'run_command', value: '/vc trash clear confirm' } }], 0));
  assert.throws(() => assertSafeHelp([{ ...old, hoverEvent: undefined }], 1));
  assert.match(stripVTControlCharacters('确认回收 \u001b[38;5;15m13\u001b[38;5;7m 件物品'), /确认回收\s*13\s*件物品/);
  console.log('receipt/help/sweep self-check: ALL OK');
} else {
  const namespace = process.env.VOIDCLEAR_TEST_NAMESPACE;
  assert(namespace && /^[a-z0-9_]{1,20}$/.test(namespace), 'Fresh namespace required');
  assert(process.env.VOIDCLEAR_TEST_JAR, 'Explicit candidate required');
  const h = require('./platforms.cjs');
  const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
  const report = path.join(__dirname, `receipt-help-sweep-${namespace}.json`);
  assert(!fs.existsSync(report), 'Preserve previous reports');
  const results = { candidate, sha256: crypto.createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
  let sequence = 0;
  async function check(name, action) {
    try {
      const evidence = await action();
      results.checks.push({ name, passed: true, evidence });
      console.log('PASS', name, JSON.stringify(evidence));
    } catch (error) { results.checks.push({ name, passed: false, error: error.stack }); throw error; }
  }
  const matches = (text, marker) => typeof marker === 'string' ? text.includes(marker) : marker.test(text);
  async function command(s, text, marker) {
    const offset = s.output.length; s.cmd(text);
    await h.until(() => matches(s.output.slice(offset), marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), text, 30000);
    const output = stripVTControlCharacters(s.output.slice(offset)); assert(!output.includes('VC_PROBE_FAILED'), output); return output;
  }
  async function query(c, text, marker) {
    const offset = c.chats.length; c.chat(text);
    await h.until(() => matches(c.chats.slice(offset).join('\n'), marker), text, 30000);
    return c.chats.slice(offset).join('\n');
  }
  async function client(s, name) {
    const offset = s.output.length, c = await h.connect(s, name);
    c.helpPackets = [];
    c.on('packet', (data, meta) => { if (meta.name === 'system_chat') c.helpPackets.push(data.content); });
    c.write('player_loaded', {});
    await h.until(() => s.output.slice(offset).includes(name + ' joined the game'), name + ' actual join');
    return c;
  }
  async function start(name, port, mysql = false, folia = false) {
    assert(!fs.existsSync(path.resolve(__dirname, '../target/platform-tests', namespace + '-' + name)), 'Preserve prior worlds');
    return h.start(name, port, mysql, folia, 33317, { probe: true });
  }
  async function snapshot(s) {
    const token = 'r' + ++sequence;
    const text = await command(s, 'vcprobe dbsnapshot ' + token, 'VC_DB_SNAPSHOT_' + token + '=');
    const found = text.match(new RegExp('VC_DB_SNAPSHOT_' + token + '=([a-f0-9]{64})'));
    assert(found, text); return found[1];
  }
  async function help(s, c) {
    await check(s.name + ': paged help only suggests commands; ordinary help hides administration', async () => {
      const before = await snapshot(s), pages = [];
      for (const [page, count] of [[1, 6], [2, 5], [3, 3]]) {
        const offset = c.helpPackets.length;
        const text = await query(c, '/vc help ' + page, '悬停看用法');
        assert(text.includes(page + ' / 3'), text);
        pages.push(assertSafeHelp(c.helpPackets.slice(offset), count).length);
      }
      await command(s, 'deop ' + c.username, 'no longer a server operator');
      const offset = c.helpPackets.length, text = await query(c, '/vc help', '悬停看用法');
      assert(text.includes('公共回收') && !text.includes('/vc receipt') && !text.includes('/vc sweep'), text);
      assertSafeHelp(c.helpPackets.slice(offset), 1);
      await query(c, '/vc receipt ' + c.uuid, '没有权限');
      await command(s, 'op ' + c.username, 'Made ' + c.username + ' a server operator');
      await query(c, '/vc receipt ' + c.uuid, '仅限服务器控制台');
      assert.equal(await snapshot(s), before, 'Help or denied commands changed SQL');
      return { pages, publicCommands: 1, playerCleanupDenied: true, sqlUnchanged: true };
    });
  }
  async function sweeps(s, c, automatic = false) {
    await h.open(c);
    await check(s.name + ': unchanged menu buttons avoid writes; changed and full renders restore buttons', async () => {
      const text = await command(s, 'vcprobe menurefresh ' + c.username, 'VC_MENU_REFRESH=');
      assert.match(text, /VC_MENU_REFRESH=100:0:[1-9]\d*:restored/);
      return { unchangedRefreshes: 100, unchangedButtonWrites: 0, changedAndFullRender: true };
    });
    for (const world of ['overworld', 'the_nether', 'the_end'])
      s.cmd('execute in minecraft:' + world + ' run kill @e[type=!minecraft:player]');
    await command(s, 'vcprobe sweepstate', 'VC_SWEEP_STATE=');
    // Killing fixture mobs can create ordinary drops on Folia after the command returns.
    await command(s, 'vc sweep', /本轮未处理目标|本轮清扫结束/);
    await check(s.name + ': empty manual sweep has no collection invitation; status agrees', async () => {
      const text = await command(s, 'vc sweep', '本轮未处理目标');
      assert(text.includes('已加载区块') && !text.includes('/vc trash'), text);
      const status = await command(s, 'vc status', '下次清空垃圾箱');
      assert(status.includes('本轮未处理目标'), status);
      return { empty: true, claimHint: false, sameStatus: true };
    });
    if (automatic) await check(s.name + ': automatic empty broadcast has no collection invitation', async () => {
      const offset = c.chats.length;
      await command(s, 'vcprobe autosweep', 'VC_AUTO_SWEEP_ARMED');
      await h.until(() => c.chats.slice(offset).some(text => text.includes('本轮未处理目标')), 'automatic empty result', 30000);
      const text = c.chats.slice(offset).join('\n');
      assert(!text.includes('/vc trash'), text); return { broadcast: true, claimHint: false };
    });
    await check(s.name + ': actual recovered drops are counted; a later empty sweep does not claim old stock', async () => {
      await query(c, '/summon item ~ ~3 ~ {Item:{id:"minecraft:quartz",count:13},PickupDelay:32767}', 'commands.summon.success');
      const text = await command(s, 'vc sweep', '本轮清扫结束');
      assert.match(text, /确认回收\s*13\s*件物品/); assert(text.includes('/vc trash'), text);
      await h.until(() => h.total(c, 'quartz') === 13, '13 recovered quartz');
      const empty = await command(s, 'vc sweep', '本轮未处理目标');
      assert(!empty.includes('/vc trash'), empty); assert.equal(h.total(c, 'quartz'), 13);
      await query(c, '/summon item ~ ~3 ~ {Item:{id:"minecraft:emerald",count:1},PickupDelay:32767}', 'commands.summon.success');
      await query(c, '/summon item ~ ~3 ~ {Item:{id:"minecraft:iron_ingot",count:1},PickupDelay:32767}', 'commands.summon.success');
      const singles = await command(s, 'vc sweep', '本轮清扫结束');
      assert.match(singles, /确认回收\s*2\s*件物品/); assert(!singles.includes('组掉落物'), singles);
      await h.until(() => h.total(c, 'emerald') >= 1 && h.total(c, 'iron_ingot') === 1, 'two single items counted');
      return { recoveredItems: 13, recoveredGroups: 1, twoSingleItems: 2, laterEmptyClaimHint: false };
    });
  }
  async function close(s, c) {
    const offset = s.output.length; c.end('Feature checks complete');
    await h.until(() => s.output.slice(offset).includes(c.username + ' left the game'), 'actual logout');
    await h.sessionClosed(s, c.username);
  }
  async function resetReceipt(s, seed) {
    const file = path.join(s.dir, 'world/playerdata', seed.uuid + '.dat'), old = file + '_old';
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, seed.bytes, { flag: 'wx' }); fs.writeFileSync(old, seed.bytes, { flag: 'wx' });
    await check(s.name + ': no ownership is not permission to clear; orphan login still fails closed', async () => {
      const before = await snapshot(s);
      await command(s, 'vc receipt ' + seed.uuid, '清理未确认');
      assert.equal(await snapshot(s), before); assert.deepEqual(fs.readFileSync(file), seed.bytes);
      const offset = s.output.length;
      await assert.rejects(() => h.connect(s, seed.name), /VoidClear|未确认|交易/);
      assert(!s.output.slice(offset).includes(seed.name + ' joined the game'));
      assert(s.output.slice(offset).includes('登录前玩家收据不一致'));
      assert.deepEqual(fs.readFileSync(file), seed.bytes);
      return { missingOwnershipRefused: true, loginRefused: true, originalFileUnchanged: true };
    });
    await check(s.name + ': preview/confirmation backs up both originals and changes no SQL rows', async () => {
      const before = await snapshot(s);
      const text = await command(s, 'vc receipt ' + seed.uuid, /\/vc receipt [a-f0-9-]{36} confirm [a-f0-9-]{36}/);
      const match = text.match(/\/vc receipt ([a-f0-9-]{36}) confirm ([a-f0-9-]{36})/); assert(match, text);
      assert.deepEqual(fs.readFileSync(file), seed.bytes); assert.equal(await snapshot(s), before);
      await command(s, `vc receipt ${seed.uuid} confirm ${crypto.randomUUID()}`, '确认码无效');
      await command(s, `vc receipt ${match[1]} confirm ${match[2]}`, '失效收据已清理');
      assert.equal(await snapshot(s), before);
      const backup = path.join(s.dir, 'plugins/VoidClear/receipt-backups', seed.uuid + '-' + match[2]);
      for (const suffix of ['.dat', '.dat_old']) assert.deepEqual(fs.readFileSync(path.join(backup, seed.uuid + suffix)), seed.bytes);
      assert(fs.existsSync(path.join(backup, 'completed.txt')) && fs.existsSync(path.join(backup, 'reset-plan.txt')));
      const expected = (await nbt.parse(Buffer.from(seed.bytes))).parsed;
      assert(expected.value.BukkitValues.value['voidclear:player_receipt']);
      delete expected.value.BukkitValues.value['voidclear:player_receipt'];
      assert.deepEqual((await nbt.parse(fs.readFileSync(file))).parsed, expected);
      assert.deepEqual((await nbt.parse(fs.readFileSync(old))).parsed, expected);
      await command(s, `vc receipt ${match[1]} confirm ${match[2]}`, '确认码无效');
      return { originalBackups: 2, allOtherNbtUnchanged: true, sqlUnchanged: true, confirmationOneUse: true };
    });
    const c = await client(s, seed.name);
    await check(s.name + ': clean baseline can log in and trade without compensation', async () => {
      await h.open(c);
      assert.equal(h.total(c, 'emerald', 54, 90), 9); assert.equal(h.total(c, 'diamond', 54, 90), 0);
      assert.equal(h.total(c, 'diamond'), 0);
      await h.deposit(c, 'gold_ingot', 4); await h.settled(s, c);
      assert.equal(h.total(c, 'gold_ingot'), 4); assert.equal(h.total(c, 'emerald', 54, 90), 9);
      await command(s, 'vc receipt ' + seed.uuid, '清理未确认');
      return { originalEmerald: 9, compensation: 0, newDeposit: 4, onlineResetRefused: true };
    });
    return c;
  }
  async function failedAsyncSweep(s, c) {
    await check(s.name + ': asynchronous delivery failure is incomplete, not empty or successful', async () => {
      await query(c, '/summon item ~ ~3 ~ {Item:{id:"minecraft:redstone",count:7},PickupDelay:32767,Tags:["vc_a17_failure"]}', 'commands.summon.success');
      await command(s, 'vcprobe dbhold', 'VC_DB_HELD');
      const offset = s.output.length;
      try {
        s.cmd('vc sweep');
        let queued = false;
        for (let attempt = 0; attempt < 40 && !queued; attempt++) {
          const state = await command(s, 'vcprobe sweepstate', 'VC_SWEEP_STATE=');
          queued = /VC_SWEEP_STATE=true:[1-9][0-9]*:/.test(state);
          if (!queued) await h.sleep(100);
        }
        assert(queued, 'No asynchronous sweep was queued');
        await command(s, 'data merge entity @e[type=item,tag=vc_a17_failure,limit=1] {Item:{id:"minecraft:redstone",count:8}}', 'Modified entity data');
      } finally { await command(s, 'vcprobe dbrelease', 'VC_DB_RELEASED'); }
      await h.until(() => s.output.slice(offset).includes('本轮清扫未完整执行'), 'failed sweep result', 30000);
      const text = s.output.slice(offset);
      assert(text.includes('清扫来源保持隔离') && !text.includes('本轮未处理目标') && !text.includes('本轮清扫结束'), text);
      assert(!text.includes('/vc trash'), text);
      const state = await command(s, 'vcprobe sweepstate', 'VC_SWEEP_STATE=');
      assert.match(state, /VC_SWEEP_STATE=false:0:1/);
      const status = await command(s, 'vc status', '下次清空垃圾箱');
      assert(status.includes('本轮清扫未完整执行'), status);
      const history = fs.readFileSync(path.join(s.dir, 'plugins/VoidClear/history.log'), 'utf8');
      assert(history.includes('failed_tasks=1'), history);
      return { failures: 1, emptySuccess: false, claimHint: false, batchFinished: true, uncertainSourceKept: true };
    });
  }
  (async () => {
    const watchdog = setTimeout(() => { results.timeout = true; process.exitCode = 1; h.servers.forEach(s => s.cmd('stop')); }, 600000);
    try {
      const donor = await start('resilience-receipt-donor', 25661), name = 'ReceiptSeed17';
      const dc = await client(donor, name);
      await help(donor, dc); await sweeps(donor, dc, true);
      await h.deposit(dc, 'diamond', 16);
      dc.chat('/give @s emerald 9'); await h.until(() => h.total(dc, 'emerald', 54, 90) === 9, 'baseline emerald');
      await h.settled(donor, dc); await close(donor, dc); await h.stop(donor);
      const seed = { name, uuid: dc.uuid, bytes: fs.readFileSync(path.join(donor.dir, 'world/playerdata', dc.uuid + '.dat')) };
      for (const [label, port, mysql, folia] of [
        ['resilience-receipt-paper-db', 25662, true, false],
        ['resilience-receipt-folia', 25663, false, true]
      ]) {
        const s = await start(label, port, mysql, folia), c = await resetReceipt(s, seed);
        await help(s, c); await sweeps(s, c);
        if (mysql) await failedAsyncSweep(s, c);
        await close(s, c); await h.stop(s);
      }
      await check('All three isolated servers stop cleanly without owner-thread violations', async () => {
        for (const s of h.servers) {
          assert.equal(await s.done, 0);
          assert(!/Could not pass event|not owned by current region|Thread failed|VC_PROBE_FAILED/.test(s.output), s.output.slice(-7000));
        }
        return { servers: h.servers.length, exitCodes: [0, 0, 0] };
      });
    } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
    finally {
      h.servers.forEach(s => s.cmd('vcprobe dbrelease'));
      h.clients.forEach(c => c.end('Cleanup'));
      await Promise.all(h.servers.map(h.stop)); clearTimeout(watchdog);
      results.finishedAt = new Date().toISOString();
      fs.writeFileSync(report, JSON.stringify(results, null, 2), { flag: 'wx' });
    }
  })();
}
