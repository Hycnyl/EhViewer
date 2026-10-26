# EhViewer 1.15.0 缓存救援预发布

包名：`moe.hycnyl.ehviewer`；版本码：`180064`。基于 FooIbar 最后发行版 1.14.6 增加独立离线导出功能，按功能升级延续为 1.15.0。核心实现由本次主 Codex 代理完成，未使用子代理。

## 源码与安装

```text
SOURCE_URL=https://codeberg.org/EhViewer/EhViewer.git
SOURCE_BASE_SHA=0195f1c9b8f9397e635c84ce14735ba2a2aae4c8
SOURCE_BRANCH=tag 1.14.6 -> feature/cached-page-rescue
SOURCE_TAG_OR_APK_VERSION_IF_KNOWN=1.14.6
IS_EXACT_FOOIBAR_RELEASE=YES (仅指修改前的源码基线)
```

该提交的作者为 FooIbar，提交说明为 `Prepare for release 1.14.6`，并与用户指定 SHA 及 GitHub 完整镜像中的 1.14.6 标签一致。新包是 Hycnyl fork，不是官方发行。保留原项目 GPL、Apache 文件头、LICENSE 和 NOTICE；未移植域前置、DNS、QUIC 或 EhBridge 代码。

**新包与旧包并存，Android 不允许新包直接读取旧包私有的 `image_cache` 或 `spider_info_v2_1`。** 本功能可救援本包自己的缓存，以及重新授权后可访问的既有下载目录/SAF 散图。安装新包不等于迁移旧缓存。不要卸载旧版、清空旧版缓存或删除其暂存散图。旧包中的私有缓存仍需由旧包自身或另行授权的数据迁移方式取得。

交付默认 flavor：Android 8.0+，提供 universal、arm64-v8a、armeabi-v7a、x86_64、x86。所有预发布使用仓库原本随附的公开测试签名证书；应保留该 fork 的安装和签名一致性。未引入 Root、全盘存储或新权限。

## 使用和可观察输出

进入任意画廊详情右上角三点 → **导出已缓存页面** → 选择 **导出散图** 或 **导出 CBZ**。只有 GID/Token、远端详情失败也可操作。扫描、复制、打包在 IO 线程进行，可取消。没有可读页时不生成空包、不登记下载项。

扫描来源优先级：已有下载目录散图 → CBZ 持久临时散图 → 已提交的 Coil 图片快照。排除 `.tmp`、空文件、封面、缩略图及带不完整提交标记的文件；允许缺页，同名去重，同页不同扩展名保留额外版本。未知页数默认扫描 2000 个缓存索引；发现更高的本地页号时扩大探测范围，界面明确说明这不是原总页数。

散图模式：

```text
<downloadLocation>/<原 dirname 或 gid-标题>/
  00000003.jpg
  00000008.webp
  ComicInfo.xml
  .ehviewer
  cached-page-conflicts/<本次唯一编号>/...  # 仅发生冲突时
```

CBZ 模式：

```text
<downloadLocation>/<dirname>/
  <gid>.cached-partial.cbz                 # 再次导出用 -2、-3...，不覆盖
  ComicInfo.xml
  .ehviewer
<应用外部文件目录>/temp/<gid>/
  00000003.jpg
  00000008.webp
```

CBZ 根目录包含实际找到的原页号图片和本地 ComicInfo。`PageCount` 使用本地已知总页数与发现最大页号的最大可信值；例如原 36 页只导出第 3、8 页，仍保留 36，而不是写成 2。真实 pToken 只从匹配当前身份的既有 SpiderInfo 保留，绝不构造缺页 token。

输出沿用配置的下载路径和原 dirname，支持普通文件和 SAF。先暂存、关闭、CRC/长度校验，再提交；不支持重命名的 SAF 使用不完整标记保护，失败清理仅涉及本次未提交产物。SpiderDen 不读取带该标记的页面。已保存的完整结果不会因后续步骤失败而删除。

原生散页副本安全提交后才尝试恢复下载项，新项为 `STATE_NONE`，不调用开始下载；已有状态（包括 `STATE_FINISH`）保持不变。活动/排队中的同 GID 必须先暂停，队列写入与救援互斥。CBZ 已保存但散页副本/登记失败时给出单独报告，不能当作已接管原生阅读/续传。

