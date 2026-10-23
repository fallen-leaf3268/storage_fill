# 存储填充 / Storage Fill

Minecraft 1.21.1 NeoForge 版本，模组 ID 为 `storage_fill`，需要客户端与服务端同时安装。

在 JEI 配方转移按钮下方点击放大镜，只查询当前配方需要的网络材料。查询另一配方时覆盖上一份结果，关闭 JEI 时释放；打开界面不会扫描整仓。点击填充时实时核对，背包和输入槽优先，Shift 按原生规则尽量填满。无法确认消耗规则的第三方处理器不启用批量补料。

个人来源顺序位于 `config/storage_fill-client.toml` 的 `recipeFill.sourceOrder`，默认超越维度、RS、AE2、精妙背包；修改后重新查询生效。

安装 FTB Quests 后，支持网络入库触发非消耗物品任务检测、从网络补充手动提交、物品奖励去向选择。奖励单选目标，剩余部分回到背包。精妙背包中的带组件奖励不与已有物品合并，仍可放入空槽。检测保留原生任务前置、冷却和自动领奖防重规则。

## 依赖基线

需要 Java 21、NeoForge 21.1.262 和 JEI 19.64.0.455。这是本次移植使用的基线，不适用于 Forge 或 Minecraft 1.20.1。

| 可选联动 | 对照版本 |
| --- | --- |
| FTB Quests / Library / Teams | 2101.1.36 / 2101.1.37 / 2101.1.11 |
| Architectury | 13.0.8 |
| 超越维度 | 0.7.30 |
| Refined Storage | 2.0.9 |
| RS Quartz Arsenal | 1.0.8 |
| RS Curios Integration / Curios | 1.0.0 / 9.5.1+1.21.1 |
| Applied Energistics 2 / GuideME | 19.2.18 / 21.1.20 |
| Sophisticated Backpacks / Core | 3.26.14.2222 / 1.5.17.2426 |
| Legendary Inventory | 1.2.4 |

RS 的无线合成终端使用 1.21.1 的 Quartz Arsenal 接口。无线访问遵守网络的原生范围、权限和能量要求。可选联动未安装时不显示对应奖励选项。

## 构建

将上述依赖的官方发行 JAR 放入本目录的 `libs`，使用 Java 21 执行 `gradlew.bat test build`。产物为 `build/libs/storage_fill-1.21.1-neoforge-1.0.0.jar`，不会自动部署到游戏。

第三方 JAR、本地测试与探针、测试世界和构建缓存不随源码仓库分发。单元测试与实际游戏测试分开启动；本机完整联动回归使用 `gradlew.bat runGameTestServer -PgameTests -PwithStorage -PwithFtb -PwithCurios -PwithRsAddons -PwithLegendary`。

## 验证范围

上述基线下，81 项单元测试与完整联动服务端 260 项 GameTest 全部通过，无跳过，包含原生 RS、AE2、超越维度、精妙背包及 FTB 提交、奖励、解锁与重复任务。缺少所有可选联动的服务端组合也通过加载与可执行基础用例，依赖缺席的联动用例按预期跳过。

实际客户端已验证 JEI 查询切换、关闭释放、工作方块不匹配时隐藏、普通/Shift 填充、回执及物品守恒；FTB 原生任务书中 5 个奖励去向的实际按钮、菜单、单选与服务端回包均通过。测试覆盖固定依赖基线，不代表所有第三方整合包与其他版本都已逐一验证。
