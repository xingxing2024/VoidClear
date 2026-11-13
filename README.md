# 虚空清理（VoidClear）

定时清扫实体与掉落物。扫到的掉落物先存进公共垃圾箱，玩家可用 `/vc trash` 领取。

面向 **Paper / Folia 1.21.4+**，Java 21+。Canvas 26.x 上可用，但请以自己服务端的实测为准。

## 当前状态

本仓库公开插件源码、配置、离线自检和实服测试脚本。运行数据及原始测试结果不随源码发布；集成测试的环境要求见 [测试说明](integration-tests/README.md)。

当前候选：[VoidClear-3.0.0-item-count-20261111-candidate.jar](target/VoidClear-3.0.0-item-count-20261111-candidate.jar)。默认清扫提示改按实际入箱件数显示，新增 `%ITEMS%`，保留旧实体数统计；30 项离线和 23 项定向实服检查通过，含两单件、13件整堆和16件仅接收4件的场景。详见[件数修复记录](archive/reports/清扫物品件数修复-20261111.md)。尚未部署，本包未重跑完整矩阵或30分钟长测。

上一版 cleanup 候选的验证：30 项离线检查、177 项实服功能回归及 30 分钟双服 4/8/16 客户端长测通过；881 轮交易、5 次实际存档交接、集中退出与重启核对正常，本轮无落后告警。三处旧测试文案判定已修正，首次失败记录保留；详见[完整回归与长测](archive/reports/完整回归与长测-20261111.md)。

本轮改动与验证见[简洁维护整理记录](archive/reports/简洁维护整理-20261111.md)；退出重复存盘的定位与采样见[退出修复记录](archive/reports/退出重复存盘修复-20261111.md)。历代候选、失败记录和当时的验收范围保留在[候选验收历史](archive/reports/候选验收历史-20261111.md)，旧结果不代表当前包通过了相同测试。旧 `reviewed6-release.jar` 存在已知强杀缺陷，不建议使用。

崩溃恢复依赖实际玩家存档、世界来源与数据库共同对账；未确认来源、损坏凭据或 `QUARANTINED` 仍须保留证据核查，不能删记录或补发解锁。这些测试不覆盖断电、文件系统损坏、真实代理或背包同步，也不代表所有服务端版本或真人负载。

## 功能

- 按间隔（`timer.period`）或每天固定时刻（`timer.mode: daily`）清扫；清扫前有倒计时播报、Boss 条和音效。
- 倒计时写入 `timer.yml`，重启后接着走（关机时间也算在内）。
- 生物按黑白名单判定：通用、动物、怪物白名单分别配置，再按全局或世界规则判定。支持精确 id、`minecraft:*`、`animals` / `monster`，`*` 匹配全部生物。`monster` 使用 Bukkit `Enemy` 分类并排除 `Animals`。
- 带 `keep` 标记的实体不扫；命名生物按名单正常判定；末影龙、凋灵等 Boss 始终保留。每个世界可以整体覆盖名单与开关。
- 掉落物只有命中豁免（`drop`）才保留，其余进入公共垃圾箱。垃圾箱满时留在世界中，不丢物。
- 可选照料：保护农田不被踩、苦力怕爆炸不破坏方块。
- 管理员只读查看数据库、后台任务及未完成交易，导出诊断报告；查询本服已确认的垃圾箱存入与取出记录。
- 三页分组帮助，悬停查看用法、点击填入指令；清扫结果区分无目标、正常结束和执行异常。
- 控制台可预览并确认清理离线玩家的孤立旧收据，先备份存档；不是交易恢复或通用解除隔离。

## 安装

1. 正常停服，完整备份插件数据与数据库。共享同一个 MySQL 的所有服务器须一起升级，不支持新旧版本混用。
2. 把 jar 放进 `plugins/`，启动服务器。
3. 首次启动会在 `plugins/VoidClear/` 生成 `config.yml`、`messages.yml` 和 `menus/bin.yml`。
4. 修改配置后执行 `/vc reload`，不用重启。数据库连接设置仍需重启。