救援包绝不命名为 `<gid>.cbz` 或 `<gid>.zip`，不改变完整归档识别规则。原生阅读依靠散页；后续普通续传遵循当时全局“保存为 CBZ”设置。缺页不会由救援按钮下载。特殊命名的救援 CBZ 不能直接视为 EhBridge 的标准完整归档。

## 修改范围与离线证据

| 文件 | 作用 |
| --- | --- |
| `spider/SpiderDen.kt` | 只读本地清单、快照存活期间流式复制；忽略未完成提交标记 |
| `spider/CachedPageExport.kt` | 元数据合并、散图/CBZ、暂存提交、下载登记、失败与取消处理 |
| `spider/CachedPageFiles.kt` | 安全页名、图片轻量完整性检查、常量内存流式 CRC、ZIP 逐项校验 |
| `download/DownloadManager.kt` | 与正常入队互斥、恢复项去重；不新增状态或 schema |
| `ui/screen/GalleryDetailScreen.kt`、`CachedPageExportDialog.kt` | 常驻菜单、TokenArgs 回退、选择、进度、取消、结果 |
| `core/i18n/.../{base,zh-rCN}/strings.xml` | 中英文交互和错误提示 |
| `app/build.gradle.kts`、`rust-toolchain.toml`、`shortcuts.xml` | 包名、延续版本、四个 ABI、快捷方式 |
| `app/src/test/.../CachedPageFilesTest.kt` | 针对缺页、损坏/短读、取消、重复输出和 ZIP 完整性的 JVM 测试 |
| `.github/workflows/cache-rescue.yml` | 构建五个 APK、执行针对性测试、校验和及 GitHub 预发布 |

新增导出模块没有 EhEngine、HTTP 客户端、请求构造器或 SpiderQueen 实例依赖；SpiderQueen 仅引用 `.ehviewer` 文件名常量。调用链只到 `SpiderDen.inspectLocalPages/copyLocalPageTo`、本地 XML/CBOR 编解码、文件系统、EhDB 和 `restoreDownload`。没有调用 `contains` 下载模式、`startDownload`、`writeComicInfo(fetchMetadata=true)`、缓存清理或源图片删除接口。详情页原有的联网加载行为保持原样，救援不会触发新的详情刷新。

## 构建与验证

工具链：JDK 21、Gradle 9.2.1、Android SDK 36 / Build Tools 36.0.0、NDK 29.0.14206865、CMake 3.22.1、Rust nightly-2025-11-15；容器额外安装 `m4` 供原生 nettle 构建。

- 原版基线 `./gradlew :app:assembleDefaultDebug --max-workers=2`：**PASS**（补齐 m4 后，退出码 0）。
- 新实现 `:app:compileDefaultDebugKotlin :app:spotlessApply`：**PASS**（退出码 0）。
- `:app:testDefaultDebugUnitTest :app:spotlessApply --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`：**PASS**（7 项，0 失败，退出码 0）。
- 五个 release APK 的实际结果见 GitHub Actions 日志及交付验证记录；预发布步骤只在构建与 release JVM 测试成功后运行。
- CI 命令：`./gradlew :app:testDefaultReleaseUnitTest :app:assembleDefaultRelease --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process`。
- JVM 覆盖：原页号/可信页数、安全文件名、完整/截断图片、非 seek 流短读、取消、重复快照命名、缺页 ZIP 原字节、CRC 损坏和危险条目拒绝。
- **NOT VERIFIED**：没有可用真机或启用 KVM 的模拟器；菜单点击、飞行模式抓包、真实 SAF provider、空间不足/权限撤回、实际阅读器与正常联网续传的端到端行为尚未实测。不能把编译或 JVM 测试通过当成全部 P0 场景已验收。

P1 未做：旧救援 CBZ 增量合并（重复导出保留编号快照）、下载列表逐项“已救援 N 页”标识、未知总页数手动扫描上限。既有损坏元数据保留并报警，不自动覆盖；极端图片内容损坏仅做签名/长度/尾标等轻量过滤，不做完整像素解码。大量图片采用固定缓冲和逐页关闭 FD；暂存、CBZ 和原生散页副本会占用额外空间。
