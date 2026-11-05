# 在线更新验收记录

> 已追加安装后自动清理的 code3 → code4 → code5 验收，见本文末尾「安装后自动清理追加验收」。前面的 code2 → code3 记录保留为首次在线更新的历史结果。

验收日期：2026-11-05（Mac 日期；模拟器快照内部时钟仍显示 2026-08-04，系统安装时间不作为发布日期）。

## 结论

已实现并在 **Android TV API 36、ARM64、3840×2160** 模拟器上完成：应用内主动检查 → 确认下载 → 大小/SHA-256/实际 APK 身份预检 → 再次点击安装并重新校验 → 遥控器确认 Android 系统覆盖安装。

实际安装包 `com.github.tvbox.osc.tk` 从 **versionCode 2 升到 3**，显示版本为 `1.1.0-debug`。两个验收包使用同一 debug 签名，不代表正式发布签名已经准备好。全程没有卸载、清空应用数据、播放视频或配置第三方影视源。

## 环境与产物

- JDK 17（Corretto 17.0.21）、Gradle 7.5、AGP 7.4.2、compileSdk 34、targetSdk 28、minSdk 21。
- `emulator-5556` / `Television_4K`：实际 `wm size` 为 3840×2160，density 640。
- 原 `emulator-5554` 核对为 1920×1080，不把它的本轮截图称为 4K。该实例没有被本任务关闭或卸载；收尾再次读取时已经不在 ADB 设备列表，无法复查其当前状态。
- 正确低版：`captures/update-preview.v6sm1W/TVBox-final-code2.apk`。
- 正确高版：`captures/update-preview.v6sm1W/server/TVBox-final-code3.apk`。
- 高版字节数：`38253115`；SHA-256：`8a92825f17ffa1cff24928232de440f56542e2fed53dc22d181eacf0366ccea2`。
- 每次复制产物前用 `aapt` 核对实际 versionCode；清单由真实 APK 经 `aapt` / `apksigner verify` 后生成，不凭文件名推断版本。
- 验收服务仅监听 Mac `127.0.0.1:19979`，模拟器通过 `10.0.2.2` 访问。仅 debug 允许本机 HTTP；这不是发布服务。完成下载后已停止服务，离线缓存恢复与安装也成功。收尾通过应用确认仅清空本次更新测试地址，16 项既有数据指纹仍全部一致，不留下指向已停止服务的配置。
- 早期截图/文件有误命名，尤其旧的 `TVBox-code3.apk` 实际是 code2；**只使用上述 final 文件交付**。

## 真实设备验收

| 项目 | 实际结果 |
|---|---|
| 未配置 / 保存不自动检查 | 页面提示未配置；保存、进入页面均不自动联网 |
| 手动发现高版 | 正确清单显示 code3；同版 code2 清单显示当前渠道最新版，不提供安装 |
| 遥控下载确认 | 修复初始窗口/触摸模式焦点后，取消按钮初始聚焦；默认 OK 取消，显式右移才批准 |
| 下载进度 | 真实慢速 APK 下载显示已下载大小与进度条，截图 `21-slow-progress-preview.png` |
| 下载取消 / 页面退出 | 遥控取消按钮提示取消；退出同样停止下载；检查内部缓存没有失败下载的 `.part` |
| 损坏缓存恢复 | 仅破坏本次测试 APK 缓存，重新确认下载会删除坏缓存并重新传输；不修改用户偏好/数据库 |
| 坏 SHA-256 | 真正下载后拒绝：`APK 大小或 SHA-256 校验失败`；未进入安装器 |
| 错包名 | 同高版 Hisense APK 的大小/哈希正确，清单伪称 generic；实际 APK 预检拒绝包名不匹配 |
| 错 ABI 渠道 | 同高版 armeabi APK 的大小/哈希正确，清单伪称 ARM64；拒绝更新渠道不匹配 |
| 错签名 | 同包名/版本/渠道 APK 以仅两天有效的临时测试证书重新签名，哈希正确；拒绝签名与当前应用不一致。临时私钥随测试删除，不是生产密钥 |
| 网络失败后候选保留 | 停止本机服务后再次检查报连接失败；已完成下载仍保留候选和恢复记录 |
| 页面重建 / 离线缓存 | 离开并重新打开更新页，在服务关闭时恢复待安装候选；不自动安装，确认后重新校验缓存可到 READY |
| 下载不自动安装 | 下载/缓存校验完成停留在安装按钮，必须再次点击安装 |
| 系统安装取消 | 真实系统页取消后仍为 code2，界面明确尚未确认升级，不误报成功 |
| 系统确认覆盖 | 使用遥控方向键选择系统 Update，再按 OK；PackageManager 实际读取为 code3，无卸载/清数据 |
| 升级后成功恢复 | 重开新应用并进入更新页，显示当前 code3 和根据实际版本确认更新完成，截图 `32-confirmed-code3-preview.png` |

UI 自动化辅助工具现在要求每次 `uiautomator dump` 返回新树，并使用唯一临时路径；忙碌页面无法导出时明确报错，不再把旧 XML 当成当前状态。下载动态画面直接截图。早期 `12` 的焦点问题和部分旧截图名称不代表最后的验收结果。