**启动保护**：配置、数据库、玩家存档位置或会话归属核对失败时，会请求正常停止整个服务器，不能只禁用插件后继续开放。请修复故障再启动，保留数据库、世界与玩家存档，勿删凭据解锁。此保护只在插件实际执行 `onEnable` 后生效，不能覆盖插件被移除、描述文件/JDBC 库加载失败或外部管理器强制禁用；禁止带玩家热启用或热卸载。

Paper 启动时会按 `plugin.yml` 的 `libraries` 自动下载 SQLite/MySQL JDBC 驱动，插件 jar 里不打包驱动。需要用阿里云镜像时，启动前设置：

```powershell
$env:PAPER_DEFAULT_CENTRAL_REPOSITORY = 'https://maven.aliyun.com/repository/public'
java -jar paper.jar nogui
```

```bash
PAPER_DEFAULT_CENTRAL_REPOSITORY=https://maven.aliyun.com/repository/public java -jar paper.jar nogui
```

## 指令

| 指令 | 权限 | 说明 |
| --- | --- | --- |
| `/vc` 或 `/vc help [1-3]` | — | 分页帮助；普通玩家仅显示公共回收入口 |
| `/vc sweep` | `voidclear.admin` | 立刻扫一轮 |
| `/vc trash` | — | 打开公共垃圾箱，自由放入和取出物品 |
| `/vc trash clear confirm` | `voidclear.admin` | 永久清空全部垃圾箱页面；不带 confirm 时只显示警告 |
| `/vc check <实体> [世界]` | `voidclear.admin` | 查询实体在指定世界是否会被清理及判定依据 |
| `/vc keep [名字\|id\|all]` | `voidclear.admin` | 标记实体不清理；不带参数为目标实体，`all` 为 16 格内全部 |
| `/vc unkeep [名字\|id\|all]` | `voidclear.admin` | 取消标记 |
| `/vc status` | `voidclear.admin` | 查看倒计时和上轮结果 |
| `/vc diagnose [export]` | `voidclear.admin` | 只读查询运行与交易状态，选填 export 导出报告 |
| `/vc audit [1-20] [type <DEPOSIT\|WITHDRAW>\|player <名字或UUID>]` | `voidclear.admin` | 查询本服垃圾箱玩家存取记录 |
| `/vc history [条数] [type <操作>\|player <玩家>]` | `voidclear.admin` | 查看操作记录 |
| `/vc trend [天数]` | `voidclear.admin` | 汇总最近每日清扫数量与耗时 |
| `/vc export [天数]` | `voidclear.admin` | 导出 CSV 到插件数据目录 |
| `/vc receipt <玩家UUID> [confirm <预览确认码>]` | `voidclear.admin`，仅服务器控制台 | 预览/确认清理离线玩家的孤立旧收据，不恢复交易 |
| `/vc reload` | `voidclear.admin` | 重载配置 |

主命令为 `/voidclear`，别名 `/vc`；实体保护标记为 `voidclear:keep`。旧命令、旧权限和旧标记已移除，以前只有旧标记的实体需要用 `/vc keep` 重新标记。

`/vc history` 仍只查询 `SWEEP` 清扫汇总；其中 `player` 筛选仅为兼容旧日志保留。玩家存取请使用独立的 `/vc audit`，查询结果为空不能证明玩家没有存取。

### 帮助与清扫结果

管理员帮助分为「日常管理」「记录与诊断」「维护与安全」三页。指令行只显示短说明，悬停显示完整参数，点击只填入输入框；只有翻页按钮会直接执行 `/vc help`。清空和收据清理不会因点击帮助而执行。

自动公告、手动清扫回复和状态查询使用同一份结果：

