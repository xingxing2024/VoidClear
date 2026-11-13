# 多服与 Folia 专项测试

## 当前交付状态

当前候选、部署状态及本轮验证范围统一见 [README 当前状态](../README.md#当前状态)，本页只维护测试环境与运行方法。

历代候选与当时的通过、失败记录见[候选验收历史](../archive/reports/候选验收历史-20261111.md)和[平台验收历史](../archive/reports/平台验收历史.md)。旧包的完整矩阵不能作为新包的同哈希验收。

## 运行前提

- 仅使用隔离测试目录和回环端口，不连接正式服数据库。
- Paper 运行时及协议依赖来自同级 `SimpleFly/test-server`、`SimpleFly/integration-tests`；PAPI 来自同级 `minecraft-fakeplayer-next-master/lib/PlaceholderAPI-2.11.6.jar`。
- Folia 运行时为 `target/platform-tests/folia.jar`：旧版官方 1.21.8 build 6，SHA256 `233843cfd5001b6f658fcab549178d694cc37f0277d004ea295de0a94c57278f`，不是最新版兼容性证明。
- MySQL 使用独立实例 `127.0.0.1:33317/voidclear_test`，测试账号密码均为 `voidclear`，不得用于生产或开放外网。
- 平台端口：Paper A/B 为 25653/25654，Folia SQLite/MySQL 为 25655/25656；维护专项为 25658。
- 脚本会改写隔离服配置、清空测试背包和测试箱。每轮使用新 namespace，运行前确认端口空闲。

## 构建及指定受测包

```powershell
powershell -ExecutionPolicy Bypass -File integration-tests/run.ps1 -BuildOnly
$env:VOIDCLEAR_TEST_JAR = '../target/VoidClear-3.0.0-async-db-a15-candidate.jar'
$env:VOIDCLEAR_TEST_NAMESPACE = 'recovery_unique_run'
```

`-BuildOnly` 构建当前源码插件和探针，不会生成上述命名候选；先将确定要验收的包放到指定路径，再核对 SHA256。平台脚本不会替你重新构建受测包。

## 门禁

1. `powershell -ExecutionPolicy Bypass -File check.ps1`：离线断言检查。
2. 使用 `target/check-classpath.txt` 加 `target/classes;target/test-classes`，执行 `java -ea -cp <classpath> net.voidclear.v2.store.TrashMysqlCheck`。需先启动隔离 MySQL，不在离线脚本内自动运行。
3. `node integration-tests/platforms.cjs all`：双 Paper/MySQL、Folia/SQLite、Folia/MySQL；可单独选择 `mysql`、`folia`、`folia-mysql`。
4. `node integration-tests/maintenance-fixes.cjs`：Paper/SQLite 的计时重载、手动/自动清扫历史、光标拾取保护与重登、启动核对失败自动停服。
5. `node integration-tests/item-fences.cjs`：物品隔离和普通双玩家存取回归。
6. `node integration-tests/drop-restart-audit.cjs`：远处来源重启续接、半登录与 Join 释放、两名登录共享区块、来源移除后缓存不可用于放行。默认 25656，可用 `VOIDCLEAR_DROP_PORT=25652` 指定另一隔离端口。
7. `node integration-tests/login-abort-audit.cjs`：实际协议半登录断开后同账号重连，使用 25652。
8. `node integration-tests/cursor-death-audit.cjs`、`survival-audit.cjs`：死亡、原生拾取、清扫和重启守恒，均使用 25655，须顺序执行。
9. `node integration-tests/folia-drop-restart-audit.cjs`：Folia 远处来源重启/原生拾取，使用 25652；结果须检查是否有 `error`，脚本存在不代表验收通过。

强杀门禁另用 `resilience.cjs`、`player-cuts.cjs`、`player-claim-cuts.cjs`、`player-session-exclusive.cjs`，按各脚本显式参数执行。普通平台通过不能代替强杀验收。

长套件应统一编排，不能只看启动时端口空闲就与其他套件并行：`platforms all` 后半段会使用 25655/25656，claims 默认 25658，cuts 默认 25657，focused 使用 25657/25658。后续阶段的端口也必须预留。每个套件独立 namespace 保留失败目录和 JSON。

完整矩阵结束后执行只读核对：

```powershell
node integration-tests/verify-recovery-results-check.cjs
node integration-tests/verify-recovery-results.cjs target/VoidClear-3.0.0-async-db-a15-candidate.jar
```

该脚本按 JAR 实际 SHA256 选择完整报告，拒绝借用旧哈希、缺项或含 error/fatal 的记录，分别统计预期拒绝登录。它验证指定回归矩阵的证据完整性；线程响应由独立异步实服专项测量，指定矩阵通过不等于生产发布验收。

`verify-recovery-results-check.cjs` 使用内存夹具验证核对脚本本身，不启动服务器、连接数据库或写入文件。

## MySQL 异步开发验收

```powershell
powershell -ExecutionPolicy Bypass -File integration-tests/run-async-audit.ps1 -Candidate a16
```

仅用于本机已有隔离 MySQL 5.7 和运行时缓存的开发环境。脚本拒绝覆盖同名候选，启动独立 33317 实例，顺序运行延迟响应、普通关闭、死亡、平台、故障与切点检查，finally 只关闭自己启动的数据库进程；随后补齐 SQLite 与来源矩阵。候选命名必须使用新的 `a<number>`，失败现场保留。异步专项分别记录提交完成时间和提交等待期间的 tick/数量查询响应，不能把后台等待算作主线程冻结。

只修改测试后可使用 `-Candidate a9 -UseExisting -RunTag a9b` 在新目录重跑同一包，不会重建或覆盖 JAR；仍要求生产类与当前编译输出逐字节一致。`RunTag` 要使用新的短名称。Paper 与 Folia 各自提供异步 5 项证据，死亡检查必须先完成客户端 `player_loaded` 握手并实际收到零生命值，不能仅靠 kill 命令回执判定。

Folia 专项使用不依赖 PAPI 的独立探针，允许 pending、tick、退出状态、玩家日志、逐区域地面计数，以及测试数据库队列 gate 和后台只读 SQL 哈希；旧 PAPI 不复制到 Folia 测试服。领取切点中探针只触发真实拾取事件，MySQL 的数量/日志断言等实际异步确认后再执行，强杀边界仍由原 prepared/storage/receipt/confirmed 探针触发。

拒连专项在上一笔实际确认完成后关闭代理，要求下一笔进入预期提交失败并在同进程拒绝重登；读取原生玩家 NBT 确认绿宝石9件未消费，独立数据库读取确认新存入0件、原钻石16件仍在。正常显示变化不能替代后台确认。死亡报告分别要求SQLite和MySQL各一份；不能跨哈希或用同一后端报告补齐矩阵。已有`RunTag`的世界不允许覆盖。

`join-quit-tickets.cjs`在MySQL独立实例运行期间使用25652端口，验证Join复核排队时退出也释放实际来源ticket；数据库恢复后重连仍核对来源。完整矩阵已含该专项，探针gate仅用于隔离测试，30秒未释放则失败。

追加审查新增`abandoned-pickup-audit.cjs`，顺序使用25652与隔离MySQL：验证未就绪拾取在退出后释放预留，以及旧连接finished/isolate不会影响新连接。旧包a12已被该专项复现预留泄漏，a13相同专项2项及完整矩阵通过，151轮浸泡通过；历史证据见[追加审查](../archive/reports/追加审查-20261110.md)。当前a15已独立重跑这2项与完整矩阵；完整门禁要求这2项报告，不能将a12历史门禁结论当作新门禁通过。

平台结果写入 `platform-results-<mode>.json` 及带时间戳副本，专项结果带 namespace。验收时必须核对结果内受测包哈希；`platform-results-all.json` 这个文件名本身不代表当前源码已通过。

## 诊断与玩家存取审计专项

```powershell
powershell -ExecutionPolicy Bypass -File integration-tests/run-admin-audit.ps1 -Candidate a16 -RunTag fresh1 -UseExisting
```

下例 `a16` 为历史候选；该包装脚本按 `admin-audit-aN` 命名查找 JAR，不会自动使用当前维护包。须先核对受测文件及 SHA256。

本专项验证诊断与审计功能，顺序使用 Paper/SQLite（25652）、Folia/SQLite（25655）、独立表的 Folia/MySQL（25657），再用两服同版本 Paper/MySQL（25653、25656）共享全新的测试表。Paper 1.21.7 与 Folia 1.21.8 不共享物品表，避免新版物品数据被旧服拒绝读取。Folia/MySQL 沿用 `resilience-admin-folia-mysql` 夹具的独立表前缀；仅此夹具关闭原生自动保存，交易仍显式保存实际玩家存档。

脚本只启动和关闭自己的 33317 隔离数据库；`RunTag` 必须全新，报告写入 `admin-audit-admin<RunTag>.json`，旧报告、世界和玩家存档保留。省略 `-UseExisting` 时仅在目标候选不存在时打包，使用既有候选时验证其生产类和资源，不覆盖 JAR。追加 `-CrossOnly` 只运行两服 Paper 的新功能与跨服检查，不重跑已验证的三个独立夹具，仍保留独立报告。

专项检查权限、确认前不记账、成功存取数量、光标归还与空点击不记账、精确玩家筛选、只读导出、日志 I/O 失败、跨服竞争和隔离状态。独立探针对九张表的完整内容（含证据、会话、隔离和版本）计算哈希，比较诊断导出前后是否相同；不使用诊断自身的数据验证只读性。MySQL 光标关闭后先等待玩家线程处理和异步确认，再重开菜单。

日志 I/O 检查故意令审计路径不可写，期望出现“已确认交易的审计写入失败”，并验证物品不回滚、不补发、不隔离，恢复路径后下一笔继续写入。该预期报错应与意外线程异常区分。新功能专项不代替恢复强杀矩阵、真实代理切服或多人长测。

历史 `a16` 专项结果分别保存在 `admin-audit-admina16r5.json` 与 `admin-audit-admina16r6.json`，两份候选哈希相同。前者三个独立夹具15项及两服各4项通过后，因未等远端页面同步而在跨服准备阶段超时，整次运行失败；后者加入两服实际可见内容同步条件，补跑的11项全部通过且无 `error` / `timeout`。按场景名去重覆盖26项，不把两轮重复检查累加，也不把第五轮失败改成通过。

## 双服多人长测

```powershell
powershell -ExecutionPolicy Bypass -File integration-tests/run-long-load.ps1 -Candidate a15 -RunTag a15long1 -Seconds 1800
```

仅使用既有候选，核对生产类和资源后重建测试探针，不重新打包插件。脚本自建并正常关闭隔离 MySQL 33317，两个 Paper 实例使用 25653/25654，共享同一组测试表；每服堆上限 1 GiB。`RunTag` 必须全新，旧世界、玩家存档和结果保留。

两服逐台启动：每服先执行 `save-all flush`、确认世界文件写入，再启动下一服。玩家接入后保持原生自动保存，按4、8、16个协议客户端分阶段连续存取。钻石、绿宝石、铁锭、金锭各256件，仅在初始化生成；每批等待实际后台确认，并核对所有客户端背包及共享垃圾箱，垃圾箱副本只计一次。记录尝试点击和背包发生变化的次数，竞争失败不计作成功交易。

每30秒记录TPS、平均tick、堆、GC、线程和探针响应；每5分钟验证同账号跨服重复登录被拒，再等待正常退出与SQL会话释放，复制实际带收据的玩家文件到另一隔离服并重登。保留源文件及原目标文件副本，核对复制哈希和物品数量。这是已验证存档的交接夹具，不能替代真实代理或第三方背包同步测试。

长测要求两服世界文件在基线后由原生保存更新，结束时读取实际玩家NBT，正常停服重启，再用SQL库存及所有玩家背包核对总量。批次P95包含协议等待、确认和同步轮询，不是单笔SQL耗时。短测仅用于验证脚本流程，不能视作30分钟测试通过；具体结果以带候选哈希且无`error`的`long-load-<namespace>.json`为准。

`verify-long-load.cjs`按实际JAR哈希只读核对长测报告，拒绝不足30分钟、未完成清理、缺少4/8/16阶段、任一服未实际保存、缺少存档交接或重启证据的结果；各人数阶段必须有实际取出与存入进展，P95必须与原始批次复算一致。有失败日志的报告仍拒绝，测试脚本会在最终断言前记录具体日志与P95，便于保留失败诊断。运行脚本在长测结束后自动调用；也可单独执行：

```powershell
node integration-tests/verify-long-load.cjs target/VoidClear-3.0.0-async-db-a15-candidate.jar integration-tests/long-load-loada15long1.json
```

上述命令校验现有 `loada15long1` 时应返回非零：该次长测因集中退出告警未通过，不能修改报告或放宽告警规则使其变成通过。重新运行须换全新 `RunTag`，保留旧证据。

## 范围和清理

平台检查覆盖跨服/跨区域存取守恒、竞争领取、远端清空、规则与菜单重载、自动清空及正常停服。少量假人通过不代表生产吞吐量或所有线程路径安全。

脚本结束会停止自己的Minecraft子进程；`run-admin-audit.ps1`、`run-long-load.ps1`和`run-async-audit.ps1`也会正常关闭本次启动的隔离MySQL。单独运行Node套件时，数据库仍由启动它的调用方负责。不要停止其他数据库服务。未覆盖提交应答丢失、断电、文件系统损坏、真实代理切服/背包同步和大量真人负载。
