# GitHub Release 本地发布准备

> **2026-11-06 更新：** 用户已确认生产密钥两份独立加密离线备份、密码安全保存及恢复核对完成，选择发布 **code7兼容预发布版**。当前流程与限制以 [v1.1.1-code7-rc.1](releases/v1.1.1-code7-rc.1.md) 为准；下文code5/code6内容保留为历史准备记录，不是当前产物或当前授权状态。预发布不设Latest，应使用固定标签的更新清单地址。

准备日期：2026-11-05。**本次仅写本地文件：没有创建 GitHub Draft/Release、标签、提交或推送，没有上传 APK 或密钥。**

## 仓库与两个链接

目标仓库：[Senfan/TVBoxOSC](https://github.com/Senfan/TVBoxOSC)。只读核对结果：公开仓库，当前 GitHub 账号具有 ADMIN 权限；核对时没有 Release。

正式发布后，TVBox 中填写的稳定更新地址为：

```text
https://github.com/Senfan/TVBoxOSC/releases/latest/download/update-manifest.json
```

已确认首版版本为 `v1.1.1` / code6，固定版本 APK 的计划地址（尚未发布）：

```text
https://github.com/Senfan/TVBoxOSC/releases/download/v1.1.1/TVBox-release-code6-arm64-generic.apk
```

这些地址现在尚未生效。`releases/latest` 只选择 GitHub 的最新正式 Release，不包含 Draft 和 Pre-release。每次正式发布都附上名字完全相同的 `update-manifest.json`；JSON 内的 APK URL 必须指向固定标签下的固定文件，不使用 latest APK URL，不原地替换旧版本的字节。

应用更新地址必须返回原始 JSON；不能填 GitHub Release 网页、仓库首页、APK URL 或需要登录的地址。APK 和 JSON 都要匿名可访问。

## 当前已准备的材料与限制

本地文件夹说明：[`captures/github-release-draft.tnFQwa/README.md`](../captures/github-release-draft.tnFQwa/README.md)。其中有已验收的调试 APK、真实调试清单、调试说明和文件 SHA-256。

- 包名 `com.github.tvbox.osc.tk`，generic / normal / arm64-v8a，versionCode **5**，versionName **1.1.0-debug**，minSdk 21。
- APK SHA-256：`36137cafd3c627a59117a6fd0da707b0f72f07948034bafe9aaa4f0c7607b6df`，38263284 字节。
- 这是 **Android debug 签名包，不是正式发行包**。调试清单独立命名为 `update-manifest.debug.json`，说明文字也明确标注调试预览，不可改名冒充正式清单。
- 拟定调试标签 `debug-code5`。如果以后单独批准发布测试包，应标为 Pre-release，并使用其固定调试清单链接；不要使用正式 latest 地址来定位 Pre-release。
- 当前模拟器已是同签名 code5，该清单只能显示当前版本；要测试下一次更新，须构建严格更高的同调试签名版本并重新生成清单。
- 本机固定正式 JKS 已按用户批准创建，项目根 `signing.properties` 已填写，权限及 Git 忽略检查通过；实际密码/密钥库完整性认证、私钥签名与公钥验证通过。正式 Release APK 尚未构建，所以**没有生成正式 `update-manifest.json` 的虚假哈希/大小**。密钥的加密离线备份与恢复测试尚未确认。
- 仓库 `version.properties` 已设为 code6 / 1.1.1；此前 code4/5 采用 debug-only 参数，不是正式版本号提交。首版说明见 [releases/v1.1.1.md](releases/v1.1.1.md)。
- 当前工作目录存在多个功能及其他会话的未提交改动。现有 code5 是本机测试构建，不能把它称为某个 Git 标签可复现的正式发行包。

## 正式发布前：先准备签名和源码

1. 确认是否已有对外分发的正式 APK/发布私钥。已有正式用户必须继续使用原密钥；不要另建密钥来替换。没有发布过正式版时，才在用户确认后本机创建固定私钥。不要把密码发到聊天。
2. 按 [`signing.properties.example`](../signing.properties.example) 配置本机 `signing.properties`。密钥放在仓库之外并做加密离线备份；私钥、密码和本机签名文件不加入 Git 或 Release。
3. 原模拟器 code5 是 debug 签名，**不同正式签名不能无损覆盖它**；较大的 versionCode 不能弥补异签名。不卸载/清数据来绕过。正式版首装和已有调试数据的迁移需要分别处理。
4. 审阅本次要发的源代码和其他会话改动，明确发布范围；经用户同意后再提交/推送。不要直接把当前 main 或旧 HEAD 当成这批未提交功能的发布源码。
5. 首版已确认并设置 [`version.properties`](../version.properties) 为 code6 / 1.1.1；后续发行保持 versionCode 严格递增：

```properties
versionCode=6
versionName=1.1.1
```

正式用户的版本号必须高于已分发的该渠道版本。正式 release 任务禁止使用 `-PdebugUpdateVersionCode`。

## 在本机构建正式包并生成真实清单

以下是本机构建及核对流程，实际完成状态以最终发布产物的结果记录为准。从项目根运行，并先完成上述签名、源码和版本确认。首版只准备 ARM64 / generic / normal；索尼真机还需核对 Android 版本与 ABI，不能只按电视尺寸选择。

### 1. 构建

```bash
JAVA_HOME=/Users/bytedance/Library/Java/JavaVirtualMachines/corretto-17-tvbox.jdk/Contents/Home ANDROID_HOME=/Users/bytedance/Library/Android/sdk bash ./gradlew :app:assembleArm64GenericNormalRelease
```

缺签名配置会明确失败，不回退为 debug。正式 minify 包需要重新做实际运行/安装验证，不能只沿用调试包验收结果。

### 2. 核对真实 APK

```bash
/Users/bytedance/Library/Android/sdk/build-tools/36.0.0/aapt dump badging app/build/outputs/apk/arm64GenericNormal/release/TVBox_release-arm64-generic-java.apk
```

应核对包名、versionCode 6、versionName 1.1.1、minSdk、原生 ABI，并确认没有 `application-debuggable`。不能凭文件名判断正式版。

```bash
JAVA_HOME=/Users/bytedance/Library/Java/JavaVirtualMachines/corretto-17-tvbox.jdk/Contents/Home /Users/bytedance/Library/Android/sdk/build-tools/36.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/arm64GenericNormal/release/TVBox_release-arm64-generic-java.apk
```

首次正式 APK 的证书 SHA-256 必须与已创建固定密钥的公开指纹一致：`c9d4035688a13f886e6661edc3631f8da38bf84e8676c2c4ade87c00f66bd821`。后续再与既有正式包比对；不能把 Android Debug 证书当正式发布证书。只核对公开证书，不上传私钥。本机密钥验证通过不等于这里的 APK 产物验签已通过。

### 3. 放入全新的版本目录

```bash
mkdir -p dist && mkdir dist/github-release-v1.1.1 && cp app/build/outputs/apk/arm64GenericNormal/release/TVBox_release-arm64-generic-java.apk dist/github-release-v1.1.1/TVBox-release-code6-arm64-generic.apk
```

如果版本目录已存在，命令应停止：先检查旧文件，不能覆盖后重新使用同一版本链接。版本标签/文件名变更时，下面 URL 和实际上传文件名也必须一致。

### 4. 生成正式 JSON

```bash
JAVA_HOME=/Users/bytedance/Library/Java/JavaVirtualMachines/corretto-17-tvbox.jdk/Contents/Home python3 -I tools/generate-update-manifest.py --sdk /Users/bytedance/Library/Android/sdk --apk dist/github-release-v1.1.1/TVBox-release-code6-arm64-generic.apk --url https://github.com/Senfan/TVBoxOSC/releases/download/v1.1.1/TVBox-release-code6-arm64-generic.apk --notes '应用内安全更新、安装成功后自动回收更新缓存。' --output dist/github-release-v1.1.1/update-manifest.json
```

工具读取真实 APK、验证签名，再流式计算哈希和大小。正式包不要使用 `--allow-debug-local`，不要手填或沿用 code5 的哈希。生成工具能验证签名，但不负责决定该证书是否是正确的正式发布身份，因此上一步正式/调试身份核对不能省略。

完成后实际验收：新正式首装、同正式签名升级、取消安装保留文件、确认升级后回收文件、重开页面显示实际成功及用户数据保留。没有已有正式低版时，不声称已验证正式覆盖升级。

## 以后获准公开时：GitHub 页面操作

**下面操作会向外发布。用户已要求正式发布，但密钥备份尚未完成：必须先确认至少两份独立加密离线备份、密码安全保存及至少一份恢复核对；在门槛满足前只完成本地 main 合入和产物准备，不推送、打标签或上传 Release。**

1. 将已确认的发布提交推送到目标仓库，记录完整提交 SHA。
2. 打开仓库 Releases → Draft a new release，选择新标签 `v1.1.1`，确保目标是上述发布提交。不要无意把标签打在不含这些功能的旧 main 上。
3. 填写发布标题/更新说明，附上 `TVBox-release-code6-arm64-generic.apk` 与 `update-manifest.json`。不要选本次独立的 debug 目录，也不要上传 signing.properties、密钥、密码、整个工作目录或日志。
4. 发布前核对 JSON 的包名/渠道/版本/大小/哈希、固定 APK URL 与资产文件名；正式 Release 不勾 Pre-release。
5. 经确认后发布，并设置为 Latest。Draft/Pre-release 不会成为稳定更新地址的最新正式版。
6. 匿名打开固定 APK 和稳定 JSON 链接，确认实际返回 APK/JSON 而非 HTML/登录页；下载后核对 SHA-256，必要时重新验签。稳定清单应只指向完整可下载的正式 APK。
7. 在电视填入本文开头的稳定 JSON 地址，手动检查更新。保存地址本身不会联网或自动安装。

GitHub Release 需要标签。当前本地既没有打标签，也没有创建远端草稿。未来若自动化发布，签名秘密只能由用户批准的安全环境提供，不把本机 debug keystore 当 CI 生产密钥。

## 日常发布规则

- 同包名、同渠道、同固定签名，versionCode 严格递增。
- APK 用固定版本路径；每个正式 Release 都附同名 `update-manifest.json`，最后才让它成为 Latest。
- 预发布使用固定标签 JSON 地址；不要误以为 `/releases/latest/` 会选择 Pre-release。
- 不原地替换已公开旧 APK，不复用旧 versionCode，不让多个同最高版本条目产生歧义。
- GitHub 重定向须保持 HTTPS。电视访问不稳定时，可改为匿名 HTTPS 对象存储直链，APK 身份和签名校验不变。