- **本轮未处理目标**：仅描述已加载区块内本轮可处理的目标，不表示全世界没有实体，也不代表跳过的隔离来源已解决。
- **本轮清扫结束**：显示实际处理目标数和确认入箱的物品件数（两堆 64 + 16 计为 80 件）。部分回收的掉落实体也计为一个已处理目标，不宣称整个实体已移除。
- **本轮清扫未完整执行**：调度、区块处理、数据库或来源交付出现失败；显示失败任务数，并提示检查服务端日志，不冒充正常的零结果。

只有本轮确认回收了掉落物才附带 `/vc trash` 查看提示；垃圾箱内往轮遗留的物品不会触发本轮领取提示，公共物品也可能已被他人取走。历史日志保留原 `removed` 字段以兼容旧统计，其含义是处理目标数；新增 `failed_tasks` 记录失败任务数。

### 只读诊断

`/vc diagnose` 立即显示本服后台任务的排队、执行和待确认玩家数量，再在后台读取数据库。报告包含本服会话与拦截数、数据库版本、物品组数、全库会话及历史未完成操作；最多列出 10 条，隔离状态优先。物品组数是数据库记录数，不是物品件数。普通玩家没有查询权限。

| 状态 | 含义 |
| --- | --- |
| `PREPARED` | 已准备，尚未确认交付 |
| `CURSOR` | 仍有光标物品待核验 |
| `DROPS` | 保留地面来源凭据 |
| `QUARANTINED` | 已隔离，需要核对存档与日志 |

未完成操作不一定是异常，历史来源凭据也可能仍有用途。数据库没有保存具体隔离原因，请按报告中的玩家 UUID、操作 ID 查服务端日志。各项由独立只读查询采样，**不是一致事务快照**；本服即时计数和共享数据库计数也可能在采样期间变化。数据库读取失败时明确显示“状态未知”。

`/vc diagnose export` 将报告写入 `plugins/VoidClear/diagnostics.txt`，后一次导出替换前一次。报告不包含数据库连接地址、密码、完整物品 NBT；含玩家、会话和操作标识，分享前请按需脱敏。诊断与导出不会解除隔离、补发或重放物品，也不会清理恢复凭据。

### 玩家存取审计

```text
/vc audit
/vc audit 20 type DEPOSIT
/vc audit 10 type WITHDRAW
/vc audit 20 player Alex
/vc audit player 11111111-1111-4111-8111-111111111111
```

默认显示最近 10 条，最多 20 条，每次选择一种筛选；玩家名或 UUID 精确匹配，名字不区分大小写。每条包含 UTC 时间、玩家名和 UUID、本服固定服务器 UUID、操作 ID、物品类型与件数。服务器 UUID 对应 `server-owner.id`，不是服务器显示名。

仅在实际玩家存档与数据库均确认成功后记录垃圾箱净增减。同材质不同元数据的交换保留存入、取出两个方向，但日志不保存名称、附魔和完整 NBT。失败竞争、未确认交易、背包整理、关闭菜单后的光标归还、地面拾取都不会写成垃圾箱成功存取；清扫汇总仍写入原 `history.log`。

审计异步写入本服 `player-audit.log`，满 4 MiB 后轮转为 `player-audit.log.1`，只保留这两份、总计至多约 8 MiB，不按天数永久保留。共享 MySQL 的其他服务器仍各自保存日志，`/vc audit` 不汇总全网记录；升级前的历史存取也不会补记。日志写入失败会在服务端报错，不会撤回已确认交易或补发物品。

**审计是有限保留的运行记录，不是恢复凭证。** 断电、强杀、磁盘写入失败或确认后连接失效可能造成缺记录，不能据此补偿物品，也不能凭“无匹配记录”认定从未存取。

### 清理孤立旧收据

仅适用于管理员已经确认旧数据库丢失、明确接受**当前玩家存档为基线**的情况。先核对数据库地址、表前缀及全服备份，不能把连接错库当成收据失效，更不能用此命令猜测恢复或补发旧交易。

在服务器控制台执行（UUID 必须完整，不能填玩家名）：

