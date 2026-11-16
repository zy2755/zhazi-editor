# 构建说明 / Building

Windows / macOS / Linux 均可构建。要求：**JDK 17**、**Android SDK（platform 35）**、Git。
本文所有命令均在本项目实际验证过。

## 1. 准备环境

- **JDK 17**（AGP 8.7 的硬性要求，JDK 8/11/21 不行）：
  ```bash
  java -version   # 应显示 17.x
  ```
  没有就装 [Temurin 17](https://adoptium.net/) 或 Android Studio 自带的 JBR。
- **Android SDK**：装 [Android Studio](https://developer.android.com/studio) 最省事；或只用
  [cmdline-tools](https://developer.android.com/studio#command-line-tools-only)：
  ```bash
  sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
  ```
- **SDK 位置**（二选一）：
  - 环境变量 `ANDROID_HOME` 指向 SDK 目录；或
  - 在项目根目录建 `local.properties`（已 gitignore）写：
    ```properties
    sdk.dir=C:/path/to/Android/Sdk
    ```

## 2. 获取源码

```bash
git clone https://github.com/zy2755/zhazi-editor.git
cd zhazi-editor
```

首次执行 `./gradlew` 会自动下载 Gradle 8.13 与依赖。
[settings.gradle.kts](settings.gradle.kts) 已配置**阿里云 Maven 镜像**（国内网络友好）；
海外网络可删掉其中两行 `maven("https://maven.aliyun.com/...")` 走官方源。

## 3. Debug 构建

```bash
./gradlew assembleDebug          # Windows 用 gradlew.bat 或 bash gradlew
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 9.5 MB，通用包）。

装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 4. 单元测试

```bash
./gradlew :app:testDebugUnitTest
```

- 报告：`app/build/reports/tests/testDebugUnitTest/index.html`
- docx 引擎（`docx/` 包）是纯 Kotlin，测试跑在**桌面 JVM**，不需要模拟器/设备

## 5. Release 构建（不签名）

```bash
./gradlew assembleRelease
```

未配置签名（见下节）时产出 `app/build/outputs/apk/release/app-release-unsigned.apk`，
自己装的话需先签名，或直接用 Debug 包。

## 6. Release 构建（签名，发布用）

**第 1 步：生成签名密钥**（放在仓库外，⚠️ 务必备份——以后所有更新包必须同签名）：

```bash
keytool -genkeypair -v -keystore ~/.android/zhazi-release.jks -alias zhazi \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass <你的密码> -keypass <你的密码> \
  -dname "CN=zhazi-editor, OU=ZhaZi, O=zy2755, C=CN"
```

**第 2 步**：在项目根目录 `local.properties`（已 gitignore，绝不入库）写：

```properties
zhazi.storeFile=C:/Users/<你>/.android/zhazi-release.jks
zhazi.storePassword=<你的密码>
zhazi.keyAlias=zhazi
zhazi.keyPassword=<你的密码>
```

**第 3 步**：构建并验证：

```bash
./gradlew assembleRelease
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

**第 4 步**：发布——把 APK 复制为 `zhazi-editor-v<版本>.apk`，
在 GitHub 上创建 Release（tag 与 `versionName` 一致，如 `v0.1.0`）挂上。
签名证书指纹记录在 Release 说明里，便于核对。

## 7. 示例文档

```bash
python tools/gen_sample.py    # 生成 sample/示例.docx（测试与演示用）
```

## 8. 常见问题

| 现象 | 处理 |
| --- | --- |
| `SDK location not found` | 见第 1 节"SDK 位置"，建 `local.properties` 或设 `ANDROID_HOME` |
| `Unsupported class file major version` / 提示需要 JDK 17 | `JAVA_HOME` 指向了旧 JDK，切到 17 |
| 依赖下载失败 | 国内网络保留阿里云镜像即可；镜像站偶发抖动可重试或换 [腾讯镜像](https://mirrors.cloud.tencent.com/nexus/repository/maven-public/) |
| Windows 下大量 `LF will be replaced by CRLF` 警告 | 换行符提示，无碍构建 |
| 首次构建很慢 | Gradle 发行版 + 依赖首次下载，正常；之后有缓存 |
| `apksigner: command not found` | 用 SDK 里 `build-tools/35.0.0/` 下的完整路径（Windows 是 `apksigner.bat`） |
