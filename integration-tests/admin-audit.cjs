// Feature-only checks on fresh loopback servers; never connects to production data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

function auditRows(text) {
  return text.split(/\r?\n/).filter(line => line.trim()).map(line => {
    const fields = line.split('\t');
    assert.equal(fields.length, 8, 'Malformed audit row');
    const [time, player, name, server, operation, action, material, quantity] = fields;
    assert(Number.isFinite(Date.parse(time)), 'Invalid audit time');
    for (const id of [player, server, operation]) assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    assert(['DEPOSIT', 'WITHDRAW'].includes(action));
    assert.match(material, /^[a-z0-9_.-]+:[a-z0-9_./-]+$/);
    assert.match(quantity, /^[1-9][0-9]*$/);
    assert(Number.isSafeInteger(Number(quantity)));
    return { time, player, name: decodeURIComponent(name.replace(/\+/g, ' ')), server, operation, action, material, quantity: Number(quantity) };
  });
}

if (process.argv.includes('--self-check')) {
  const id = '11111111-1111-4111-8111-111111111111';
  const row = `2026-11-10T00:00:00Z\t${id}\tAlex%2B%25\t${id}\t${id}\tDEPOSIT\tminecraft:diamond\t16\n`;
  assert.equal(auditRows(row)[0].name, 'Alex+%');
  for (const bad of [row.replace('16\n', '0\n'), row.replace('DEPOSIT', 'SWEEP'), row.replace('2026-11-10T00:00:00Z', 'bad'), row + 'broken\n'])
    assert.throws(() => auditRows(bad));
  assert.deepEqual(auditRows(''), []);
  console.log('admin-audit parser self-check: ALL OK');
} else {
  const h = require('./platforms.cjs');
  const namespace = process.env.VOIDCLEAR_TEST_NAMESPACE;
  assert(namespace && /^[a-z0-9_]{1,20}$/.test(namespace), 'Fresh namespace required');
  assert(process.env.VOIDCLEAR_TEST_JAR, 'Explicit candidate required');
  const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
  const report = path.join(__dirname, `admin-audit-${namespace}.json`);
  assert(!fs.existsSync(report), 'Preserve earlier reports');
  const results = { candidate, sha256: crypto.createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), checks: [] };
  const readAudit = s => ['player-audit.log.1', 'player-audit.log'].flatMap(file => {
    const target = path.join(s.dir, 'plugins/VoidClear', file);
    return fs.existsSync(target) && fs.statSync(target).isFile() ? auditRows(fs.readFileSync(target, 'utf8')) : [];
  });
  let sequence = 0;
  async function check(name, action) {
    const evidence = await action();
    results.checks.push({ name, passed: true, evidence }); console.log('PASS', name, JSON.stringify(evidence));
  }
  async function command(s, command, marker) {
    const offset = s.output.length; s.cmd(command);
    await h.until(() => s.output.slice(offset).includes(marker) || s.output.slice(offset).includes('VC_PROBE_FAILED'), command, 30000);
    const output = s.output.slice(offset);
    assert(!output.includes('VC_PROBE_FAILED'), output);
    return output;
  }
  async function barrier(s) { await command(s, 'vc audit 1', '本服存取审计'); }
  async function snapshot(s) {
    const token = 's' + ++sequence;
    const output = await command(s, 'vcprobe dbsnapshot ' + token, `VC_DB_SNAPSHOT_${token}=`);
    const match = output.match(new RegExp(`VC_DB_SNAPSHOT_${token}=([a-f0-9]{64})`));
    assert(match, output); return match[1];
  }
  async function exported(s, expected = '数据库读取正常') {
    await command(s, 'vc diagnose export', '诊断已导出');
    const text = fs.readFileSync(path.join(s.dir, 'plugins/VoidClear/diagnostics.txt'), 'utf8');
    assert(text.includes(expected), text);
    assert(!/jdbc:|voidclear_test|evidence|planned\.storage|password/i.test(text), text);
    return text;
  }
  async function client(s, name) {
    const c = await h.connect(s, name); c.write('player_loaded', {});
    await h.until(() => s.output.includes(name + ' joined the game'), 'actual player join');
    return c;
  }
  async function query(c, command, marker) {
    const offset = c.chats.length; c.chat(command);
    await h.until(() => c.chats.slice(offset).some(line => line.includes(marker)), command);
    return c.chats.slice(offset).join('\n');
  }
  async function pending(s, c) {
    const deadline = Date.now() + 10000;
    do {
      const output = await command(s, 'vcprobe dbpending ' + c.username, 'VC_DB_PENDING=');
      if (output.includes('VC_DB_PENDING=true')) return;
      await h.sleep(50);
    } while (Date.now() < deadline);
    assert.fail('Click did not enter pending state: ' + c.username);
  }
  async function start(name, port, mysql, folia) {
    const dir = path.resolve(__dirname, '../target/platform-tests', `${namespace}-${name}`);
    assert(!fs.existsSync(dir), 'Preserve earlier server evidence: ' + dir);
    return h.start(name, port, mysql, folia, 33317, { probe: true });
  }
  async function feature(s, name, mysql) {
    const c = await client(s, name); await h.open(c); await h.settled(s, c);
    await check(s.name + ': ordinary players cannot query diagnostics or audit', async () => {
      await command(s, 'deop ' + name, 'no longer a server operator');
      const diagnostic = await query(c, '/vc diagnose export', '没有权限');
      const audit = await query(c, '/vc audit', '没有权限');
      assert(!fs.existsSync(path.join(s.dir, 'plugins/VoidClear/diagnostics.txt')));
      await h.open(c);
      await command(s, 'op ' + name, 'Made ' + name + ' a server operator');
      return { diagnosticDenied: diagnostic.includes('没有权限'), auditDenied: audit.includes('没有权限'), publicTrashAllowed: true };
    });
    await check(s.name + ': only confirmed bin movements are audited', async () => {
      const before = readAudit(s).length;
      if (mysql) {
        s.cmd('give ' + name + ' diamond 16');
        await h.until(() => h.total(c, 'diamond', 54, 90) === 16, 'seed held deposit');
        await command(s, 'vcprobe dbhold', 'VC_DB_HELD');
        try {
          const slot = c.window.items.findIndex((item, n) => n >= 54 && item?.itemCount === 16);
          assert(slot >= 54); h.sendClick(c, slot);
          await pending(s, c); await barrier(s);
          assert.equal(readAudit(s).length, before, 'Queued transfer was reported as successful');
          const text = await exported(s);
          assert.match(text, /后台任务排队 [1-9]/);
          assert(text.includes('待确认玩家 1'), text);
        } finally { await command(s, 'vcprobe dbrelease', 'VC_DB_RELEASED'); }
        await h.until(() => h.total(c, 'diamond') === 16 && h.total(c, 'diamond', 54, 90) === 0, 'held deposit completion');
      } else await h.deposit(c, 'diamond', 16);
      await h.settled(s, c); await barrier(s);
      const deposited = readAudit(s).slice(before);
      assert.equal(deposited.length, 1); assert.equal(deposited[0].action, 'DEPOSIT'); assert.equal(deposited[0].quantity, 16);
      assert.equal(deposited[0].name, name); assert.equal(deposited[0].material, 'minecraft:diamond');
      const identity = (await exported(s)).match(/本服 ([a-f0-9-]{36})/);
      assert(identity, 'Diagnostic server identity missing');
      const serverId = identity[1];
      assert.equal(deposited[0].server, serverId);
      const diamondSlot = c.window.items.findIndex((item, n) => n < 45 && item?.itemCount === 16);
      assert(diamondSlot >= 0); h.sendClick(c, diamondSlot, 0, 0);
      await h.until(() => c.window?.cursor?.itemCount === 16, 'withdraw to cursor');
      await h.settled(s, c); await barrier(s);
      const withdrawn = readAudit(s).slice(before);
      assert.equal(withdrawn.length, 2); assert.equal(withdrawn[1].action, 'WITHDRAW'); assert.equal(withdrawn[1].quantity, 16);
      c.write('close_window', { windowId: c.window.id });
      const closeToken = 'close' + ++sequence;
      await query(c, '/vcprobe clientsync ' + closeToken, 'VC_CLIENT_SYNC_' + closeToken);
      await h.settled(s, c); await h.open(c); await h.settled(s, c); await barrier(s);
      assert.equal(h.total(c, 'diamond', 54, 90), 16);
      assert.equal(readAudit(s).length, before + 2, 'Cursor return was audited as another withdrawal');
      h.sendClick(c, 44); await h.settled(s, c); await barrier(s);
      assert.equal(readAudit(s).length, before + 2, 'Empty click created an audit');
      const match = await query(c, '/vc audit 20 player ' + name.toLowerCase(), '取出 minecraft:diamond ×16');
      assert(match.includes('存入 minecraft:diamond ×16') && match.includes('取出 minecraft:diamond ×16'), match);
      const uuid = await query(c, '/vc audit 20 player ' + deposited[0].player, '服务器=');
      assert(uuid.includes('minecraft:diamond ×16'));
      await command(s, 'vc audit player ' + name.slice(0, -1), '保留范围内无匹配记录');
      return { deposit: 16, withdrawal: 16, confirmedOnly: true, server: serverId, player: deposited[0].player, cursorReturnIgnored: true };
    });
    await check(s.name + ': diagnosis and export leave all nine SQL tables unchanged', async () => {
      await h.settled(s, c);
      const before = await snapshot(s), text = await exported(s), after = await snapshot(s);
      assert.equal(after, before);
      assert(text.includes(mysql ? 'MySQL' : 'SQLite'), text);
      await query(c, '/vc diagnose', '数据库读取正常');
      return { before, after, ownerThreadReply: true, secretsExcluded: true };
    });
    await check(s.name + ': audit append failure does not change a confirmed transfer', async () => {
      const file = path.join(s.dir, 'plugins/VoidClear/player-audit.log'), backup = file + '.io-test-backup';
      const before = readAudit(s).length, offset = s.output.length;
      const emeraldBefore = h.total(c, 'emerald');
      fs.renameSync(file, backup); fs.mkdirSync(file);
      try {
        await h.deposit(c, 'emerald', 3); await h.settled(s, c);
        await h.until(() => s.output.slice(offset).includes('已确认交易的审计写入失败'), 'expected audit I/O failure');
        await command(s, 'vcprobe dbsnapshot io' + ++sequence, 'VC_DB_SNAPSHOT_io' + sequence + '=');
        assert.equal(h.total(c, 'emerald'), emeraldBefore + 3); assert.equal(h.total(c, 'emerald', 54, 90), 0);
        assert(!s.output.slice(offset).includes('玩家物品交易已隔离'), s.output.slice(offset));
      } finally { fs.rmdirSync(file); fs.renameSync(backup, file); }
      assert.equal(readAudit(s).length, before, 'Failed log append fabricated a record');
      await h.deposit(c, 'quartz', 2); await h.settled(s, c); await barrier(s);
      const appended = readAudit(s).slice(before);
      assert.equal(appended.length, 1); assert.equal(appended[0].material, 'minecraft:quartz'); assert.equal(appended[0].quantity, 2);
      return { confirmedEmerald: 3, appendFailureLogged: true, noCompensationOrIsolation: true, nextAppendRecovered: true };
    });
    return c;
  }
  (async () => {
    const watchdog = setTimeout(() => { results.timeout = true; process.exitCode = 1; h.servers.forEach(s => s.cmd('stop')); }, 600000);
    try {
      for (const [name, port, mysql, folia, username] of [
        ['paper-sqlite', 25652, false, false, 'AuditPaperSQL'],
        ['folia-sqlite', 25655, false, true, 'AuditFoliaSQL'],
        ['resilience-admin-folia-mysql', 25657, true, true, 'AuditFoliaDB']
      ].filter(() => !process.argv.includes('--cross-only'))) {
        const s = await start(name, port, mysql, folia), c = await feature(s, username, mysql);
        c.end('Feature checks complete'); await h.sessionClosed(s, username); await h.stop(s);
        await check(name + ': clean shutdown retains audit records', async () => {
          const rows = readAudit(s); assert.equal(rows.length, 3); assert.equal(await s.done, 0);
          assert(!/Could not pass event|not owned by current region|Thread failed|VC_PROBE_FAILED/.test(s.output), s.output.slice(-7000));
          return { rows: rows.length, exitCode: await s.done };
        });
      }
      const a = await start('paper-mysql', 25653, true, false), ca = await feature(a, 'AuditPaperDB', true);
      const b = await start('paper-mysql-b', 25656, true, false), cb = await feature(b, 'AuditPeerDB', true);
      await check('MySQL: competing withdrawals produce exactly one successful audit', async () => {
        await h.until(() => [ca, cb].every(c => h.total(c, 'emerald') === 6 && h.total(c, 'quartz') === 4), 'both servers observe final fixture writes');
        await h.open(ca); await h.open(cb);
        await h.settled(a, ca); await h.settled(b, cb);
        await h.deposit(ca, 'iron_ingot', 8); await h.settled(a, ca); await barrier(a);
        await h.until(() => h.total(cb, 'iron_ingot') === 8, 'cross-server item sync');
        const beforeA = readAudit(a).length, beforeB = readAudit(b).length;
        const slotA = ca.window.items.findIndex((item, n) => n < 45 && item?.itemCount === 8);
        const slotB = cb.window.items.findIndex((item, n) => n < 45 && item?.itemCount === 8);
        assert(slotA >= 0 && slotB >= 0); h.sendClick(ca, slotA); h.sendClick(cb, slotB);
        await h.until(() => h.total(ca, 'iron_ingot', 54, 90) + h.total(cb, 'iron_ingot', 54, 90) === 8, 'one winner');
        await h.settled(a, ca); await h.settled(b, cb);
        await barrier(a); await barrier(b);
        const rows = readAudit(a).slice(beforeA).concat(readAudit(b).slice(beforeB));
        assert.equal(rows.length, 1); assert.equal(rows[0].action, 'WITHDRAW'); assert.equal(rows[0].quantity, 8);
        return { attempts: 2, successfulAuditEntries: 1, held: 8 };
      });
      await check('MySQL: rejected delivery is absent from audit and visible as quarantined diagnosis', async () => {
        await h.until(() => [ca, cb].every(c => h.total(c, 'iron_ingot') === 0), 'both servers observe completed withdrawal');
        await h.open(ca); a.cmd('give AuditPaperDB gold_ingot 5');
        await h.settled(a, ca);
        await h.until(() => h.total(ca, 'gold_ingot', 54, 90) === 5, 'seed failing delivery');
        const before = readAudit(a).length, offset = a.output.length;
        await command(a, 'vcprobe dbhold', 'VC_DB_HELD');
        try {
          const slot = ca.window.items.findIndex((item, n) => n >= 54 && item?.itemCount === 5);
          assert(slot >= 54); h.sendClick(ca, slot); await pending(a, ca);
          a.cmd('give AuditPaperDB redstone 1');
          await h.until(() => h.total(ca, 'redstone', 54, 90) === 1, 'inventory mutation during pending');
        } finally { await command(a, 'vcprobe dbrelease', 'VC_DB_RELEASED'); }
        await h.until(() => a.output.slice(offset).includes('玩家物品交易已隔离'), 'failed delivery quarantine');
        await h.until(() => a.output.slice(offset).includes('AuditPaperDB lost connection'), 'quarantined disconnect');
        await h.sessionClosed(a, ca.username); await barrier(a);
        assert.equal(readAudit(a).length, before);
        const hash = await snapshot(a), diagnostic = await exported(a), after = await snapshot(a);
        assert(diagnostic.includes('QUARANTINED=1'), diagnostic); assert.equal(after, hash);
        const remote = await exported(b); assert(remote.includes('QUARANTINED=1'), remote);
        return { failedTransferAuditEntries: 0, state: 'QUARANTINED', visibleAcrossServers: true, databaseUnchanged: hash === after };
      });
      cb.end('Feature checks complete'); await h.sessionClosed(b, cb.username);
      await h.stop(b); await h.stop(a);
      await check('MySQL: both Paper servers retain distinct local audit records after shutdown', async () => {
        assert.equal(await a.done, 0); assert.equal(await b.done, 0);
        assert(!/Could not pass event|not owned by current region|Thread failed|VC_PROBE_FAILED/.test(a.output + b.output));
        const local = [readAudit(a), readAudit(b)];
        assert(local.every(rows => rows.length >= 3));
        assert.notEqual(local[0][0].server, local[1][0].server);
        return { paperRows: local[0].length, peerRows: local[1].length, distinctLocalServers: true };
      });
    } catch (failed) { results.error = failed.stack; console.error(failed); process.exitCode = 1; }
    finally {
      h.servers.forEach(s => s.cmd('vcprobe dbrelease'));
      h.clients.forEach(c => c.end('Cleanup'));
      await Promise.all(h.servers.map(h.stop)); clearTimeout(watchdog);
      results.finishedAt = new Date().toISOString();
      fs.writeFileSync(report, JSON.stringify(results, null, 2), { flag: 'wx' });
    }
  })();
}