```text
vc receipt 11111111-1111-4111-8111-111111111111
vc receipt 11111111-1111-4111-8111-111111111111 confirm <上一步返回的确认码>
```

- 第一步只预览，不改存档；确认码五分钟有效，一次只能保留一个预览，新预览替换旧预览。有效确认只有一次尝试机会，失败后需重新核查并预览。
- 必须离线，且本服没有登录、退出、待交付或隔离状态；预览/确认期间拒绝该玩家登录。游戏内、命令方块和 RCON 均不能执行。
- 数据库必须已有该玩家的本服会话归属；缺失时先让玩家尝试登录以建立正常检查路径。异服归属不接管。当前凭据、任何历史操作（包括 `DONE`）、相关来源或物品围栏仍存在时，一律拒绝，不删 SQL 行。
- 确认时重新检查数据库和两份存档；`.dat_old` 可以不存在或没有收据，但存在不同收据、存档损坏、路径链接、文件变化或备份失败时拒绝猜测处理。
- 写入前备份实际玩家 `.dat` 和存在的 `.dat_old`，记录原/新 SHA256；只移除 `voidclear:player_receipt` 标签，其他未压缩 NBT 字节、背包、末影箱和其他 PDC 不变。备用存档先处理，主存档原子替换；文件系统不支持原子替换时不降级写入。

备份在 `plugins/VoidClear/receipt-backups/<玩家UUID>-<确认码>/`，含原存档、`reset-plan.txt`，成功完成文件写入后另有 `completed.txt`。出现“未确认”时应保留整个目录、日志和数据库人工核查；数据库提交与文件替换不是一个原子事务，不能仅凭备份或完成标记补发、回放旧背包。

**此命令不会证明旧交易物品守恒，不是通用解锁，也不能防止再次删库或连接错库。** 正常交易校验继续启用；清理后由正常登录和后续交易重新建立收据。

## 配置

- `timer.period` / `timer.mode` / `timer.times`：清扫节奏。
- 修改普通清扫周期、模式或每日时刻后，`/vc reload` 会重新计时；未变化或配置无效时保留原截止时间。
- `sweep.batch`：一批派发多少个区块，派完再派下一批。
- `sweep.floor`：区块里待清的东西少于这个数就跳过，留到下一轮一起清（0 为关闭）。
- `sweep.marks` / `sound.*` / `bar.*` / `actionbar.*`：倒计时播报、音效、Boss 条与 ActionBar。`bar.sweep.show-before: 60` 控制 Boss 条提前显示的秒数，`0` 为全程显示。
- `rule.*`：判定规则；`rule.world.<世界>` 整体覆盖。
- `drop.*`：掉落物豁免。
- `world.creatures` / `animals` / `monsters` / `experience-orbs` / `drops`：各类别开关，`rule.world.<世界>` 可分别覆盖。
- `trash.clear.*`：垃圾箱自动清空，见下文。
- `messages.yml`：提示文案；帮助使用 `help.header`、`help.sections`、`help.descriptions` 和 `help.footer`。显式自定义的旧 `help.lines` 仍可整段替换管理员帮助，包括空列表。

删掉的键会回落到 jar 内的默认值，显式空列表保持为空。此次仅升级**精确匹配旧默认值**的帮助列表、标题及清扫完成文案，写入前保留 `messages.yml.pre-voidclear.bak`（已存在则用 `.1`、`.2` 等，不覆盖旧备份）；自定义文案不自动改写。自定义 `sweep.done` 如仍带无条件领取提示，需手工移至新的 `sweep.claim`，由本轮回收结果决定是否显示。两份配置全部严格解析且计时设置有效后才允许迁移写入；读取失败时启动失败，`/vc reload` 失败则保留原配置。

### 垃圾箱自动清空

默认关闭，避免升级后误删物品。需要每两小时清空一次时：

```yaml
trash:
  clear:
    enable: true
    mode: interval      # interval 按 period 秒循环；daily 按 times 每天定时
    period: 7200
    times: ['04:00', '16:00']
    marks: [30, 10, 5]
```

