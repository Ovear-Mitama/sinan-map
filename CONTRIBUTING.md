# 贡献指南

感谢愿意帮忙。提交 issue 或 PR 之前，请先读本文件。英文版见文末「English summary」。

--------------------------------------------------------------------------------------------------
一、提交 Issue
--------------------------------------------------------------------------------------------------

欢迎报 bug、提建议、请求新功能。请尽量写清：

- 加载器与版本（Fabric / NeoForge）、Minecraft 版本、本模组版本；
- 复现步骤、期望结果与实际结果；
- 相关日志（`logs/latest.log` 或崩溃报告）、截图或录像；
- 若与地图渲染有关，请注明：维度、高度、是否在洞穴层或水下、是否使用光影/资源包。

以下内容不属于本项目范围：其他模组的 bug、Minecraft 本体问题、要求把本模组改造成
另一个模组的复刻。

--------------------------------------------------------------------------------------------------
二、提交代码
--------------------------------------------------------------------------------------------------

### 2.1 授权（重要）

提交任何代码、文档或素材，即表示你同意：

1. 该贡献由你原创，或你已取得提交所需的全部授权；
2. 你授予项目作者**永久的、全球范围的、免版税的、可再许可（sublicensable）**的权利，
   以便作者在本项目中以任何方式（包括商业许可）使用、修改与分发你的贡献；
3. 该贡献合并后成为本项目的一部分，随本项目按 [LICENSE](LICENSE) 对外分发。

你保留你贡献部分的著作权；**Git 提交记录即是对你的署名**，不需要额外署名。

### 2.2 不得提交的内容

- 来自其他模组的**反编译产物**，或任何非本人原创的代码；
- 未经授权的贴图、模型、音效等素材；
- 与本次改动无关的大规模格式化、重命名或重构。

### 2.3 素材贡献

`assets/sinanmap/` 下的美术资源对外**不作限制**（可自由使用、修改、再分发，含商业用途）。
贡献素材即视为同意其按该条件分发。

### 2.4 代码要求

- 构建通过：先把 `JAVA_HOME` 指向本机的 **JDK 25**（路径因机器而异），再运行
  `.\gradlew.bat compileJava`（Windows）或 `./gradlew compileJava`（Linux / macOS）；
- 遵循既有目录结构与风格：共用逻辑放 `common/`，且**不得**引用 Minecraft 或加载器类型；
- 注释用中文，写"为什么"而不是复述代码；不要写"用户说……"这类对话记录；
- 新增行为尽量补一个单元测试（放 `common/test/`）。

### 2.5 PR 说明

请写清：改了什么、为什么、如何验证（截图/日志/测试），以及是否影响存档或配置格式。

--------------------------------------------------------------------------------------------------
三、交流约定
--------------------------------------------------------------------------------------------------

友善、就事论事。不接受人身攻击、催促未承诺的功能、或要求公开闭源内容。

--------------------------------------------------------------------------------------------------
English summary
--------------------------------------------------------------------------------------------------

Issues are welcome: please include your loader and versions (Fabric/NeoForge, Minecraft,
this mod), reproduction steps, expected vs. actual behaviour, logs and screenshots; for
map-rendering issues also mention the dimension, height, whether you are in a cave or
underwater, and whether shaders/resource packs are in use.

By submitting any code, documentation or asset you agree that (1) it is your original work
or you have all rights needed to submit it; (2) you grant the project author a perpetual,
worldwide, royalty-free and **sublicensable** licence to use, modify and distribute your
contribution within this project in any way, including under commercial licences; and (3) the
contribution becomes part of this project and is distributed under [LICENSE](LICENSE). You
keep the copyright to your contribution; the Git history is your credit.

Do not submit decompiled code from other mods, code you do not own, or unauthorised
textures/models/sounds. Art assets under `assets/sinanmap/` are distributed without
restriction (free to use, modify and redistribute, including commercially); contributing art
means you agree to that.

Code requirements: the build must pass (`.\gradlew.bat compileJava`), shared logic goes in
`common/` and must not reference Minecraft or loader types, comments are written in Chinese
and explain "why" rather than restating the code, and new behaviour should come with a unit
test under `common/test/`.

Conduct: be kind and stay on topic. Harassment, demanding unreleased features, or asking for
closed content to be published are not welcome.
