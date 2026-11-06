#!/usr/bin/env python3
"""Render audit evidence and explicit pending decisions; never marks compatibility passed."""
import argparse
import json
from pathlib import Path
import tomllib

NOTES = {
    'annotation': '最新 AndroidX 注解候选；随 AndroidX 同族解析后再锁定。',
    'appcompat': '候选 1.8.0 AAR min23，电视实际 API 门禁未解除。',
    'constraintlayout': '已选 2.2.2；AAR min21，待整包回归。',
    'lifecycle-extensions': '聚合包 2.2.0 已停止维护；需迁移实际使用的 ViewModel/LiveData 模块，不能将此号称作新 Lifecycle。',
    'multidex': '同坐标未发现更高稳定版；min21 已原生支持 multidex，移除依赖需一并迁 App 基类。',
    'recyclerview': '已选 1.4.0；AAR min21/minCompile35。',
    'room-runtime': '候选 2.8.5 min23；Android平台变体、runtime/compiler 配套和旧 schema 非破坏迁移未验证。',
    'room-compiler': '与 runtime 同版，禁止 destructive migration；暂未完成新版本迁移。',
    'AlivcConan': '1.0.5 AAR min14/ARM64；须与 Aliyun 配套验证，不单独盲升。',
    'AliyunPlayer': '保留 full 功能族后候选为 7.16.0-full，不采用初版误选的 7.19.0-part；需 SDK API、授权/许可证和 native 配套验证。',
    'gradle': 'AGP 9.5.0 已实际配置运行；com.chaquo.python 不属 Sony normal，另列隔离例外。',
    'gradle-kotlin': 'Google legacy-kapt marker 指向此独立实现；已补依赖并实际运行 kapt。',
    'BaseRecyclerViewAdapterHelper': '旧坐标候选 3.0.12 是破坏性 API 升级，且迁移后的新坐标需另查；不能只改号。',
    'FlowLayout': '2.0 是候选，包/API及遥控布局回归未完成。',
    'nextlib-media3ext': '旧坐标 metadata 0.8.4 不是全项目最新：新坐标 io.github.anilbeesetti:nextlib-media3ext:1.11.1-0.17.0；min23，需配套 Media3 1.11.1。',
    'glide': '候选 5.0.9 AAR min23/minCompile37；与 compiler 一并迁移。',
    'compiler': 'Glide compiler 必须与运行时同版；候选迁移待 min23 门禁。',
    'DanmakuFlameMaster': 'Maven Central 0.3.8 低于当前 0.9.25，拒绝降级；JitPack查询未确证可用更高稳定版。',
    'XXPermissions': '28.3 AAR min17，但权限接口有变化；组件优先迁 Android/AndroidX 公开 API，剩余调用需检查。',
    'android-file-chooser': '带/不带 v 的 tag 不等同已确认升级；SAF入口迁移不代表旧 chooser 所有调用已移除。',
    'jcifs': '同坐标稳定候选未高于当前，现代SMB替代需独立API与认证兼容迁移。',
    'sardine-android': '已选 v0.9，AAR min15；与 OkHttp5 的二进制和WebDAV回归必须通过。',
    'material': '候选 1.14.0 AAR min23；暂保留原版等待实际API门禁。',
    'gson': '最新 2.15.0 官方要求 Android24；当前先选官方支持min21的2.14.0，不是最终Sony最新结论。必须检查既有数据/更新清单与R8。',
    'core': 'ZXing 已选 3.5.4，二维码及R8/反射回归待验。',
    'universalchardet': '同坐标未发现更高稳定版；更换维护者/坐标不能冒充无改动升级。',
    'loadsir': '正式源查询未解析，保留原声明；未证明它是最新。',
    'okgo': '未解析到可信新版；保留3.0.4，真实检查与OkHttp5二进制链接和请求，不用force掩盖。',
    'hawk': '同坐标仍2.0.1；保留key/格式与加密资料，不做破坏性替换。',
    'tv-recyclerview': '查询未解析；需要原来源/替代与遥控焦点回归。',
    'okhttp': '已选5.5.1，官方要求Android21/Java8；迁移internal调用、TLS、DoH、OkGo/Sardine。',
    'okio': '已选3.18.2；检查OkHttp配套解析及流行为。',
    'xstream': '已选1.4.21；XML/字幕兼容回归待验，不放宽类型权限。',
    'api': 'AndServer2.1.12是已核实该坐标最新；旧Gradle plugin换为项目公开Variant接线，实际生成器已运行。',
    'processor': 'AndServer2.1.12；已通过legacy-kapt生成路由，仍需minify与HTTP运行验收。',
    'commons-io': '候选2.22.0；Java/Android API及desugaring要求需核实，不单纯改号。',
    'commons-text': '候选1.15.0；需与Commons Lang传递依赖和Android API配套检查。',
    'calligraphy3': '同坐标未发现更高稳定版；保留字体/反射行为，维护状态不等于版本号。',
    'viewpump': '同坐标未发现更高稳定版；与Calligraphy成套回归。',
    'autosize': '正式源查询未解析；已有AAR混合support/AndroidX，需要Jetifier，不能隐瞒维护问题。',
    'android-retrofuture': '同坐标候选未高于当前；替代为desugaring需要实际调用迁移。',
    'conscrypt-android': '已选2.9.0；AAR min21与四ABI已静态核实，native实际加载未测。',
    'eventbus': '同坐标未发现更高稳定版；事件与混淆keep须保留。',
    'kotlin-gradle-plugin': '2.4.21配合AGP内置Kotlin，未叠加kotlin-android插件；真实kapt/Kotlin已运行。',
    'kotlin-stdlib': '与构建Kotlin统一2.4.21；最终runtime图需检查冲突选择。',
    'jsoup': '候选1.24.1；HTML解析及Java/Android最低要求需核实。',
    'nanohttpd': '改到官方org.nanohttpd:2.3.1，保留嵌入HTTP协议。',
    'nanohttpd-websocket': '与NanoHTTPD同版2.3.1，WebSocket及电脑配置仍需运行验收。',
    'simple-xml': '同坐标仍2.7.1；维护停滞，WebDAV XML替换需兼容验证，不暗自移除。',
    'dkplayer-ui': '同坐标仍3.3.7；工程内播放器实现已定制，不能按SDK更新覆盖。',
    'videocache': '同坐标仍3.3.7；缓存协议/来源需与网络栈回归。',
    'utilcodex': '候选1.31.1；Android API及现有路径/备份调用回归待验。',
}