- `daily` 的时间列表必须非空且为有效的 `HH:mm`，错误配置会拒绝加载。
- `interval` 模式重启后接着原截止时间走，已过期就在开服后立即清空；`daily` 模式重启后等下一个时刻，不补清关机期间错过的。
- 修改模式、周期或时刻后重载会重新计时；配置没变则保留剩余时间。
- 清空时会关闭垃圾箱页面。已经领到背包或光标上的物品不受影响。

### 垃圾箱菜单

共享分页箱子，默认每页 45 格，最后一行是装饰和翻页按钮，布局来自 `menus/bin.yml`。支持放入、取出、拖动、Shift 点击和相同物品堆叠。最多保存 10,000 组或 64 MiB 原始数据；箱子满时，清扫只收能放下的部分，剩余留在地上。

未确认交易只锁住包含其新增/改写物品的页面，其他页面可以继续存取；隔离跟随物品编号，不因翻页、清扫合并或重启失效。升级前的未确认交易缺少物品范围记录，仍保守锁住整个垃圾箱，不能通过删表解锁。启动自动新增 `_item_fences` 表，不删除已有物品或交易凭据；共享 MySQL 的所有服务器须一起升级。

在支持连接 API 的 Paper 上，未完成入服的连接确认断开后可重新登录；仍保留数据库归属并重新核验存档收据。旧 Paper 无法取得连接状态时保留原有隔离，不按超时或 UUID 关闭事件自动放行。

光标非空时暂不拾取托管掉落物，地面来源保持原样；放回光标物品后可继续拾取。这避免普通容器缺少垃圾箱的光标收尾流程而遗留未确认交易；已有历史 `CURSOR` 不会自动猜测解锁。

启动自动为玩家交易日志补充 `(player_id,state,session_id)` 查询索引，不删除交易或来源凭据。旧日志较大时首次建索引可能延长启动，共享 MySQL 升级仍须全部服务器停服后进行。

本轮未解决：运行中数据库不可用时登录仍会被拒绝；SQLite 同步 SQL、玩家存档保存与刷盘仍可能卡线程；玩家交易日志与清扫凭据仍长期保留；没有一键安全解除隔离的管理员命令，`receipt` 也不绕过已有交易或本服隔离状态。历史长测中的集中退出卡顿也未定位或修复。不要靠超时、删除交易记录或重复补发物品绕过这些保护。

菜单信息支持 `%CLEAR_TIME%`（清空剩余时间）和 `%CLEAR_AT%`（下次清空时间），每秒刷新。`/vc reload` 会严格检查菜单配置：布局有变化就关闭旧页面，菜单无效时拒绝整个重载。

垃圾箱总量和清空提示的 `%COUNT%` 按实际件数统计（64 + 16 = 80 件）。普通清扫的 `%COUNT%` 是处理目标数（含部分回收的掉落实体），`%ITEMS%` 是确认入箱的物品件数（仅计算实际接收部分）；旧 `%COLLECTED%` 继续表示掉落实体数以兼容自定义文案；`%FAILED%` 是本轮失败任务数。

## PlaceholderAPI 变量

安装 PlaceholderAPI 后自动注册，无需下载 eCloud 扩展。所有变量都是全服统计，不需要玩家在线。

