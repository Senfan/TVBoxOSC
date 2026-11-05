# 应用内在线更新

入口：**设置 → 关于 → 应用更新**。更新清单地址和影视内容源地址独立，清空更新地址不会清空影视源、历史或播放器设置。首次使用没有默认服务器；保存地址不会联网，必须主动点击「检查更新」，确认后才下载。播放时不会自动弹更新。

## 更新清单

配置一个电视可访问的 **HTTPS JSON URL**，例如自己的静态文件服务。参考 [`update-manifest.example.json`](update-manifest.example.json)。模板中的域名、哈希和文件大小是占位符，不能直接用于更新；请用下方工具从真实 APK 生成清单。

格式为 `schemaVersion: 1` 和 `artifacts` 数组，每项包含：

| 字段 | 含义 |
|---|---|
| applicationId | APK 实际包名 |
| brand | `generic` 或 `hisense` |
| mode | `normal` 或 `python`（文件名中的 `java` 对应 `normal`） |
| abi | `arm64-v8a` 或 `armeabi-v7a` |
| versionCode | APK 实际整数版本，1–2100000000 |
| versionName | APK 实际显示版本，debug 包包括 `-debug` 后缀 |
| minSdk | APK 最低 Android API |
| apkUrl | APK 的完整 HTTPS 地址 |
| sha256 | APK 文件的 64 位十六进制 SHA-256 |
| sizeBytes | 文件实际字节数，最多 200 MiB |
| notes | 可选更新说明，最多 8192 字符 |

清单最多 256 KiB、256 个条目。客户端只选择**同包名、同 brand、同 mode、同编译 ABI、系统兼容**且 `versionCode` 严格更高的最高版本。同一最高版本重复匹配会报错，不会任意选择。无兼容包、已是最新版和网络错误分别显示。

清单地址与 APK 地址都必须使用 HTTPS，使用系统信任链和正常主机名验证，与播放器的不严格 TLS client 隔离；重定向不能降级 HTTP。无法通过清单指定「信任新证书」或关闭校验。

> 调试例外：仅 `BuildConfig.DEBUG` 允许 `http://10.0.2.2`、`http://127.0.0.1`、`http://localhost`、`http://[::1]` 本机测试地址。模拟器通过 `10.0.2.2` 访问 Mac；真实电视的 `127.0.0.1` 是电视自身。Release 没有 HTTP 开关。

## 包名、签名、版本规则

无损覆盖要求：**同包名、同签名、严格更高的 versionCode**。本实现还禁止跨品牌、模式或 ABI 切换。

- generic：`com.github.tvbox.osc.tk`
- hisense：`com.github.hisense.osc.tk`
- 品牌、模式、ABI 写入 BuildConfig 和 Manifest 的 `tvbox.update.*` 元数据，客户端校验真实 APK，不猜文件名。
- 固定发布签名；首版不支持换签或签名轮换，要求签名证书集合完全一致。
- 当前模拟器安装的是 debug 签名包。它只能覆盖为同一 debug keystore 签的更高版，**不能直接覆盖为新正式签名包**。不要卸载来绕过；已有异签名包的数据迁移需另行规划。
- 提交发布前核对已分发旧 APK 的证书，已有正式用户必须继续使用旧正式密钥；不要重新生成另一份。

版本集中在项目根的 [`version.properties`](../version.properties)：

```properties
versionCode=2
versionName=1.1.0
```

每次正式发版递增 `versionCode`（建议每次 +1），同时更新可读 `versionName`。不使用时间戳或文件名判定升级，也不重用已发布版本号。Debug 自动添加 `-debug` 后缀。`-PdebugUpdateVersionCode=3` 只供本机高版验收，任何 release 任务都禁止使用该覆盖参数。

## 固定正式密钥（本机操作，不把密码发到聊天）

1. 如已有发布密钥，直接选择已有密钥。只有尚未发布、没有旧正式用户时才创建新密钥。
2. 以下是手动创建示例；**本次实现没有运行它，也没有创建生产私钥**。修改路径和证书信息，命令会在本机提示输入密码：

