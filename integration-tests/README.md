# 实服集成测试

本目录保留开发时使用的测试脚本与探针源码，不包含测试结果、服务端 JAR、世界、数据库或运行时缓存。

脚本依赖隔离的本地 Paper/Folia、MySQL 和协议客户端环境；部分路径仍引用开发机相邻的 SimpleFly 等测试夹具，需要准备环境后再运行。MySQL 示例账号 `voidclear` 仅用于回环地址的隔离测试库，不是线上凭据。具体场景与端口见 [PLATFORMS.md](PLATFORMS.md)。

普通源码构建与离线自检不需要这些实服夹具：在仓库根目录执行 `mvn package` 和 `powershell -ExecutionPolicy Bypass -File ./check.ps1`。