| 变量 | 含义 |
| --- | --- |
| `%voidclear_sweep_seconds%` | 普通清扫剩余秒数，未运行返回 0 |
| `%voidclear_sweep_time%` | 普通清扫剩余时间，如 39分09秒 |
| `%voidclear_sweep_at%` | 下次普通清扫日期时间，未运行显示“未开启” |
| `%voidclear_sweep_mode%` | 普通清扫模式：interval / daily |
| `%voidclear_sweep_active%` | 是否正在清扫：true / false |
| `%voidclear_sweep_last_removed%` | 上轮处理目标数量，保留旧键名；含部分回收的掉落实体 |
| `%voidclear_sweep_last_collected%` | 上轮回收入箱的掉落实体组数，不是件数 |
| `%voidclear_sweep_last_duration%` | 上轮清扫耗时，秒，保留两位小数 |
| `%voidclear_trash_count%` | 垃圾箱实际物品件数 |
| `%voidclear_trash_stacks%` | 垃圾箱占用格数 |
| `%voidclear_trash_capacity%` | 垃圾箱格数上限（10000） |
| `%voidclear_trash_free_slots%` | 剩余格数，不代表还能放多少件，也不反映 64 MiB 字节上限 |
| `%voidclear_trash_clear_enabled%` | 是否开启自动清空 |
| `%voidclear_trash_clear_mode%` | 配置的清空模式 |
| `%voidclear_trash_clear_seconds%` | 清空剩余秒数，关闭时返回 0 |
| `%voidclear_trash_clear_time%` | 清空剩余时间，关闭时显示“未开启” |
| `%voidclear_trash_clear_at%` | 下次清空日期时间，关闭时显示“未开启” |

日期时间使用服务器时区，格式为 `yyyy-MM-dd HH:mm:ss`。可用 `/papi parse me %voidclear_trash_count%` 检查。不要用空位数或倒计时作为交易权限或容量校验。

## 存储

插件目录下的运行数据：`timer.yml`（倒计时）、`bin.db`（SQLite 垃圾箱，或改用 MySQL）、`history.log`（最近 1000 条操作记录）、`sweep-history/`（按日归档清扫日志；日统计合并归档和现存日志，支持查询最近 365 天。旧版已截断且未归档的记录无法恢复；归档失败时暂缓截断主日志）、`player-audit.log` / `.log.1`（各最多 4 MiB 的本服存取审计）、手动导出的 `sweep-stats.csv` 和 `diagnostics.txt`。审计日志与数据库中的交易恢复凭据用途不同。

手动确认收据清理还会生成 `receipt-backups/<玩家UUID>-<确认码>/`，保留原 `.dat`、存在的 `.dat_old` 及校验记录；不会自动清理这些备份，它们不构成可重放旧交易的证明。

每次写入都先锁定数据库版本、修改并提交，再移除地面实体或发放物品。版本号没变时复用上次提交的内容，变了才重新读整箱。当前源码中 SQLite 在操作线程执行；MySQL 在线写入由单线程后台队列依次执行，玩家/实体效果回所属线程复核，页面读取独立已提交快照。清扫按区块合并事务。MySQL 保留一条空闲连接复用，复用前检测连接是否可用。启动和停服需要数据库收尾，交易中的玩家 saveData 与收据刷盘仍同步；普通退出依赖原生存盘，并在后台核验收据后释放会话；异步候选验收状态见页首。

- 数据库提交与玩家、世界存档不是同一个事务，靠玩家操作日志、存档收据和清扫凭据在重启后对账。

### 已验证的强杀窗口

隔离测试服上，在数据库已提交、玩家或世界尚未保存时强杀，重启后：

| 后端 | 存入 | 领取 | 清扫 |
| --- | --- | --- | --- |
| SQLite | 箱 16 / 玩家 0 | 箱 0 / 玩家 16 | 箱 16 / 地面 0 |
| MySQL | 箱 16 / 玩家 0 | 箱 0 / 玩家 16 | 箱 16 / 地面 0 |

交易中途强杀（`player-cuts.cjs`）时：日志只到“准备”或“存储”阶段且缺少落盘收据的，拒绝该玩家登录；收据已落盘且来源链完整的，登录时自动完成确认，不重发物品。部分领取仅在剩余来源确实保存并可核对，或有完整终结凭据时允许续接；未保存余量继续隔离。

未覆盖：COMMIT 已执行但确认包丢失、断电或文件系统损坏、Folia 上强杀、拖动或关闭菜单等其他交易切点。
- 数据库损坏或记录超限时，插件会拒绝启动并请求正常停服，不会用空箱覆盖。

