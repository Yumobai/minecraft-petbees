# 构建与测试

需要 JDK 25。项目自带 Gradle Wrapper，首次构建需要联网下载 Gradle、Minecraft 和 Fabric 依赖。

1. 将 JAVA_HOME 指向 JDK 25。
2. 从 Jade 的官方发布页获取适用于 Minecraft 26.2 的 Fabric 版 Jade 26.2.11，将文件保存为项目内的 `libs/jade.jar`。此依赖仅用于编译；本仓库不再分发第三方模组 Jar。
3. Windows 运行 `gradlew.bat build`；Linux/macOS 运行 `sh gradlew build`。
4. 成品在 `build/libs/`。安装不带 `-sources` 后缀的 Jar，服务端与客户端都需要安装本模组及对应版本的 Fabric API。

构建会执行 Minecraft GameTest。测试代码与脱敏回归样本不会打进成品模组。

可选兼容测试：把合法获取的 Jade 26.2.11、Lithium 0.25.3、Carpet 26.2+v260616 和 GCA 2.12.7 对应 26.2 版本放进 `compat-mods/`，执行 `gradlew.bat build -Pcompat`。使用独立测试世界，不要把自己的服务器存档放入构建目录。

`libs/`、`compat-mods/`、构建缓存和输出均被 Git 忽略。提交前请检查 `git status`，避免提交日志、真实玩家数据或存档。
