# 发布规则

任何实际影响用户使用的变更（包括图标、文案和功能的增删调整），除非用户明确禁止，完成后都必须：

1. 延续现有版本号，提升 `versionName` 和 `versionCode`。
2. 使用仓库现有 GitHub Actions 构建新版本并发布正式 Release，提供 universal、arm64-v8a、armeabi-v7a、x86_64、x86 APK。
3. 用简短的 Release 说明描述功能变化。

不能因为改动小而省略发版，也不能用 `[skip ci]` 跳过这类变更的默认发布流程。

# README 规则

README 不得随版本随意修改。仅允许在各语言 README 的“变更”章节内，简要补充新版本相对 FooIbar 1.14.6 的差异；不得改动其他内容。例如，UI/UX 改进只需记录“UI/UX 优化”，不展开具体改动点。

# 局部开发规则

每轮开发与构建都应局部、精确，围绕单一明确目标实施，避免一次性改动涉及应用整体的多个模块行为。无关改动留待后续独立迭代。

# SEC-01 签名规则

正式私钥仅在本开发环境 `/workspace/.ehviewer-signing` 保管。目录为 0700，文件为 0600；禁止输出凭据、提交私钥、上传 GitHub Secrets 或 CI Artifact。新证书指纹固定于 `app/signing/release.json`。密钥缺失时必须恢复原备份，不能生成替代密钥或回退旧密钥。

Actions 先构建 `UnsignedRelease` 并生成 `ci-unsigned-<run-id>` 私有草稿。取回后用 `tools/security/local_signing.py` 在本环境签名。仅将签好的 APK 与公开验证数据通过 GitHub 插件 `create_blob` 传输；512 KiB 分块及请求由 `candidate_release.py plan` 生成，逐块核对 Git blob SHA，再提交 `.github/release-candidate.json`。二进制块不加入 Git 树或源码历史。

`verify-signed-release.yml` 验证签名与 API 33 覆盖安装，全部通过后自动发布正式 Release。发布前不可跳过签名迁移测试。详见 `docs/SEC-01.md`。同机加密备份不是独立离线备份，需向维护者明确说明长期保管限制。