### SQLite

默认后端。`bin.db` 只能供单个服务端使用，不要让多个服务器同时写同一个文件。

### MySQL

```yaml
database:
  type: mysql
  mysql:
    jdbc-url: 'jdbc:mysql://数据库地址:3306/数据库名?useSSL=true&serverTimezone=UTC&characterEncoding=utf8'
    username: ''
    password: ''
    table-prefix: voidclear_
```

- 账号密码建议用环境变量 `VOIDCLEAR_DB_URL`、`VOIDCLEAR_DB_USER`、`VOIDCLEAR_DB_PASSWORD` 提供，不要写进配置文件。
- 表会自动创建，必须是 InnoDB，启动时会检查。
- 多服共享时，存入、领取和清空都会先锁定数据库最新状态并提交，再发放或移除物品。页面已过期时拒绝操作并刷新，物品留在背包里，可重新点击。
- 旧来源恢复包仍同步执行，单向 30 ms 延迟下菜单确认约 2.691 秒。当前在线 MySQL 路径把 JDBC 等待移到后台；后台确认耗时不能当作线程卡顿数据，历史延迟响应证据见性能优化与恢复报告。
- 切换 `database.type`、文件名或表前缀不是数据迁移，不要删除原数据库。

### 交付异常证据

玩家物品交付异常时，菜单会锁定，并把 UUID、失败步骤和物品快照写入 `plugins/VoidClear/delivery-failures/*.properties`。文件含完整物品数据，应限制访问。它只是核查证据，**不是补发凭证**：失败的步骤可能已经部分生效，禁止自动回放或照快照补发。进程被杀或断电时不会写入。

### 旧 YAML 迁移

旧版 `trash.yml` 会在 SQLite 中连同迁移凭据一起原子导入，成功后源文件改名为 `.migrated`。如果数据库非空且同时存在未迁移的旧文件，插件会拒绝启动。此时先正常停服、完整备份（含 `bin.db-wal` / `bin.db-shm`），人工核对后再处理；不要删除凭据、清库或重复导入。

## 备份与恢复（单服）

1. 用控制台 `stop` 正常关服，确认进程完全退出，并且没有“垃圾箱最终保存失败”的日志。不要强杀。
2. 把整个服务器目录复制到带日期的备份目录。至少要一起保存所有世界、玩家数据和整个 `plugins/VoidClear/`（含 `bin.db`、`bin.db-wal`、`bin.db-shm`、`timer.yml`）。不要在运行中单独复制 `bin.db`。
3. 恢复前同样停服，先把当前目录另存一份，再用同一时间点的完整备份恢复。不要把旧垃圾箱和新玩家背包混用。
4. 先在独立目录、独立端口试启动，检查日志、垃圾箱和玩家背包，确认无误后再开放生产服。

`timer.yml` 保存的是绝对截止时间：恢复旧备份后，过期的 interval 清空任务可能立即执行。需要先核对物品时，在首次启动前把 `trash.clear.enable` 改成 `false`。

MySQL 请用数据库自己的备份工具，并与各服的世界、玩家数据使用同一个停服备份点。

## 已知限制

- 崩溃安全只覆盖“已验证的强杀窗口”里列出的场景。
- SQLite 写入、交易中的玩家存档保存与刷盘仍同步；MySQL 在线 JDBC 已移到后台，启动与停服仍需数据库收尾。普通退出已取消插件额外存盘，原生存盘、世界刷盘与其他插件仍可能造成停顿。
- 玩家审计只在本服有限保留，异步写入可能缺记录，不能用作补发、恢复或“从未存取”的证明。
- 清扫凭据表（`*_sweeps`）只增不删，每清扫一个掉落物加一行，启动时会全部读取。长期运行的服务器启动时间会随之增加。
- 玩家交易日志（`*_player_ops`）也持续增长；本轮只加查询索引，没有安全归档或删除已完成记录。历史未确认交易仍须保留证据核查。
- 掉落物事件的凭据快速判断只索引本服写入的凭据。多个服务器共享 MySQL 且复制了同一个世界时，需要改回查库。

