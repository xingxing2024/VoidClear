// Control: the same runtime/plugins, but a vanilla chest instead of VoidClear's menu.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const h = require('./platforms.cjs');
const { spawn } = require('node:child_process');
assert(process.env.VOIDCLEAR_TEST_NAMESPACE, 'Use a fresh isolated namespace');
const candidate = path.resolve(__dirname, process.env.VOIDCLEAR_TEST_JAR);
const results = { candidate, sha256: require('node:crypto').createHash('sha256').update(fs.readFileSync(candidate)).digest('hex'), observations: [] };
(async () => {
  try {
    let s = await h.start('resilience-native-cursor-death', 25655, false);
    if (process.argv.includes('--without-plugins')) {
      await h.stop(s);
      for (const file of ['VoidClear.jar', 'VoidClearProbe.jar', 'PlaceholderAPI.jar'])
        fs.renameSync(path.join(s.dir, 'plugins', file), path.join(s.dir, 'plugins', file + '.disabled'));
      const dir = s.dir;
      const child = spawn('java', ['-Xms256M', '-Xmx1024M', '-Dfile.encoding=UTF-8', '-jar', 'server.jar', '--nogui'], { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'] });
      s = { name: 'native-no-plugins', dir, port: 25655, child, output: '', exited: false };
      const log = fs.createWriteStream(path.join(dir, 'without-plugins.log'));
      s.done = new Promise(resolve => child.once('close', code => { s.exited = true; log.end(() => resolve(code)); }));
      for (const stream of [child.stdout, child.stderr]) stream.on('data', data => { s.output += data; log.write(data); });
      s.cmd = command => { if (!s.exited) child.stdin.write(command + '\n'); };
      h.servers.push(s);
      await h.until(() => s.output.includes('Done (') || s.exited, 'no-plugin startup', 240000);
      assert(!s.exited && !/Enabling (VoidClear|PlaceholderAPI)/.test(s.output), s.output);
      results.withoutPlugins = true;
    }
    const c = await h.connect(s, 'NativeCursorQA');
    c.write('player_loaded', {});
    s.cmd('gamemode survival NativeCursorQA');
    s.cmd('tp NativeCursorQA 0.5 -60 0.5');
    s.cmd('setblock 1 -60 0 chest');
    s.cmd('item replace block 1 -60 0 container.0 with quartz 16');
    await h.sleep(500);
    c.write('block_place', { hand: 0, location: { x: 1, y: -60, z: 0 }, direction: 1,
      cursorX: .5, cursorY: .5, cursorZ: .5, insideBlock: false, worldBorderHit: false, sequence: 1 });
    await h.until(() => c.window?.items.length === 63, 'native chest open');
    h.sendClick(c, 0, 0);
    await h.until(() => c.window.cursor?.itemCount === 16, 'native chest cursor');
    const offset = s.output.length;
    s.cmd('kill NativeCursorQA');
    await h.until(() => s.output.slice(offset).includes('Killed NativeCursorQA'), 'native chest death');
    await h.sleep(1500);
    if (results.withoutPlugins) {
      s.cmd('execute as @e[type=item] run data get entity @s Item');
      s.cmd('data get entity NativeCursorQA Inventory');
      s.cmd('say NATIVE_CONTROL_END');
      await h.until(() => s.output.slice(offset).includes('[Server] NATIVE_CONTROL_END'), 'no-plugin counts');
      results.observations.push({ stage: 'no-plugin death', console: s.output.slice(offset) });
      console.log('OBSERVED no-plugin control', results.observations);
      return;
    }
    s.cmd('vcprobe ground count NativeCursorQA native');
    await h.until(() => s.output.slice(offset).includes('VC_GROUND_native='), 'native ground count');
    const ground = Number(s.output.slice(offset).match(/VC_GROUND_native=(\d+)/)[1]);
    c.write('client_command', { actionId: 0 });
    await h.sleep(500);
    await h.open(c);
    results.observations.push({ stage: 'native chest cursor death and respawn', ground,
      bin: h.total(c, 'quartz'), player: h.total(c, 'quartz', 54, 90), cursor: c.window.cursor?.itemCount || 0 });
    results.passed = ground === 16 && h.total(c, 'quartz') === 0 && h.total(c, 'quartz', 54, 90) === 0;
    assert(results.passed, JSON.stringify(results.observations));
    console.log('PASS native control', results.observations);
  } catch (error) { results.error = error.stack; console.error(error); process.exitCode = 1; }
  finally {
    h.clients.forEach(c => c.end('Cleanup'));
    await Promise.all(h.servers.map(h.stop));
    fs.writeFileSync(path.join(__dirname, `native-cursor-death-audit-${process.env.VOIDCLEAR_TEST_NAMESPACE}.json`), JSON.stringify(results, null, 2));
  }
})();