## 数据保留的实测范围

- 4K 低版建立只读基线；升级后比较 **16 项**既有 Hawk 偏好/加密密钥指纹：`changed: []`，`new_keys: []`。
- 该 4K 快照初始没有 TVBox，本轮先安装 code2；没有持久化 `api_url`，没有既有播放历史数据库。因此不能声称已实测已有影视源地址、已有播放历史保留。
- 原 1080p 实例先前 code1 → code2 的 **19 项**偏好/数据库/密钥指纹全部一致；同样没有持久化 `api_url`。收尾因设备断开未再比较。
- 指纹脚本不保存原始影视源 URL 或加密密钥，只记录哈希；排除独立更新地址与调试开关。不为造历史播放视频。

## 回归与构建

以下均在本机实际执行通过，不下载新测试依赖：

| 测试 | 结果 |
|---|---|
| `tests/run-update-tests.sh` | 核心 282 cases + 网络 69 cases，0 失败 |
| `tests/run-update-vm-tests.sh` | 真实 VM 配合最小 Android 替身：8 cases；超时策略检查 3 cases，0 失败（超时检查与网络套件重复，不当作独立功能重复计数） |
| `tests/test-update-manifest-tool.py` | 10 tests，0 失败 |
| 播放重试/成功追踪/持久化守卫 | 304 + 361 assertions，21 source checks 通过 |
| 直播管理回归 | 94 assertions 通过 |
| `git diff --check` | 通过 |
| `arm64GenericNormalDebug` | 最终 code2 与 code3 均构建成功 |
| 其他 normal/debug 渠道 | armeabiGenericNormal、arm64HisenseNormal 已构建并用真实 APK 核对渠道；最终恢复/超时修复后未重构这两个变体 |
| release 缺签名 | 实际任务按预期明确失败：需要本机 `signing.properties` 和存在的 keystore，不回退到 debug 签名 |

网络套件测试独立默认 TLS client 拒绝不可信证书，使用只信任临时测试证书的测试 client 验证 HTTPS 成功、错误主机名、降级/循环重定向、字节数/哈希、已知/未知长度和取消清理等。APK 下载使用 20 秒连接/30 秒无数据超时，不再使用清单的五分钟总时限；这一差异通过实际 client 配置断言，未做十四分钟真实设备传输测试。

VM 测试执行真实状态/网络代码，覆盖已确认 UPDATED 的重复安装返回、无候选不能 AVAILABLE(null)、下载记录保留、失败后离线重校验与可信标记失效。偏好/PackageManager/主线程运行环境是明确的测试替身，不把这些结果当作真实 Activity 或系统安装器验收。

## 未验证与发布前条件

- **正式签名和 minify release**：没有用户指定的发布密钥，未构建正式签名包，未测试正式签名覆盖升级。没有生成生产私钥、上传 APK/密钥、发布 Release、提交或推送 Git。
- **未知来源授权变化**：模拟器原本允许 `REQUEST_INSTALL_PACKAGES`。已读取权限页，但切换测试被当前权限模式拒绝，未绕过。因此未实测未授权跳转、拒绝授权、授权返回不自动安装。
- **索尼真机 / Android 5–7 / Python**：未验证。75 英寸不决定系统版本、ABI 或 APK 兼容性，需要真机核对；模拟器不等同真机。
- **空间不足、真实掉电/进程被杀、缺系统安装器、真实慢网超五分钟**：未在设备制造这些条件；JVM/配置测试不能替代设备验收。
- 正式发版保持包名、渠道和同一私有发布签名，递增 `version.properties` 的 versionCode，先上传固定版本 APK，再更新 HTTPS 清单。现有 debug 安装不能无损换成不同正式签名。

使用说明与发布流程见 [online-update.md](online-update.md)。

## 安装后自动清理追加验收

日期：2026-11-05。设备仍为 `emulator-5556`，Android TV API 36、ARM64、3840×2160。以下均为本次新增清理功能的实际结果，不沿用上面的首次验收结论。

### 实现范围

- 非导出的 `ACTION_MY_PACKAGE_REPLACED` Receiver 自动安排清理；`App.onCreate()` 在 Hawk 初始化后兜底。安装返回、更新页恢复、最后一个下载/校验文件租约结束也使用同一清理器。
- 候选、更新模块归属、独立安装 token、实际版本绑定的完成提示分开持久化。修改更新地址不解除安装器的文件保护。
- 只自动回收更新模块已废弃的 APK、无活跃 writer 的生成式 `.part` 和空目录；旧无归属标记 APK 必须实际同包/渠道且不高于当前版本。未知、不可读、较新或记录损坏时保守保留。
- 清理线程不排在网络下载后；文件租约覆盖提交任务、worker I/O、rename 和所有主线程回调。取消不会提前释放文件保护。删除失败保留重试依据，不把实际升级成功改成失败。
- 一键缓存清理排除整个 `updates` 子树。播放器只沿用原有启动临时缓存范围，并移除已知空的 `jpali` 父目录；不自动清空全部 cache/files。