## 开发与自检

`mvn test` 不运行任何测试，项目没有 JUnit；自检是普通 `main` 加 `assert`。运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\check.ps1
```

它会离线编译，然后用 `java -ea` 运行 `check.ps1` 显式列出的检查入口及根目录的 `SelfCheck`、`ConfigCheck`。新增离线 `*Check` 时须加入该列表，不能只靠编译判断已运行。任何一项失败都会以非零退出。`TrashMysqlCheck` 需要真实 MySQL，不在脚本里，需要手动运行。这些是离线检查，不等同于实服验收。

### 集成测试

```powershell
powershell -ExecutionPolicy Bypass -File integration-tests/run.ps1
```

- 依赖同级目录 `SimpleFly/test-server` 的 Paper 1.21.7 运行时、`SimpleFly/integration-tests` 的协议库，以及 `minecraft-fakeplayer-next-master/lib/PlaceholderAPI-2.11.6.jar`，不会自动安装。
- 测试服默认位于 `target/live-server`，只监听 `127.0.0.1:25652`，每次会清空测试箱和假人背包。首轮测试应设置 `VOIDCLEAR_LIVE_DIR` 为新的隔离目录（相对于 `integration-tests/`，例如 `../target/live-maintenance-1109b`）；复用故障测试目录可能因历史未确认交易拒绝登录，不要删记录解锁。
- 用两个真实协议客户端执行菜单操作，结果写入 `integration-tests/results.json`。
- 多服与 Folia 专项测试见 `integration-tests/platforms.cjs` 和 `integration-tests/PLATFORMS.md`。
- 可通过环境变量 `VOIDCLEAR_TEST_JAR` 指定要测试的 jar（路径相对于 `integration-tests/`）。

诊断、存取审计、跨服、Folia、多人负载与故障专项的环境和运行方法集中维护在[平台测试说明](integration-tests/PLATFORMS.md)。历史包装脚本示例中的候选名不代表当前包；验收前须核对实际受测 JAR 的 SHA256，并使用新的隔离目录和报告名。

### 目录

- `src/`：源码与离线自检。
- `integration-tests/`：实服测试脚本和最新结果。
- `archive/reports/`：历次评估、修复和韧性验证报告。
- `archive/integration-results/`：历次测试结果。
- `archive/jars/`、`archive/logs/`：历史 jar 与构建日志（不进 git）。

### 核心调用链

- 清扫：`VoidClearPlugin` → `Sweeper` → `Decide` 判定 → `TrashStore` 事务入箱 → `SweepRecovery` 更新来源实体。
- 菜单：`CommandHandler` → `TrashListener` 计划存取 → `TrashStore` 提交 → `PlayerTransfers` 验证玩家存档收据、确认或隔离。
- 退出：原生退出事件写入本次收据 → `PlayerTransfers.retireWhenDisconnected` 等连接离开 → 后台核验实际存档并刷盘 → `retireAfterQuit` 核对数据库后释放会话。MySQL 核验使用有序数据库队列，SQLite 使用异步调度；在线玩家或旧会话的回调不得释放新连接。
- `TrashStore.transaction` 同时用于 SQLite / MySQL，MySQL 在线调用经 `DatabaseTasks` 有序提交，已提交只读快照用于菜单/PAPI，另有远端刷新。没有后台整表覆盖或冲突合并。垃圾箱的 YAML 仅用于一次性旧数据导入，没有可写 YAML 存储后端；配置与计时器仍使用各自的 YAML 文件。

## 致谢

本项目参考了 [ClearEntity](https://github.com/Summer-Tail/ClearEntity)（Kotori0629 / MrLv0816）的设计思路：定时清扫、实体黑白名单。代码为重新编写。