def render(root):
    snapshot = json.loads((root / 'docs/sony-dependency-candidates.json').read_text())
    catalog = tomllib.loads((root / 'gradle/libs.versions.toml').read_text())
    selected = {}
    for value in catalog['libraries'].values():
        version = value['version']
        selected[value['module']] = catalog['versions'][version['ref']] if isinstance(version, dict) else version
    lines = ['# Sony XR-75X90K 依赖升级记录', '',
             '版本查询截止 **2026-11-05**。这是进行中的选择与验证清单，**不是所有依赖已最新/已兼容/已签名发布的声明**。', '',
             '## 目标与状态', '',
             '- 用户确认曾在该电视安装ARM64；最终目标为arm64-v8a/generic/normal，不改包名或固定签名。',
             '- 电视当前Android API/固件尚未实测。型号资料不能冒充该设备当前系统；仍保持min21，不用overrideLibrary绕过min23/24要求。',
             '- compile37、target34、AGP9.5.0、Gradle9.8.2、Kotlin2.4.21已进入真实构建；Gradle归档已按官方SHA256校验。',
             '- legacy-kapt来自com.android.tools.build:gradle-kotlin:9.5.0；公开AndServer生成器、kapt、Kotlin和QuickJS Java编译已实际运行。首次app javac遇组件迁移中间态错误，不能据此称整包成功。',
             '- 所有版本固定，Catalog不是lockfile。runtime图、锁和校验文件须以真实Gradle输出为准；首次获取的SHA256不是来源签名。',
             '- Python默认不配置；其旧Chaquopy/Python源码保留但不声称已迁移。隔离须用任务图、runtime图和最终APK三层验证。', '',
             '## 全部声明坐标', '',
             '“快照声明”是审计时的值；“当前选择”来自生成本文时的Catalog。上游候选仅为该坐标查询结果，新坐标/弃用状态需结合说明。Last-Modified不是官方发布日期。', '',
             '| 坐标 | 快照声明 | 当前选择 | 上游候选 | 决策/未完成项 |',
             '| --- | --- | --- | --- | --- |']
    for item in snapshot['dependencies']:
        module = item['group'] + ':' + item['artifact']
        note = NOTES.get(item['artifact'], 'API/最低要求、许可证和运行兼容尚未核实。')
        if item['group'].startswith('androidx.media3'):
            note = '同族1.11.1候选min23；需nextlib/FFmpeg配套迁移，当前未升级。'
        if item['group'].startswith('com.chaquo.'):
            note = '仅enablePython=true时配置；不在Sony normal目标，未迁移/未验证该可选构建。'
        candidate = item.get('candidate', '未解析')
        if item.get('pomUrl'):
            candidate = f"[{candidate}]({item['pomUrl']})"
        lines.append(f"| `{module}` | {item['current']} | {selected.get(module, item['current'])} | {candidate} | {note} |")
    lines += ['', '独立构建插件补充：`com.android.tools.build:gradle-kotlin:9.5.0`（在上述快照之后补入）；官方marker POM已核实，实际加载成功。', '',
              '## 原生库与证据', '',
              '见[逐项原生库升级记录](sony-native-upgrade.md)。不能把旧SO换名、升级NDK或仅升级Java依赖称为原生升级；IJK/QuickJS存在JNI定制差异，迅雷/P2P未取得可信配套源码或新版SDK。', '',
              '- [初始快照](sony-dependency-baseline.json)：保留历史，有已识别的旧工具局限，不直接用于升级。',
              '- [校正候选快照](sony-dependency-candidates.json)：保留full功能族、拒绝metadata降级、支持Catalog。',
              '- [AAR最低要求](sony-candidate-android-requirements.json)：初始Aliyun记录是part，不是full兼容证据。',
              '- [额外AAR检查](sony-candidate-android-requirements-extra.json)：full/Material/旧nextlib下载和结构数据；初次相对路径记录错误需与后续复检区分。',
              '- [本地二进制](sony-local-binary-inspection.json)：18项的hash/归档/ELF静态信息，来源与最新性未认证。', '',
              '## 验收与不变约束', '',
              'Catalog覆盖、版本族、禁止降级/误选part等离线审计测试已执行；动态加载202条断言已独立复跑。Android/ART/HTTP边界部分是test doubles，不代表Sony真机验证。旧依赖离线回归不得作为新依赖回归凭据。', '',
              '整包尚需minify、真实包名/SDK/ABI/签名检查、Play Protect正常安装、原生首页/设置/更新页与实际设备验收。源码构建不等于原生运行。不能用模拟器结果宣布Sony兼容。', '',
              '保留code6封存物、密钥与用户数据；不commit/merge/push/tag/上传，不卸载/清数据，不关闭Play Protect，不播放真实影片，不新建/导出密钥或展示密码。', '',
              '## 特别版本依据', '',
              '- [Gson2.15 Android24要求](https://github.com/google/gson/blob/gson-parent-2.15.0/README.md)；[2.14 Android21要求](https://github.com/google/gson/blob/gson-parent-2.14.0/README.md)。',
              '- [OkHttp5.5.1平台要求](https://github.com/square/okhttp/blob/parent-5.5.1/README.md)。',
              '- [AGP内置Kotlin与legacy-kapt](https://developer.android.com/build/migrate-to-built-in-kotlin)。', '']
    return '\n'.join(lines)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    with args.output.open('x', encoding='utf-8') as stream:
        stream.write(render(args.root))
