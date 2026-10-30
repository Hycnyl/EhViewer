# 发布规则

任何实际影响用户使用的变更（包括图标、文案和功能的增删调整），除非用户明确禁止，完成后都必须：

1. 延续现有版本号，提升 `versionName` 和 `versionCode`。
2. 使用仓库现有 GitHub Actions 构建新版本并发布正式 Release，提供 universal、arm64-v8a、armeabi-v7a、x86_64、x86 APK。
3. 用简短的 Release 说明描述功能变化。

不能因为改动小而省略发版，也不能用 `[skip ci]` 跳过这类变更的默认发布流程。

# README 规则

README 不得随版本随意修改。仅允许在各语言 README 的“变更”章节内，简要补充新版本相对 FooIbar 1.14.6 的差异；不得改动其他内容。例如，UI/UX 改进只需记录“UI/UX 优化”，不展开具体改动点。
