# BandSync 工程 · gradle wrapper 说明

本工程用 Android Studio 打开即可（菜单 File → Open → 选 `D:\AI-Projects\band-sync`），
Studio 会识别 `gradle/wrapper/gradle-wrapper.properties`（Gradle 8.2 + AGP 8.1.4）并自动下载依赖。

若 Studio 提示缺少 `gradle-wrapper.jar`：
- 最简单：用 Studio 新建任意一个空项目，把它的 `gradle/wrapper/gradle-wrapper.jar`
  复制到本工程的 `gradle/wrapper/` 目录，再 Open 本工程即可。
- 或让 Studio 在打开时自动补齐 wrapper（一般会自动处理）。

本机（Dell）未安装 Android SDK / Studio，故源码仅做了静态检查（括号配平、引用完整），
未执行真实编译。真机运行与协议验证需在装有 Studio 的机器/手机上进行。
