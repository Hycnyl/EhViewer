# SEC-01：正式签名密钥迁移

## 已发布版本

- Hycnyl 1.17.8：包名 `moe.hycnyl.ehviewer`，versionCode `180083`，最低 API 26。
- 证书 SHA-256：`5d91dff13b6489a9cfadbb197b3e795eefc8c39f849c4543f32c160720fb40a6`。
- APK 使用 v2/v3 签名。仓库中的旧密钥和密码已经公开，不能继续作为可信正式签名身份。
- 此仓库的历史 Release 没有发布 Marshmallow APK。原 Marshmallow 配置的包名为 `moe.hycnyl.ehviewer.m`、最低 API 23，与 Default 共用旧签名配置。不能将配置核对表述为已检查不存在的历史安装包。

## 更新路径

正式版从 1.17.9 起最低要求 Android 13（API 33）。Debug 保留原来的最低版本与调试签名。

| 系统版本 | 新正式版 |
| --- | --- |
| API 23、26、27 | 不支持 v3 轮换；新包最低版本阻止安装 |
| API 28–32 | 虽有 v3 支持，但本次不继续兼容 |
| API 33 及以上 | 使用新密钥和旧证书到新证书的轮换证明覆盖更新 |

新 APK 仅由新私钥签署 v3 签名，不附带供旧系统使用的旧密钥签名。旧证书只保留迁移应用数据所需的 `installed-data` 能力；`rollback`、`permission`、`shared-uid`、`auth` 均关闭。更新完成后，必须拒绝旧密钥签署的更高版本 APK。

AndroidX 的 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 是旧系统动态接收器的兼容权限。关闭旧证书的 `permission` 能力后，原包直接重签会在覆盖安装时触发 `INSTALL_FAILED_DUPLICATE_PERMISSION`。正式版专用 Manifest 移除该兼容权限及其声明；API 33 已使用系统原生的 `RECEIVER_NOT_EXPORTED` 标志。Debug Manifest 不变。

参考：[Android 官方 v3 说明](https://source.android.com/docs/security/features/apksigning/v3)、[apksigner](https://developer.android.com/tools/apksigner)。官方不建议在 Android 12 及以下轮换密钥。

## 本地密钥保管与发布

按维护者最新要求，私钥仅保管在开发环境，不上传 Git、GitHub Secrets 或 CI Artifact。新正式证书为 RSA-4096，SHA-256 为 `67afb9dab5d2234ed83bc3dd8c13faaeadf4940be0a6749e0aafb9ae9e8e143f`。

PKCS12 使用强随机密码，私有目录为 0700、密钥与凭据文件为 0600。同机另存一份加密密钥文件只能防止部分误操作，**不能替代独立离线备份**。工作环境并非永久密钥托管服务；维护者应将加密密钥、轮换证明离线另存，并单独保管恢复凭据。私钥缺失时禁止生成替代密钥继续发包。

1. Actions 构建明确命名的 `UnsignedRelease` 变体，运行现有单元测试并核对 Debug 构建。未签名产物通过私有草稿交接到开发环境，不对用户发布。
2. `local_signing.py` 从 Git 工作区外的受保护目录读取私钥。`sign_release.py` 核对固定证书、轮换能力、最低版本、非 Debug 状态及签名权限，再签署 APK。缺少凭据或校验失败时停止，不使用其他密钥。
3. 只将签好的 APK 和公开验证数据通过插件支持的 Git blob 对象传送到 GitHub。二进制块不加入 Git 树或源码历史；私钥和密码不参与传输。
4. `.github/release-candidate.json` 记录构建提交、二进制块校验和及 CI 构建编号。验证任务确认应用源码在构建后未改变，复核证书、轮换证明、版本、来源标记和校验和。
5. API 33 模拟器验证覆盖更新、UID 与数据保留、旧签名高版本拒绝及启动。Default 使用真实 1.17.8 APK；Marshmallow 使用旧签名的无代码测试基线，并在报告中明确标注。
6. 只有验证通过，独立发布任务才获得仓库写权限并创建正式 Release。正式附件不包含测试包、私钥、密码或轮换密钥文件。

签名任务不使用 Gradle 配置缓存或构建缓存保存凭据。普通 `Release` 的最终 APK 仍需通过本地签名转换任务；`UnsignedRelease` 是显式未签名交接变体，不是缺少私钥时的静默回退。Debug 保留原有最低系统版本与调试签名。

## 历史清理

旧 JKS 从所有公开分支、备份分支和标签的历史中移除，同时清除历史 Gradle 文件中的硬编码签名密码。历史重写会改变提交 SHA；所有协作者应重新克隆或显式切换到清理后的引用。

旧版本的 APK 不含签名私钥，不能通过反编译 APK 恢复私钥；保留历史 APK 与保留泄露的 JKS 是两回事。清理不能让已泄露的旧密钥重新安全。

GitHub 的旧提交视图、不可达对象、缓存或他人的克隆可能仍保留旧内容。完成公开引用清理后应复查旧文件 URL 和对象地址；若仍可访问，需要仓库所有者向 GitHub Support 申请清除缓存和不可达对象。第三方仓库与既有克隆不在本仓库的删除权限内。
