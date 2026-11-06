# Sony XR-75X90K 依赖升级记录

版本查询截止 **2026-11-05**。这是进行中的选择与验证清单，**不是所有依赖已最新/已兼容/已签名发布的声明**。

## 目标与状态

- 用户确认曾在该电视安装ARM64；最终目标为arm64-v8a/generic/normal，不改包名或固定签名。
- 电视当前Android API/固件尚未实测。型号资料不能冒充该设备当前系统；仍保持min21，不用overrideLibrary绕过min23/24要求。
- compile37、target34、AGP9.5.0、Gradle9.8.2、Kotlin2.4.21已进入真实构建；Gradle归档已按官方SHA256校验。
- legacy-kapt来自com.android.tools.build:gradle-kotlin:9.5.0；公开AndServer生成器、kapt、Kotlin/Java、R8和ARM64 normal生产签名Release均已实际执行成功。迁移中间态的receiver与OkHttp Kotlin/DNS编译错误已修正；最终产物身份和剩余验收见独立候选目录记录。
- 所有版本固定，Catalog不是lockfile。runtime图、锁和校验文件须以真实Gradle输出为准；首次获取的SHA256不是来源签名。
- Python默认不配置；其旧Chaquopy/Python源码保留但不声称已迁移。隔离须用任务图、runtime图和最终APK三层验证。

## 全部声明坐标

“快照声明”是审计时的值；“当前选择”来自生成本文时的Catalog。上游候选仅为该坐标查询结果，新坐标/弃用状态需结合说明。Last-Modified不是官方发布日期。

