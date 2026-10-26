# 1.15.0 缓存救援交付记录

最终产物：[v1.15.0-pre.3 预发布](https://github.com/Hycnyl/EhViewer/releases/tag/v1.15.0-pre.3)。包名 `moe.hycnyl.ehviewer`，versionName `1.15.0`，versionCode `180064`，Android 8.0+。

基线来自 `https://codeberg.org/EhViewer/EhViewer.git` 的 FooIbar `1.14.6` 标签，固定 SHA `0195f1c9b8f9397e635c84ce14735ba2a2aae4c8`。构建源码提交：[5c011fcf75309a846d5763af11a9672331b5b7d7](https://github.com/Hycnyl/EhViewer/commit/5c011fcf75309a846d5763af11a9672331b5b7d7)。核心代码由主 Codex 完成，未使用子代理。

## APK

- [Universal](https://github.com/Hycnyl/EhViewer/releases/download/v1.15.0-pre.3/EhViewer-1.15.0-universal.apk)
- [arm64-v8a](https://github.com/Hycnyl/EhViewer/releases/download/v1.15.0-pre.3/EhViewer-1.15.0-arm64-v8a.apk)
- [armeabi-v7a](https://github.com/Hycnyl/EhViewer/releases/download/v1.15.0-pre.3/EhViewer-1.15.0-armeabi-v7a.apk)
- [x86_64](https://github.com/Hycnyl/EhViewer/releases/download/v1.15.0-pre.3/EhViewer-1.15.0-x86_64.apk)
- [x86](https://github.com/Hycnyl/EhViewer/releases/download/v1.15.0-pre.3/EhViewer-1.15.0-x86.apk)
- [SHA256SUMS](https://github.com/Hycnyl/EhViewer/releases/download/v1.15.0-pre.3/SHA256SUMS.txt)

GitHub 发布资源的五个 APK SHA-256 均与本地独立验证结果完全一致。每个 APK 已核对包名、版本、minSdk/targetSdk、签名、ZIP CRC、16 KiB ZIP 对齐、实际 ELF 架构和 DEX 内的构建提交标识。Universal 包含四个 ABI，单架构包只含对应 ABI。采用项目随附的公开测试签名证书；证书 SHA-256 为 `5d91dff13b6489a9cfadbb197b3e795eefc8c39f849c4543f32c160720fb40a6`。

## 实际验证

| 命令或检查 | 结果 |
| --- | --- |
| 修改前 `:app:assembleDefaultDebug --max-workers=2` | PASS，退出 0，补齐 m4 后成功 |
| 新实现 `:app:compileDefaultDebugKotlin :app:spotlessApply` | PASS，退出 0 |
| `:app:testDefaultDebugUnitTest` | PASS，7 项，0 失败/错误/跳过 |
| 最终 `:app:assembleDefaultRelease :app:spotlessCheck --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | PASS，退出 0，五个 APK |
| CI `:app:testDefaultReleaseUnitTest :app:assembleDefaultRelease --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process --stacktrace` | PASS，退出 0；release JVM 7 项，0 失败/错误/跳过 |
| APK 独立校验、GitHub 发布资源 digest 比对 | PASS，5/5 |
| 预发布及整个 GitHub workflow | SUCCESS |

可查看 [完整 Action](https://github.com/Hycnyl/EhViewer/actions/runs/40368557906) 中的日志、JVM 报告、mapping 和 APK artifacts。源码修改说明、输出布局、离线调用链证据和安装策略见 [CACHE_RESCUE.md](CACHE_RESCUE.md)。上游完整 lint、benchmark/profile、双 flavor 自动任务使用本项目单一打包流程替代，避免重复构建。

## 验证边界

没有可用真机或 Android 模拟器，因此详情失败入口、真实 SAF provider、飞行模式抓包、空间不足/权限撤回、实际阅读与正常续传的端到端行为 **NOT VERIFIED**。编译和 JVM 测试不等于全部 T01–T18 已实测。未做全量 lint、instrumentation、benchmark。

P1 未做：旧救援 CBZ 增量合并（采用编号快照保留历史）、下载列表专属救援页数标识、未知总页数手动扫描上限。图片仅做轻量结构检查，不做完整像素解码；已有损坏元数据保留并提示。

**不要卸载旧版或清空其缓存。** 新包可与旧包并存，但不能直接读取旧包私有的 image_cache / spider_info_v2_1；安装不会迁移旧缓存。新包可救援自己的缓存和重新授权后可访问的原下载目录/SAF 散图。