### 真实系统安装与文件检查

最初准备核对上轮留下的旧 APK，但本次升级前 `cache/updates` 已不存在；没有将它的消失归因于尚未安装的新代码。本轮改为在 code3 中真实下载 code4，再由 Android 系统安装器确认覆盖，随后验证 code4 → code5。

| 阶段 | 实测结果 |
|---|---|
| code3 下载 code4 | 大小/哈希/身份校验通过，内部更新 APK 实际落盘，字节数 38442440 |
| 系统确认 code3 → code4 | PackageManager 实际版本为 4；前台为 Android TV Launcher，尚未重新打开 TVBox，内部 cache 已无 APK、`.part` 和 `updates` 目录 |
| 清理后重新打开更新页 | 当前版本 4，仍显示「已根据实际安装版本确认更新完成」；没有 AVAILABLE(null) |
| code4 下载 code5 | 实际落盘 38263284 字节的已校验 APK，没有自动安装 |
| code5 系统安装取消 | 实际版本仍为 4；APK 仍存在，设备 SHA-256 与下载产物一致 |
| 取消后的真实冷启动 | 强制停止空闲 TVBox 进程后重开首页，PID 从 9472 变为 10077；APK 哈希未变。重开更新页恢复候选，不自动安装，必须重新校验 |
| 再次进入系统安装器 | 只读检查实际 `update_pending.xml`：候选存在、独立持久化 installer hold 为 1、owned 记录为 1 |
| 系统确认 code4 → code5 | PackageManager 实际版本为 5；前台仍为 Android TV Launcher，尚未重新打开 TVBox，内部 cache 再次为空，APK、`.part` 和更新目录均不存在 |
| 清理后的成功提示 | 重开更新页显示当前版本 5 与实际版本确认更新完成，截图 `15-code5-success-preview.png` |
| 数据与收尾 | code4、code5、清空本次独立测试更新地址后，16 项既有偏好/加密密钥指纹均 `changed: []`、`new_keys: []`；停止本机 fixture 服务并返回 TVBox 首页，不播放 |

这里只测试了空闲进程的强制停止/冷启动；没有在设备中途杀下载进程、制造掉电或磁盘满。该实例没有持久化影视源地址、既有历史数据库或源插件/字幕文件，不能声称这些真实数据已在设备上实测保留；外部资源与未知文件保护另外由文件系统回归覆盖。

### 新增回归与最终构建

| 检查 | 结果 |
|---|---|
| 真实 VM + Android 最小替身 | 14 cases，全部通过：慢下载、缓存校验取消、rename 至 pending 发布间隙、候选替换自动回收等 |
| 真实清理器/偏好/Receiver + Android 最小替身 | 30 cases，全部通过：无更新页清理、独立 token、失败重试、损坏记录保守保护、空目录/路径边界、广播完成及最后一轮请求不丢 |
| 一键缓存纯文件系统测试 | 19 assertions，全部通过：完整保留 updates、外部目标/资源不变、符号链接不跟随、空父目录及深度边界 |
| 更新核心/网络 | 282 + 69 cases，全部通过；VM runner 另运行的 3 项超时配置检查与网络套件重复 |
| 清单生成工具 | 10 tests，全部通过 |
| 播放器/直播既有回归 | 304 + 361 assertions、21 source checks、94 assertions，全部通过；没有进行播放测试 |
| 最终 ARM64 generic normal/debug | code4 与 code5 均在最后的产品修正后离线构建成功；真实系统覆盖安装成功 |
| 正式版本规则 | `version.properties` 未改，用现有 debug-only override 生成 4/5，不创建或更换生产密钥 |

JVM 回归编译真实 `UpdateViewModel`、`UpdatePreferences`、`UpdateCleanup`、`UpdateCleanupReceiver`，只替换 Android/持久化/PackageManager 边界；不是完整 Android 生命周期或系统安装器测试。设备结果仅限上述实际检查，不能代替索尼真机、Android 5–7、Python 或正式签名 release。

### 最终产物

- 目录：`captures/update-cleanup.uVVcIV/`；本机 fixture 服务已停止，未发布到外部。
- code4：`server/TVBox-cleanup-code4.apk`；38442440 字节；SHA-256 `3c4a29a595a1e3ce8c7224d555c301a70676284125759465afce67e0b08fcddb`。
- 最终 code5：`server/TVBox-cleanup-code5.apk`；38263284 字节；SHA-256 `36137cafd3c627a59117a6fd0da707b0f72f07948034bafe9aaa4f0c7607b6df`。只读核对设备已安装的 `base.apk`，SHA-256 与交付文件完全一致。
- 与 code3 相同的 debug 证书 SHA-256：`7e563928392adab7badf207edc7e3dc0c26c4112edc9319e9ba618317db2e0a1`；`aapt`/清单生成器核对实际包名、版本、ABI，`apksigner verify` 通过。
- 最终首页截图：`4k/16-final-home-preview.png`。模拟器保持运行。
- 本轮没有卸载、清应用数据、提交、推送、发布或上传密钥。