| 坐标 | 快照声明 | 当前选择 | 上游候选 | 决策/未完成项 |
| --- | --- | --- | --- | --- |
| `androidx.annotation:annotation` | 1.8.2 | 1.8.2 | [1.11.0](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.11.0/annotation-1.11.0.pom) | 最新 AndroidX 注解候选；随 AndroidX 同族解析后再锁定。 |
| `androidx.appcompat:appcompat` | 1.7.0 | 1.7.0 | [1.8.0](https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/1.8.0/appcompat-1.8.0.pom) | 候选 1.8.0 AAR min23，电视实际 API 门禁未解除。 |
| `androidx.constraintlayout:constraintlayout` | 2.2.2 | 2.2.2 | [2.2.2](https://dl.google.com/dl/android/maven2/androidx/constraintlayout/constraintlayout/2.2.2/constraintlayout-2.2.2.pom) | 已选 2.2.2；AAR min21，待整包回归。 |
| `androidx.lifecycle:lifecycle-extensions` | 2.2.0 | 2.2.0 | [2.2.0](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-extensions/2.2.0/lifecycle-extensions-2.2.0.pom) | 聚合包 2.2.0 已停止维护；需迁移实际使用的 ViewModel/LiveData 模块，不能将此号称作新 Lifecycle。 |
| `androidx.media3:media3-common` | 1.3.1 | 1.3.1 | [1.11.1](https://dl.google.com/dl/android/maven2/androidx/media3/media3-common/1.11.1/media3-common-1.11.1.pom) | 同族1.11.1候选min23；需nextlib/FFmpeg配套迁移，当前未升级。 |
| `androidx.media3:media3-datasource-rtmp` | 1.3.1 | 1.3.1 | [1.11.1](https://dl.google.com/dl/android/maven2/androidx/media3/media3-datasource-rtmp/1.11.1/media3-datasource-rtmp-1.11.1.pom) | 同族1.11.1候选min23；需nextlib/FFmpeg配套迁移，当前未升级。 |
| `androidx.media3:media3-exoplayer` | 1.3.1 | 1.3.1 | [1.11.1](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.11.1/media3-exoplayer-1.11.1.pom) | 同族1.11.1候选min23；需nextlib/FFmpeg配套迁移，当前未升级。 |
| `androidx.media3:media3-exoplayer-dash` | 1.3.1 | 1.3.1 | [1.11.1](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer-dash/1.11.1/media3-exoplayer-dash-1.11.1.pom) | 同族1.11.1候选min23；需nextlib/FFmpeg配套迁移，当前未升级。 |
| `androidx.media3:media3-exoplayer-hls` | 1.3.1 | 1.3.1 | [1.11.1](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer-hls/1.11.1/media3-exoplayer-hls-1.11.1.pom) | 同族1.11.1候选min23；需nextlib/FFmpeg配套迁移，当前未升级。 |
| `androidx.media3:media3-exoplayer-rtsp` | 1.3.1 | 1.3.1 | [1.11.1](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer-rtsp/1.11.1/media3-exoplayer-rtsp-1.11.1.pom) | 同族1.11.1候选min23；需nextlib/FFmpeg配套迁移，当前未升级。 |
| `androidx.media3:media3-ui` | 1.3.1 | 1.3.1 | [1.11.1](https://dl.google.com/dl/android/maven2/androidx/media3/media3-ui/1.11.1/media3-ui-1.11.1.pom) | 同族1.11.1候选min23；需nextlib/FFmpeg配套迁移，当前未升级。 |
| `androidx.multidex:multidex` | 2.0.1 | 2.0.1 | [2.0.1](https://dl.google.com/dl/android/maven2/androidx/multidex/multidex/2.0.1/multidex-2.0.1.pom) | 同坐标未发现更高稳定版；min21 已原生支持 multidex，移除依赖需一并迁 App 基类。 |
| `androidx.recyclerview:recyclerview` | 1.4.0 | 1.4.0 | [1.4.0](https://dl.google.com/dl/android/maven2/androidx/recyclerview/recyclerview/1.4.0/recyclerview-1.4.0.pom) | 已选 1.4.0；AAR min21/minCompile35。 |
| `androidx.room:room-compiler` | 2.6.1 | 2.6.1 | [2.8.5](https://dl.google.com/dl/android/maven2/androidx/room/room-compiler/2.8.5/room-compiler-2.8.5.pom) | 与 runtime 同版，禁止 destructive migration；暂未完成新版本迁移。 |
| `androidx.room:room-runtime` | 2.6.1 | 2.6.1 | [2.8.5](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/2.8.5/room-runtime-2.8.5.pom) | 候选 2.8.5 min23；Android平台变体、runtime/compiler 配套和旧 schema 非破坏迁移未验证。 |
| `com.alivc.conan:AlivcConan` | 0.9.5 | 0.9.5 | [1.0.5](https://maven.aliyun.com/repository/releases/com/alivc/conan/AlivcConan/1.0.5/AlivcConan-1.0.5.pom) | 1.0.5 AAR min14/ARM64；须与 Aliyun 配套验证，不单独盲升。 |
| `com.aliyun.sdk.android:AliyunPlayer` | 5.5.2.0-full | 5.5.2.0-full | [7.16.0-full](https://maven.aliyun.com/repository/releases/com/aliyun/sdk/android/AliyunPlayer/7.16.0-full/AliyunPlayer-7.16.0-full.pom) | 保留 full 功能族后候选为 7.16.0-full，不采用初版误选的 7.19.0-part；需 SDK API、授权/许可证和 native 配套验证。 |
| `com.android.tools.build:gradle` | 9.5.0 | 9.5.0 | [9.5.0](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.5.0/gradle-9.5.0.pom) | AGP 9.5.0 已实际配置运行；com.chaquo.python 不属 Sony normal，另列隔离例外。 |
| `com.blankj:utilcodex` | 1.31.0 | 1.31.0 | [1.31.1](https://repo.maven.apache.org/maven2/com/blankj/utilcodex/1.31.1/utilcodex-1.31.1.pom) | 候选1.31.1；Android API及现有路径/备份调用回归待验。 |
| `com.chaquo.python:gradle` | 12.0.1 | 12.0.1 | 未解析 | 仅enablePython=true时配置；不在Sony normal目标，未迁移/未验证该可选构建。 |
| `com.github.CymChad:BaseRecyclerViewAdapterHelper` | 2.9.49-androidx | 2.9.49-androidx | [3.0.12](https://jitpack.io/com/github/CymChad/BaseRecyclerViewAdapterHelper/3.0.12/BaseRecyclerViewAdapterHelper-3.0.12.pom) | 旧坐标候选 3.0.12 是破坏性 API 升级，且迁移后的新坐标需另查；不能只改号。 |
| `com.github.alidili:FlowLayout` | v1.5 | v1.5 | [2.0](https://jitpack.io/com/github/alidili/FlowLayout/2.0/FlowLayout-2.0.pom) | 2.0 是候选，包/API及遥控布局回归未完成。 |
| `com.github.anilbeesetti.nextlib:nextlib-media3ext` | 0.7.1 | 0.7.1 | [0.8.4](https://jitpack.io/com/github/anilbeesetti/nextlib/nextlib-media3ext/0.8.4/nextlib-media3ext-0.8.4.pom) | 旧坐标 metadata 0.8.4 不是全项目最新：新坐标 io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.17.0；min23，需配套 Media3 1.11.1。 |
| `com.github.bumptech.glide:compiler` | 4.16.0 | 4.16.0 | [5.0.9](https://repo.maven.apache.org/maven2/com/github/bumptech/glide/compiler/5.0.9/compiler-5.0.9.pom) | Glide compiler 必须与运行时同版；候选迁移待 min23 门禁。 |
| `com.github.bumptech.glide:glide` | 4.16.0 | 4.16.0 | [5.0.9](https://repo.maven.apache.org/maven2/com/github/bumptech/glide/glide/5.0.9/glide-5.0.9.pom) | 候选 5.0.9 AAR min23/minCompile37；与 compiler 一并迁移。 |
| `com.github.ctiao:DanmakuFlameMaster` | 0.9.25 | 0.9.25 | 未解析 | Maven Central 0.3.8 低于当前 0.9.25，拒绝降级；JitPack查询未确证可用更高稳定版。 |
| `com.github.getActivity:XXPermissions` | 18.5 | 18.5 | [28.3](https://jitpack.io/com/github/getActivity/XXPermissions/28.3/XXPermissions-28.3.pom) | 28.3 AAR min17，但权限接口有变化；组件优先迁 Android/AndroidX 公开 API，剩余调用需检查。 |
| `com.github.hedzr:android-file-chooser` | v1.2.0-final | v1.2.0-final | [1.2.0-final](https://jitpack.io/com/github/hedzr/android-file-chooser/1.2.0-final/android-file-chooser-1.2.0-final.pom) | 带/不带 v 的 tag 不等同已确认升级；SAF入口迁移不代表旧 chooser 所有调用已移除。 |
| `com.github.seven332:jcifs` | 1.3.19 | 1.3.19 | [1.3.19](https://jitpack.io/com/github/seven332/jcifs/1.3.19/jcifs-1.3.19.pom) | 同坐标稳定候选未高于当前，现代SMB替代需独立API与认证兼容迁移。 |
| `com.github.thegrizzlylabs:sardine-android` | 0.8 | v0.9 | [v0.9](https://jitpack.io/com/github/thegrizzlylabs/sardine-android/v0.9/sardine-android-v0.9.pom) | 已选 v0.9，AAR min15；与 OkHttp5 的二进制和WebDAV回归必须通过。 |
| `com.google.android.material:material` | 1.12.0 | 1.12.0 | [1.14.0](https://dl.google.com/dl/android/maven2/com/google/android/material/material/1.14.0/material-1.14.0.pom) | 候选 1.14.0 AAR min23；暂保留原版等待实际API门禁。 |
| `com.google.code.gson:gson` | 2.10.1 | 2.14.0 | [2.15.0](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.15.0/gson-2.15.0.pom) | 最新 2.15.0 官方要求 Android24；当前先选官方支持min21的2.14.0，不是最终Sony最新结论。必须检查既有数据/更新清单与R8。 |
| `com.google.zxing:core` | 3.5.1 | 3.5.4 | [3.5.4](https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.4/core-3.5.4.pom) | ZXing 已选 3.5.4，二维码及R8/反射回归待验。 |
| `com.googlecode.juniversalchardet:juniversalchardet` | 1.0.3 | 1.0.3 | [1.0.3](https://repo.maven.apache.org/maven2/com/googlecode/juniversalchardet/juniversalchardet/1.0.3/juniversalchardet-1.0.3.pom) | API/最低要求、许可证和运行兼容尚未核实。 |
| `com.kingja.loadsir:loadsir` | 1.3.8 | 1.3.8 | 未解析 | 正式源查询未解析，保留原声明；未证明它是最新。 |
| `com.lzy.net:okgo` | 3.0.4 | 3.0.4 | 未解析 | 未解析到可信新版；保留3.0.4，真实检查与OkHttp5二进制链接和请求，不用force掩盖。 |
| `com.orhanobut:hawk` | 2.0.1 | 2.0.1 | [2.0.1](https://repo.maven.apache.org/maven2/com/orhanobut/hawk/2.0.1/hawk-2.0.1.pom) | 同坐标仍2.0.1；保留key/格式与加密资料，不做破坏性替换。 |
| `com.owen:tv-recyclerview` | 3.0.0 | 3.0.0 | 未解析 | 查询未解析；需要原来源/替代与遥控焦点回归。 |
| `com.squareup.okhttp3:okhttp` | 3.12.11 | 5.5.1 | [5.5.1](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/5.5.1/okhttp-5.5.1.pom) | 已选5.5.1，官方要求Android21/Java8；迁移internal调用、TLS、DoH、OkGo/Sardine。 |
| `com.squareup.okio:okio` | 3.3.0 | 3.18.2 | [3.18.2](https://repo.maven.apache.org/maven2/com/squareup/okio/okio/3.18.2/okio-3.18.2.pom) | 已选3.18.2；检查OkHttp配套解析及流行为。 |
| `com.thoughtworks.xstream:xstream` | 1.4.20 | 1.4.21 | [1.4.21](https://repo.maven.apache.org/maven2/com/thoughtworks/xstream/xstream/1.4.21/xstream-1.4.21.pom) | 已选1.4.21；XML/字幕兼容回归待验，不放宽类型权限。 |
| `com.yanzhenjie.andserver:api` | 2.1.12 | 2.1.12 | [2.1.12](https://repo.maven.apache.org/maven2/com/yanzhenjie/andserver/api/2.1.12/api-2.1.12.pom) | AndServer2.1.12是已核实该坐标最新；旧Gradle plugin换为项目公开Variant接线，实际生成器已运行。 |
| `com.yanzhenjie.andserver:processor` | 2.1.12 | 2.1.12 | [2.1.12](https://repo.maven.apache.org/maven2/com/yanzhenjie/andserver/processor/2.1.12/processor-2.1.12.pom) | AndServer2.1.12；已通过legacy-kapt生成路由，仍需minify与HTTP运行验收。 |
| `commons-io:commons-io` | 2.13.0 | 2.13.0 | [2.22.0](https://repo.maven.apache.org/maven2/commons-io/commons-io/2.22.0/commons-io-2.22.0.pom) | 候选2.22.0；Java/Android API及desugaring要求需核实，不单纯改号。 |
| `io.github.inflationx:calligraphy3` | 3.1.1 | 3.1.1 | [3.1.1](https://repo.maven.apache.org/maven2/io/github/inflationx/calligraphy3/3.1.1/calligraphy3-3.1.1.pom) | 同坐标未发现更高稳定版；保留字体/反射行为，维护状态不等于版本号。 |
| `io.github.inflationx:viewpump` | 2.1.1 | 2.1.1 | [2.1.1](https://repo.maven.apache.org/maven2/io/github/inflationx/viewpump/2.1.1/viewpump-2.1.1.pom) | 同坐标未发现更高稳定版；与Calligraphy成套回归。 |
| `me.jessyan:autosize` | 1.2.1 | 1.2.1 | 未解析 | 正式源查询未解析；已有AAR混合support/AndroidX，需要Jetifier，不能隐瞒维护问题。 |
| `net.sourceforge.streamsupport:android-retrofuture` | 1.7.4 | 1.7.4 | [1.7.4](https://repo.maven.apache.org/maven2/net/sourceforge/streamsupport/android-retrofuture/1.7.4/android-retrofuture-1.7.4.pom) | 同坐标候选未高于当前；替代为desugaring需要实际调用迁移。 |
| `org.apache.commons:commons-text` | 1.10.0 | 1.10.0 | [1.15.0](https://repo.maven.apache.org/maven2/org/apache/commons/commons-text/1.15.0/commons-text-1.15.0.pom) | 候选1.15.0；需与Commons Lang传递依赖和Android API配套检查。 |
| `org.conscrypt:conscrypt-android` | 2.5.2 | 2.9.0 | [2.9.0](https://repo.maven.apache.org/maven2/org/conscrypt/conscrypt-android/2.9.0/conscrypt-android-2.9.0.pom) | 已选2.9.0；AAR min21与四ABI已静态核实，native实际加载未测。 |
| `org.greenrobot:eventbus` | 3.3.1 | 3.3.1 | [3.3.1](https://repo.maven.apache.org/maven2/org/greenrobot/eventbus/3.3.1/eventbus-3.3.1.pom) | 同坐标未发现更高稳定版；事件与混淆keep须保留。 |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | 2.4.21 | 2.4.21 | [2.4.21](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.4.21/kotlin-gradle-plugin-2.4.21.pom) | 2.4.21配合AGP内置Kotlin，未叠加kotlin-android插件；真实kapt/Kotlin已运行。 |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.21 | 2.4.21 | [2.4.21](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.21/kotlin-stdlib-2.4.21.pom) | 与构建Kotlin统一2.4.21；最终runtime图需检查冲突选择。 |
| `org.jsoup:jsoup` | 1.16.1 | 1.16.1 | [1.24.1](https://repo.maven.apache.org/maven2/org/jsoup/jsoup/1.24.1/jsoup-1.24.1.pom) | 候选1.24.1；HTML解析及Java/Android最低要求需核实。 |
| `org.nanohttpd:nanohttpd` | 2.3.1 | 2.3.1 | [2.3.1](https://repo.maven.apache.org/maven2/org/nanohttpd/nanohttpd/2.3.1/nanohttpd-2.3.1.pom) | 改到官方org.nanohttpd:2.3.1，保留嵌入HTTP协议。 |
| `org.nanohttpd:nanohttpd-websocket` | 2.3.1 | 2.3.1 | [2.3.1](https://repo.maven.apache.org/maven2/org/nanohttpd/nanohttpd-websocket/2.3.1/nanohttpd-websocket-2.3.1.pom) | 与NanoHTTPD同版2.3.1，WebSocket及电脑配置仍需运行验收。 |
| `org.simpleframework:simple-xml` | 2.7.1 | 2.7.1 | [2.7.1](https://repo.maven.apache.org/maven2/org/simpleframework/simple-xml/2.7.1/simple-xml-2.7.1.pom) | 同坐标仍2.7.1；维护停滞，WebDAV XML替换需兼容验证，不暗自移除。 |
| `xyz.doikki.android.dkplayer:dkplayer-ui` | 3.3.7 | 3.3.7 | [3.3.7](https://repo.maven.apache.org/maven2/xyz/doikki/android/dkplayer/dkplayer-ui/3.3.7/dkplayer-ui-3.3.7.pom) | 同坐标仍3.3.7；工程内播放器实现已定制，不能按SDK更新覆盖。 |
| `xyz.doikki.android.dkplayer:videocache` | 3.3.7 | 3.3.7 | [3.3.7](https://repo.maven.apache.org/maven2/xyz/doikki/android/dkplayer/videocache/3.3.7/videocache-3.3.7.pom) | 同坐标仍3.3.7；缓存协议/来源需与网络栈回归。 |

独立构建插件补充：`com.android.tools.build:gradle-kotlin:9.5.0`（在上述快照之后补入）；官方marker POM已核实，实际加载成功。

传递依赖也已纳入：[运行时152个坐标的候选查询](sony-transitive-candidates.json)。其中100项发现不同版本候选，7项未解析或原比较规则未能判定；这不等于100项都应独立force升级。该快照生成时ViewPager2仍为1.1.0-beta02，之后已经添加1.1.0稳定版约束；版本比较器也补了同基础版本beta→final的排序回归。当前实际选择以`app/gradle.lockfile`为准。

构建已生成运行时依赖图、Gradle锁文件和`gradle/verification-metadata.xml`。校验启用metadata/SHA256，不声称签名认证；生成期间首次获得的校验值仍需来源审查。新Kotlin图片加载器已从旧OkHttp方法调用迁到属性访问。

独立网络回归实际通过92条断言（真实OkGo/Sardine/OkHttp/Okio，Android边界为test shims），源码与JAR摘要已由主线程复核并保存。覆盖DoH切换/原始转发/字面量地址、Brotli/gzip及WebDAV等；不等价于被拒执行的更新专用套件，也不等价于真实Android TLS握手。

## 原生库与证据

见[逐项原生库升级记录](sony-native-upgrade.md)。不能把旧SO换名、升级NDK或仅升级Java依赖称为原生升级；IJK/QuickJS存在JNI定制差异，迅雷/P2P未取得可信配套源码或新版SDK。

- [初始快照](sony-dependency-baseline.json)：保留历史，有已识别的旧工具局限，不直接用于升级。
- [校正候选快照](sony-dependency-candidates.json)：保留full功能族、拒绝metadata降级、支持Catalog。
- [AAR最低要求](sony-candidate-android-requirements.json)：初始Aliyun记录是part，不是full兼容证据。
- [额外AAR检查](sony-candidate-android-requirements-extra.json)：初次下载含相对路径记录错误；[离线归档复检](sony-candidate-android-requirements-extra-recheck.json)重新核对SHA256/Manifest/JNI目录后已修正，未重复下载或执行二进制。full Aliyun min18、Material1.14 min23、旧nextlib0.8.4 min21。
- [本地二进制](sony-local-binary-inspection.json)：18项的hash/归档/ELF静态信息，来源与最新性未认证。

## 验收与不变约束

Catalog覆盖、版本族、禁止降级/误选part等12个离线审计测试已通过；动态加载209条断言已独立复跑（新增主候选严格校验及稳定快照身份），工作区65条断言和主JAR事务回归、组件51条断言也已独立复跑通过，新增覆盖旧式clan根资源、次级/USB卷外播和暂停结果恢复。Android/ART/HTTP边界部分是test doubles，不代表Sony真机验证。

2026-11-06 用户选择code7兼容预发布，并明确批准切换为普通询问权限模式执行测试后，已使用Gson2.14.0/OkHttp JVM5.5.1/Okio3.18.2/Kotlin2.4.21补跑更新套件：UpdateCore 282、UpdateNetwork 69、UpdateViewModel 14、UpdateCleanup 30全部通过；VM runner重复核心282及超时子集3，不重复计数。此前权限拒绝时没有执行或绕过；本次结果来自后续明确授权的真实执行。仍不等于Android安装器全生命周期或Sony真机验收。

最终code7 APK已完成minify Release、包名/min21/target34/ARM64/生产v1-v2签名复验，并在独立API36模拟器正常覆盖同签名code6。实际PackageManager确认code7；原生首页/遥控焦点/设置/关于/更新页、真实launcher intent冷启动，以及电脑服务上传下载/限额/穿越拒绝和AndServer混淆后路由均有记录。该模拟器没有SAF picker，完整授权导入未验；Sony仍未连接。完整[候选验收记录](../dist/sony-xr75x90k-code7-candidate/device-validation.md)在本地候选目录。不能用模拟器结果宣布Sony兼容或全依赖最新版。

2026-11-06 用户确认两份独立加密离线密钥备份、密码安全保存和至少一份恢复核对全部完成，并选择code7兼容预发布，授权为此提交、推送分支/标签及上传GitHub Pre-release；不设Latest、不合入远端main、不宣称全依赖最新版。保留code6封存物和用户数据，不卸载/清数据，不关闭Play Protect，不播放真实影片，不新建/导出密钥或展示密码。

## 特别版本依据

- [Gson2.15 Android24要求](https://github.com/google/gson/blob/gson-parent-2.15.0/README.md)；[2.14 Android21要求](https://github.com/google/gson/blob/gson-parent-2.14.0/README.md)。
- [OkHttp5.5.1平台要求](https://github.com/square/okhttp/blob/parent-5.5.1/README.md)。
- [AGP内置Kotlin与legacy-kapt](https://developer.android.com/build/migrate-to-built-in-kotlin)。