```bash
keytool -genkeypair -v -keystore /absolute/private/path/tvbox-release.jks -alias tvbox -keyalg RSA -keysize 3072 -validity 36500
```

3. 在本机复制 `signing.properties.example` 为 `signing.properties`，填写私有值：

```properties
storeFile=/absolute/private/path/tvbox-release.jks
storePassword=YOUR_LOCAL_PASSWORD
keyAlias=tvbox
keyPassword=YOUR_LOCAL_PASSWORD
```

路径相对于项目根解析，也支持绝对路径。此文件是本机明文秘密，限制本机文件权限，避免同步到公共云盘。真实配置与 `.jks/.keystore/.p12/.pfx` 已忽略，不应加入 Git。
4. 对 keystore 做至少两份独立、加密、离线备份；密码放入密码管理器，记录 alias 与证书 SHA-256。密钥遗失后不能正常更新已有应用，泄漏可能让别人伪造同签名升级包。不要上传密钥到仓库、清单或 APK 下载服务。

## 构建与生成清单

旧 Gradle/当前依赖请使用 **JDK 17**，安装 `compileSdk 34` 和 Android build-tools。`local.properties` 配置本机 `sdk.dir`；也可设置 `ANDROID_HOME`。本机示例：

```bash
JAVA_HOME=/Users/bytedance/Library/Java/JavaVirtualMachines/corretto-17-tvbox.jdk/Contents/Home ANDROID_HOME=/Users/bytedance/Library/Android/sdk bash ./gradlew :app:assembleArm64GenericNormalDebug
```

正式版本使用配置好的私有签名：

```bash
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk bash ./gradlew :app:assembleArm64GenericNormalRelease
```

缺少完整签名配置或 keystore 时，release 任务明确失败；不会生成未签名包，也不会回退到 debug 签名。不要为通过 CI 把调试密钥冒充正式密钥。现有 CI 若未注入私有发布签名也会失败；本任务未改造或触发 CI 发布。

normal/debug ARM64 通用包路径：
`app/build/outputs/apk/arm64GenericNormal/debug/TVBox_debug-arm64-generic-java.apk`。
其他 normal 渠道任务示例：`assembleArmeabiGenericNormalRelease`、`assembleArm64HisenseNormalRelease`。Python 变体仍依赖项目已有 Python 构建环境，本任务不改造它。

从项目根生成真实清单，工具只用 Python 标准库和本机 SDK，不上传任何文件：

```bash
python3 -I tools/generate-update-manifest.py --sdk /path/to/android-sdk --apk app/build/outputs/apk/arm64GenericNormal/release/TVBox_release-arm64-generic-java.apk --url https://your-host.example/tvbox/3/TVBox-release.apk --notes '更新说明' --output dist/update-manifest.json
```

工具读取 `aapt` 的实际版本/渠道/ABI，运行 `apksigner verify`，支持 Android 5/6 的包须通过 v1 验签，然后流式计算 SHA-256 与大小。自动寻找本机可执行的 build-tools 版本，不依赖固定版本目录。已存在的输出默认拒绝覆盖；先检查内容，确实要替换再传 `--replace`。多个渠道请分别生成，合并各自的 `artifacts`；不手工猜哈希和大小。

调试 fixture 额外使用 `--allow-debug-local`，仅接受可调试 APK；正式发布不得使用此参数。

## 发布顺序（需要自行托管）

1. 构建高版本同签名包，核对包名、渠道和证书。
2. 生成清单；先上传固定版本路径的 APK，确认电视可以完整访问。
3. 最后原子更新 HTTPS 清单；不要在同一个 APK URL 原地改包，不要先发布指向不存在文件的清单。
4. 电视手动检查、确认下载、检查更新说明，下载完点击安装。

本实现不提供托管、不自动上传 Release、不上传 APK 或密钥。HTTPS 与 SHA-256 能保护传输/文件一致性，但不是独立的发布身份来源；客户端另核对当前安装证书，系统安装器最终验证 APK 签名本身。

## 安装与错误恢复

