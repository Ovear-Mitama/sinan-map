# Sinan Map（司南地图）

Minecraft 地图模组：小地图、全屏地图、路径点，以及生物雷达。

## 支持的版本

| Minecraft | 加载器 | 状态 |
|---|---|---|
| 26.1.2 | Fabric / NeoForge | 当前开发版本 |

Fabric 与 NeoForge 是两个独立工程，各自构建、各自发布，没有跨加载器的抽象层。以后加新版本时在上表补一行即可；具体依赖版本写在根目录和 `neoforge/` 的 `gradle.properties` 里。

## 功能

- 小地图：缩放、锁定正北、地表与洞穴层切换、生物雷达
- 全屏地图：平移缩放、维度切换、区块网格、史莱姆区块、方位字母
- 路径点：新建、分组、传送、死亡点记录，传送石碑自动同步；只保存在本机
- 多人：服务端装了司南时，同意共享位置的玩家可以互相显示在地图上

默认按键：`M` 世界地图，`U` 路径点管理器，`B` 新建路径点，`=` / `-` 缩放小地图。

## 安装

按对应加载器的常规方式安装：

- Fabric：Fabric Loader + Fabric API
- NeoForge：NeoForge

需要 Java 25（Minecraft 26.1 起的运行基线）。把 jar 放进 `mods` 目录，不要同时装两个加载器的版本。Fabric 版也可以直接在 Quilt Loader 上运行。

## 构建

需要 JDK 25，Gradle 用仓库自带的 wrapper，本机不用另装。Gradle 本身也必须跑在 JDK 25 上：命令行先设置 `JAVA_HOME`，IDE 里把 Gradle JVM 选成 25。

Fabric（仓库根目录就是 Fabric 工程）：

```bash
./gradlew build
./gradlew runClient
```

Sinan Lib（核心逻辑，独立构建；两端构建时会自动先编它）：

```bash
./gradlew -p lib build
./gradlew -p lib test
```

NeoForge（独立工程）：

```bash
./gradlew -p neoforge build
./gradlew -p neoforge runClient
```

Windows 下把 `./gradlew` 换成 `.\gradlew.bat`。

## 目录结构

| 目录 | 内容 |
|---|---|
| `lib/` | Sinan Lib：坐标换算、配色、层判定、路径点模型、存储格式。不引用 Minecraft 或加载器类型 |
| `shared/` | 网络协议。用到 Minecraft 的类型，由两个工程共同编译 |
| `src/` | Fabric 工程与客户端实现 |
| `neoforge/` | NeoForge 工程 |

`lib/` 有自己的版本号（见 `lib/gradle.properties`），与模组版本分开。打包时它的类会并进模组 jar，玩家仍然只装一个文件。

## 测试

单元测试在 `lib/src/test/java`：

```bash
./gradlew -p lib test
```

目前覆盖坐标换算、地图视口换算、路径点模型、存档格式等纯逻辑；Fabric 与 NeoForge 工程本身没有单独的测试。

## 许可

项目保留所有权利，完整条款见 [LICENSE](LICENSE)。可以游玩、备份、原样随整合包分发（含商业整合包和盈利服务器），在服务器、直播、视频里使用并获利；也可以基于公开接口做独立附加组件，许可随意（含闭源、商业），不需要申请。`assets/sinanmap/` 下的美术资源不受限制。

拿本模组本身卖钱（例如放在付费墙后或作为商品出售）需要事先取得书面许可。修改后分发（补丁 jar、修改版 jar）、把源码或资源并入其他项目、冒充官方都不允许。署名默认不要求；只有复制、移植或实质性参考源码，或者作品依赖本模组源码时，才需要保留声明并注明来源。

项目为独立实现，不含 JourneyMap、Xaero's Minimap、VoxelMap 等模组的代码或资源。

## 参与贡献

提问题、建议或代码之前，请先读 [CONTRIBUTING.md](CONTRIBUTING.md)，里面写了 issue 需要的信息和 PR 的入站授权说明。
