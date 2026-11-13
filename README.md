# VoidClear

Minecraft 实体清理与公共垃圾箱插件。支持 Paper / Folia 1.21.4+，需要 Java 21+。

## 功能

- 按间隔或每日固定时间清扫，支持倒计时、Boss 条和音效。
- 按世界、实体名单和保护标记控制清扫范围。
- 回收掉落物到公共垃圾箱，玩家可存入和领取；容量不足时剩余物品留在世界中。
- SQLite 单服存储，或通过 MySQL 共享跨服公共库存。
- 清扫历史、玩家存取审计与运行诊断。
- 可选农田防踩踏和苦力怕爆炸方块保护。

## 构建与安装

运行 `mvn package`，生成 `target/VoidClear-3.0.0.jar`。

1. 停服备份，将 JAR 放入 `plugins/`。
2. 启动生成 `plugins/VoidClear/config.yml`、`messages.yml` 和 `menus/bin.yml`。
3. 设置清扫规则与数据库连接。
4. 普通配置修改后执行 `/vc reload`；数据库连接修改后重启服务器。

首次启动会下载 JDBC 驱动，需要能访问依赖仓库。共享 MySQL 的所有子服须使用相同插件版本；公共库存共享不包含玩家背包同步。

## 常用指令

主命令 `/voidclear`，别名 `/vc`。普通玩家可打开垃圾箱，管理操作需要 `voidclear.admin` 权限。

| 指令 | 功能 |
| --- | --- |
| `/vc help [1-3]` | 查看完整帮助 |
| `/vc trash` | 打开公共垃圾箱 |
| `/vc sweep` | 执行清扫 |
| `/vc check <实体> [世界]` | 查询清理规则 |
| `/vc keep` / `/vc unkeep` | 保护或取消保护目标实体 |
| `/vc status` | 查看运行状态 |
| `/vc diagnose [export]` | 查看或导出诊断 |
| `/vc audit` | 查询本服玩家存取记录 |
| `/vc history` / `/vc trend` | 查看清扫记录与统计 |
| `/vc export [天数]` | 导出 CSV |
| `/vc reload` | 重载配置 |

永久清空和旧收据维护命令见管理员帮助。

## 配置与数据维护

完整字段说明见 [config.yml](src/main/resources/config.yml)。

- `timer` / `sweep`：清扫时间、批次与提醒。
- `rule` / `world` / `drop`：世界、实体和掉落物规则。
- `trash.clear`：垃圾箱自动清空，默认关闭。
- `database`：SQLite 文件或 MySQL 连接。

数据库连接可通过 `VOIDCLEAR_DB_URL`、`VOIDCLEAR_DB_USER`、`VOIDCLEAR_DB_PASSWORD` 环境变量配置。切换数据库类型或表前缀不会自动迁移数据。

正常停服后，将数据库、插件目录、世界和玩家存档作为同一备份点保存与恢复。交易核对失败时保留数据和日志排查，不要删库或删除凭据解锁。启动核对失败会请求停止整个服务器，不支持带玩家热启用或热卸载。

## 自检

在 Windows PowerShell 中运行：

```powershell
powershell -ExecutionPolicy Bypass -File ./check.ps1
```

自检采用普通 Java 入口和 assert，`mvn test` 不会自动执行这些检查。`TrashMysqlCheck` 需要隔离 MySQL 环境，不在离线自检中执行。

## 致谢

参考了 [ClearEntity](https://github.com/Summer-Tail/ClearEntity) 的定时清扫与实体名单设计思路，代码重新编写。
