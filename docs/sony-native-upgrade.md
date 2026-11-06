# Sony XR-75X90K：原生库升级边界

版本查询截止 2026-11-05。本文是源码/接口核实记录，不是升级完成或真机兼容证书。

2026-11-06 实际安装时，Sony电视明确拒绝仅含`arm64-v8a`的rc.1；用户核对此前可安装包文件名含`armeabi`。因此此前“ARM64可安装”的判断被实际设备行为推翻：该电视的Android应用环境按`armeabi-v7a`处理（即使芯片本身可能是64位）。rc.2同时提供两个独立ABI APK，Sony目标使用armeabi-v7a；仍未取得电视当前Android API/固件、codec/HDR能力或ADB数据。

## 可复核现状

[本地二进制清单](sony-local-binary-inspection.json)记录 4 个 JAR/AAR、14 个 SO 的大小和 SHA256。14 个独立 SO 的 ELF class/machine 与所在 ARM64/ARMv7 目录相符。哈希和 ELF 头仅证明文件身份/架构，不证明来源、许可证、完整 ABI 或可运行。

归档未在顶层发现名称包含 LICENSE/NOTICE 的条目，不等于相关软件没有许可证；嵌套归档及源码许可证仍需核实。

| 组件 | 已发现的接口/版本线索 | 新版路线 | 当前状态 |
| --- | --- | --- | --- |
| IJK 三库 | `libijkffmpeg.so` 含 `TVBoxOSC Ffmpeg4.0 20230802`；Java 声明 `native_setReqLevel`、`native_setDot`，当前 SO 也含对应注册名 | 找回定制源码和编译选项，或将定制接口移植到可追溯 fork，再成套编译 `ijkffmpeg/ijkplayer/ijksdl` | 不能用上游 k0.8.8 或最新 FFmpeg 同名 SO 直接覆盖；精确来源 commit 未确认 |
| Media3 FFmpeg AAR | `androidx.media3.decoder.ffmpeg.*` 与对应 JNI；native 版本线索 `n4.2.9-4-gd7beb0c61f` | 从匹配 Media3 版本的官方 decoder_ffmpeg 模块重编 Java/JNI/AAR | 官方 1.11.1 文档推荐 FFmpeg 6.0，不代表支持全部新版 FFmpeg |
| nextlib | 当前 JitPack 坐标 0.7.1，使用 `NextRenderersFactory` | 新 Maven Central 坐标 `io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.17.0`，对应 Media3 1.11.1 | 旧坐标 metadata 的 0.8.4 不是整个项目的最新发布；新候选需 min23、API/ABI配套和加载验证 |
| QuickJS | 当前 wrapper 含 QuickJS `2021-03-27` 线索；evaluate/execute 带自定义模块参数 | 可追溯 wrapper 3.2.3 与更新 QuickJS 引擎分别评估，迁移 Java/JNI/模块加载、重新生成字节码缓存 | 上游 wrapper JNI 签名不同，不能只替换 SO；不能把 wrapper 发布号当作引擎版本 |
| 迅雷 | `thunder.jar`、`libxl_thunder_sdk.so`、`libxl_stat.so`，应用调用 XLTaskHelper/XLDownloadManager | 供应方提供带版本、授权、SHA256、ABI和接口说明的成套 Java/native SDK | 未确认当前 JNI 对应的可信新版或源码，保留原件，不虚构升级 |
| P2P | `com.p2p.P2PClass` 的 `XGFilm*`、`dox*` JNI 与 libp2p.so | 取得供应方可追溯 SDK/源码后迁移 | 未找到可验证来源；不能用别的下载器或删功能冒充升级 |
| Conscrypt | 原 Maven 2.5.2 | 2.9.0 AAR 已静态核实 min21、含 ARM64/ARMv7 | 已解析、打包，API36 ARM64模拟器冷启动日志确认native加载成功；Sony未测 |
| XWalk | shared-library 23.53.589.4 是 Java桥；运行时另外下载 | 已补实际 DEX 的只读发布及安全失败路径 | 安全加载修正不是 Chromium 引擎升级，运行时来源/维护问题仍在 |

## 不可混淆的版本

原生核实发现的 FFmpeg 官网稳定候选为 **9.0.2**（2026-09-18）。上游 IJK 构建脚本仍指向 ff4.0；Media3 1.11.1 的官方说明仍推荐 6.0；nextlib 新正式标签的源码 pin 为 FFmpeg **9.0.1**、mbedTLS **3.6.7**、dav1d **1.5.4**。它们并非同一个兼容组合，不能把 nextlib 更新理解为 IJK 的 FFmpeg 已更新。

这些是上游页面/源码核实结果，尚未在本项目重编或运行。若把 pin 改为 FFmpeg 9.0.2，还需要 API 迁移、原生编译和受控解码测试，不可仅改数字。

本仓库未发现上述 IJK、QuickJS、P2P、迅雷二进制对应的 C/C++/CMake/Android.mk 源码或子模块。更新 AGP/NDK 不会重新编译无源码的预编译库。

## 验收要求

- Java native 声明、JNI 注册/导出、关联 SO、codec/protocol 编译选项一致。
- 来源标签/commit、归档摘要、构建工具与完整许可证/版权通知可追溯。
- 保留当前协议、音轨、字幕、硬解、回退与用户偏好，不通过删功能达标。
- 每个APK只含单一ABI：Sony使用armeabi-v7a，另提供arm64-v8a；逐包校验重复SO、ELF class/machine、链接依赖与最低API。16KB页兼容主要影响64位native，仍需真实设备/工具链验证；文件名本身不是架构证明。
- 新库实际加载与受控媒体行为分别测试。当前不播放真实影片；未做的运行验证必须明确标未验证。

## 上游证据入口

- [FFmpeg 官方下载与稳定版本](https://ffmpeg.org/download.html)
- [IJK 官方 README](https://github.com/bilibili/ijkplayer/blob/master/README.md)、[FFmpeg pin](https://github.com/bilibili/ijkplayer/blob/master/init-android.sh)、[k0.8.8 Java 接口](https://github.com/bilibili/ijkplayer/blob/k0.8.8/android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java)
- [Media3 1.11.1 FFmpeg 构建说明](https://github.com/androidx/media/blob/1.11.1/libraries/decoder_ffmpeg/README.md)
- [nextlib 正式标签](https://github.com/anilbeesetti/nextlib/releases/tag/v1.11.1-0.17.0)、[新坐标元数据](https://repo.maven.apache.org/maven2/io/github/anilbeesetti/nextlib-media3ext/maven-metadata.xml)、[native pins](https://github.com/anilbeesetti/nextlib/blob/v1.11.1-0.17.0/ffmpeg/setup.sh)、[minSdk 配置](https://github.com/anilbeesetti/nextlib/blob/v1.11.1-0.17.0/media3ext/build.gradle.kts)
- [QuickJS 引擎](https://bellard.org/quickjs/)、[wrapper 3.2.3](https://github.com/HarlonWang/quickjs-wrapper/releases/tag/3.2.3)、[JNI 接口](https://github.com/HarlonWang/quickjs-wrapper/blob/3.2.3/wrapper-java/src/main/java/com/whl/quickjs/wrapper/QuickJSContext.java)
- [迅雷官方 SDK](https://open.xunlei.com/index.html)、[官方文档](https://open.xunlei.com/doc)
