# 存储填充 / Storage Fill

同一仓库管理两个 Minecraft 版本，模组 ID 均为 `storage_fill`。客户端与服务端需安装对应版本。

| Minecraft | 加载器 | 工程 |
| --- | --- | --- |
| 1.20.1 | Forge | [1.20.1-forge](1.20.1-forge/README.md) |
| 1.21.1 | NeoForge | [1.21.1-neoforge](1.21.1-neoforge/README.md) |

两个子目录分别保存源码、依赖、Gradle Wrapper 和构建配置。在目标版本子目录执行 `gradlew.bat test build`，产物位于该子目录的 `build/libs`。

1.20.1 使用 Java 17；1.21.1 使用 Java 21。联动版本、验证范围与限制见对应子工程说明。第三方 JAR、本地测试、运行目录和构建缓存不随仓库分发。不会自动部署到游戏 mods。

1.21.1 已完成 NeoForge 移植，保留 JEI 定向查询、配方填充和 FTB 联动；安装与验证范围见子工程说明。