- 下载到内部缓存 `updates/*.part`；流式校验大小、SHA-256，再做真实 APK 身份预检，全部通过才保存为 `.apk`。连接超时 20 秒、无数据读取超时 30 秒；清单请求总超时 5 分钟，APK 持续传输不设五分钟总时限，仍可主动取消，不支持断点续传。
- 预检包名、实际高版、显示版本、渠道、原生 ABI、签名身份。API24+ 同时读取 APK 实际 minSdk；API21–23 的实际系统兼容由安装器最终强制检查。
- 安装前重新验证缓存；文件损坏后确认下载会清理并重新下载，不能沿用历史「已验证」状态。
- Android 8+ 未授权时，确认后进入本应用的「允许安装未知应用」页面；返回只复查权限/文件，**仍需再次点击安装**。不循环弹授权。Android 5–7 按系统安装器的旧未知来源提示操作。
- 使用现有 FileProvider `content://`、临时只读授权唤起系统安装器，必须由用户在系统确认，不静默安装、提权、卸载或清数据。
- 打开安装器只表示「已交给系统」，不报告成功。取消/拒绝不会影响旧应用；返回或下次打开更新页，以实际已安装 `versionCode` 确认结果。
- 退出更新页取消工作，配置变化保留当前工作；进程重启只恢复候选，不恢复可信标记、不自动安装。再次检查不会删除已完成下载的恢复记录；只有另一个下载完成或更改更新地址才替换该记录。
- 实际版本确认安装完成后，本包替换广播和安装返回自动尝试删除已用更新 APK；App 启动兜底，无需再进入更新页。废弃的更新模块文件、无活跃写入的 `.part` 及空 `updates` 目录一并回收，下载时再创建目录；删除失败不改变升级成功结果，后续操作/启动重试，不等待七天。
- 待安装 APK、正在下载/校验的文件和系统安装器仍在读取的 APK 受保护；取消安装、离线或进程重启不能触发误删。安装器使用保护独立于更新地址，只由匹配的安装返回或实际版本确认解除。一键清缓存也保留整个更新目录，由同一清理规则判断。
- 自动清理仅限明确归更新模块的内部缓存，旧孤立 APK 还需确认同包/渠道且版本不高于当前；未知、较新或暂时无法读取的旧包保留。沿用播放器启动临时缓存清理并移除已知空父目录，不自动清空全部 cache/files，不删除源插件、字幕、历史数据库、Hawk/密钥、壁纸、备份或用户下载。

## 验证范围

策略/网络回归：`bash tests/run-update-tests.sh`；真实 ViewModel 与清理组件配合最小 Android 测试替身的回归：`bash tests/run-update-vm-tests.sh`；一键缓存保留更新目录、路径边界与空目录回归：`bash tests/run-cache-cleanup-tests.sh`；发布工具回归：`python3 -I tests/test-update-manifest-tool.py`。既有回归：`bash tests/run-playback-retry-tests.sh`、`bash tests/run-live-player-tests.sh`。测试不下载新依赖、不碰生产签名；测试替身不等同真实 Android 生命周期或安装器。

首次 4K 在线更新验收为同 debug 签名 code2 → code3；新增自动清理验收为 code3 → code4 → code5，均通过应用下载和系统确认覆盖安装，不卸载、不清数据。实际升级后，尚未重新打开应用时 APK 和空更新目录已清理；取消安装及冷启动时待安装 APK 保留，成功提示仍正常。模拟器目前实际为 code5；后续覆盖包须使用同签名且 versionCode 严格更高，不要用默认低版降级。

既有偏好与加密密钥只读哈希比较全部一致。该实例没有已保存的影视源地址、既有播放历史数据库或源插件/字幕文件，不能据此宣称这些真实数据已经实测保留。网络/纯 JVM 策略通过不代表 PackageManager 或系统安装器已验收；各轮实际结果和未测项见 [`online-update-validation.md`](online-update-validation.md)。

未提供用户指定的正式密钥时，正式 minify release 构建和正式签名覆盖升级未验证；索尼真机、Android 5–7 和 Python 变体也不能由当前模拟器结果代替。
