# VoidClear · 虚空清理

[![Build](https://github.com/xingxing2024/VoidClear/actions/workflows/build.yml/badge.svg)](https://github.com/xingxing2024/VoidClear/actions/workflows/build.yml)

**Minecraft 实体清理与公共资源回收插件，适用于生存服、空岛服与多子服网络。**

VoidClear 将定时清扫与公共垃圾箱结合起来：管理员设置清扫时间、世界范围和实体规则，插件按计划处理目标，将可回收的掉落物集中存入公共库存。玩家通过游戏菜单重新领取，让可用资源继续参与服务器里的下一次建造。

[使用 Wiki](https://github.com/xingxing2024/VoidClear/wiki) · [安装与升级](https://github.com/xingxing2024/VoidClear/wiki/%E5%AE%89%E8%A3%85%E4%B8%8E%E5%8D%87%E7%BA%A7) · [配置说明](https://github.com/xingxing2024/VoidClear/wiki/%E9%85%8D%E7%BD%AE%E6%80%BB%E8%A7%88) · [反馈问题](https://github.com/xingxing2024/VoidClear/issues)

## 核心功能

### 按计划清扫

支持按时间间隔或每天固定时刻执行。清扫前通过聊天倒计时、Boss 条、ActionBar 与音效提醒玩家，各种提示可以分别配置。倒计时会保存，重启后继续计时。

### 按规则决定清什么、留什么

按世界和实体类别控制清扫范围，组合实体白名单、保留名单、清理名单与保护标记。支持实体 ID 和分类匹配，可为不同世界设置规则；管理员用 `/vc check` 查询判定依据，用 `/vc keep` 保护指定实体。

### 公共垃圾箱

可回收的地面物品进入公共库存，玩家输入 `/vc trash` 即可打开菜单，自由存入和领取。菜单布局、翻页按钮与提示文字可定制；容量不足时，未能入箱的物品留在世界中。可另行开启垃圾箱定时清空，默认关闭。

### 单服存储与跨服共享

单服可直接使用 SQLite；多个子服可连接同一个 MySQL 数据库和表前缀，共享公共垃圾箱。公共库存共享与玩家背包同步分别配置，子服须使用相同插件版本。

### 日常管理与问题排查

查询清扫历史、每日趋势与本服玩家存取记录，导出 CSV 或诊断报告。诊断包含后台任务、会话与未完成交易状态，方便管理员结合服务端日志定位问题。

### 可选世界保护

支持玩家与生物踩踏农田、海龟蛋的保护，以及指定爆炸实体对方块的破坏保护。苦力怕保护不会取消爆炸伤害。

## 运行环境

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper / Folia 1.21.4+ |
| Java | 21+ |
| 存储 | SQLite / MySQL |
| 可选依赖 | PlaceholderAPI，用于向其他插件提供变量 |

当前源码版本为 **3.0.0 测试候选版**，先在测试服验证配置与存取流程。

## 快速开始

1. 正常停服并备份，将插件 JAR 放入 `plugins/`。
2. 启动生成 `config.yml`、`messages.yml` 和 `menus/bin.yml`。
3. 设置清扫规则和数据库。普通配置修改后执行 `/vc reload`，数据库连接修改后重启。
4. 用 `/vc check minecraft:zombie world` 检查规则，执行 `/vc sweep`，再通过 `/vc trash` 查看回收结果。

首次启动需要下载 JDBC 驱动。清扫只遍历已加载区块，不扫描整个离线世界。

## 常用指令

主命令 `/voidclear`，别名 `/vc`。玩家使用公共垃圾箱无需管理员权限；管理操作需要 `voidclear.admin`，默认 OP 拥有。

| 指令 | 用途 |
| --- | --- |
| `/vc trash` | 打开公共垃圾箱 |
| `/vc sweep` | 立即发起一轮清扫 |
| `/vc check <实体> [世界]` | 查询清理规则与判定依据 |
| `/vc keep` / `/vc unkeep` | 保护或取消保护目标实体 |
| `/vc status` | 查看倒计时与上轮结果 |
| `/vc diagnose [export]` | 查看或导出诊断 |
| `/vc audit` | 查询本服玩家存取记录 |
| `/vc reload` | 重载普通配置 |
| `/vc help [1-3]` | 查看完整帮助 |

完整参数、统计导出与维护操作见 [指令与权限](https://github.com/xingxing2024/VoidClear/wiki/%E6%8C%87%E4%BB%A4%E4%B8%8E%E6%9D%83%E9%99%90)。

## Wiki 文档

| 文档 | 内容 |
| --- | --- |
| [安装与升级](https://github.com/xingxing2024/VoidClear/wiki/%E5%AE%89%E8%A3%85%E4%B8%8E%E5%8D%87%E7%BA%A7) | 构建、首次安装、版本升级与重载 |
| [配置总览](https://github.com/xingxing2024/VoidClear/wiki/%E9%85%8D%E7%BD%AE%E6%80%BB%E8%A7%88) | 时间、批次、提醒及配置文件分工 |
| [清扫规则](https://github.com/xingxing2024/VoidClear/wiki/%E6%B8%85%E6%89%AB%E8%A7%84%E5%88%99) | 世界与实体名单、保护标记、掉落物豁免 |
| [公共垃圾箱](https://github.com/xingxing2024/VoidClear/wiki/%E5%85%AC%E5%85%B1%E5%9E%83%E5%9C%BE%E7%AE%B1) | 存取、菜单定制与定时清空 |
| [数据库与跨服](https://github.com/xingxing2024/VoidClear/wiki/%E6%95%B0%E6%8D%AE%E5%BA%93%E4%B8%8E%E8%B7%A8%E6%9C%8D) | SQLite、MySQL、多服共享与数据维护 |
| [指令与权限](https://github.com/xingxing2024/VoidClear/wiki/%E6%8C%87%E4%BB%A4%E4%B8%8E%E6%9D%83%E9%99%90) | 玩家和管理员命令 |
| [占位符](https://github.com/xingxing2024/VoidClear/wiki/%E5%8D%A0%E4%BD%8D%E7%AC%A6) | PlaceholderAPI、菜单变量及数量口径 |
| [常见问题](https://github.com/xingxing2024/VoidClear/wiki/%E5%B8%B8%E8%A7%81%E9%97%AE%E9%A2%98) | 启动、规则、回收数量和交易排查 |

默认配置含逐项注释：[config.yml](src/main/resources/config.yml) · [messages.yml](src/main/resources/messages.yml) · [菜单配置](src/main/resources/menus/bin.yml)。

## 构建与自检

```shell
mvn package
```

生成 `target/VoidClear-3.0.0.jar`。也可从成功的 [GitHub Actions 构建](https://github.com/xingxing2024/VoidClear/actions/workflows/build.yml)下载产物。

Windows PowerShell 离线自检：

```powershell
powershell -ExecutionPolicy Bypass -File ./check.ps1
```

首次先完成联网构建和依赖解析；自检脚本使用本地 Maven 缓存。自检为普通 Java 入口加 assert，`mvn test` 不会自动执行；真实 MySQL 检查需单独准备隔离环境。

## 数据维护

正常停服后，将数据库、世界、玩家存档和插件数据作为同一备份点保存与恢复。启动或交易核对失败时保留数据与日志排查，不删除凭据解锁。插件启动核对失败时会请求停止服务器，不支持带玩家热启用或热卸载。

## 致谢

参考了 [ClearEntity](https://github.com/Summer-Tail/ClearEntity) 的定时清扫与实体名单设计思路，代码重新编写。
