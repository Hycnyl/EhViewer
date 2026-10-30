> 您当前浏览的 EhViewer 基于 FooIbar 1.14.6 版 EhViewer 改造而来，基于我的实际使用体验，增加了一些新功能，调整了一些已有功能。需要说明，我并非专业的开发者，所有变更均由 GPT-6.1 Sol(Max) 完成。我不会、也无法满足所有用户的需求，我无法保证解决软件运行中可能出现的问题，我随时可能引入具有破坏性的新特性，我不建议任何追求极致稳定的用户使用此版本 EhViewer。

> 此版本 EhViewer 修改了包名，与您可能正在使用的[“FooIbar 版”](https://github.com/FooIbar/EhViewer/releases)能够共存，但这也意味着您需要重新配置一些设置项。

<p align="center">
  <a href="/README.md">
  English
  </a>
  <span> | </span>
  <strong>简体中文</strong>
  <span> | </span>
  <a href="/docs/README/zh-tw.md">
  正體中文
  </a>
  <span> | </span>
  <a href="/docs/README/ja.md">
  日本語
  </a>
</p>

<h1 align="center">
  <img src="https://github.com/FooIbar/EhViewer-art/blob/master/launcher_icon-web.svg" width="200" alt="EhViewer">
  <br>EhViewer<br>
</h1>

<div align="center">
  <h3>
    <a href="#变更">
    变更
    </a>
    <span> | </span>
    <a href="#下载">
    下载
    </a>
    <span> | </span>
    <a href="#截图">
    截图
    </a>
    <span> | </span>
    <a href="#鸣谢">
    鸣谢
    </a>
    <span> | </span>
    <a href="#许可证">
    许可证
    </a>
  </h3>
</div>

# 变更

> 请注意，变更始终是与 FooIbar 1.14.6 版 EhViewer 相比较的。

## 新增

### 导出已缓存页面

- 入口： 画廊详情页 → 右上角三点菜单 →「导出已缓存页面」。

- 功能：允许用户在画廊已被删除、无法正常获取图片等情况下，尝试将本地仍然存在的缓存图片以散图或 CBZ 形式导出，并将画廊加入原生下载管理列表。**始终建议以散图形式导出。**

### [实验性] 清理阅读缓存

- 入口：设置 → 高级 → [实验性] 清理阅读缓存。

- 功能：允许用户主动删除一定时间范围内的缓存数据。**根据 Coil 缓存文件的最后修改时间进行筛选，而不是根据用户上次阅读画廊的时间。**

## 调整

### 自定义阅读缓存大小

- 入口：设置 → 高级 → 阅读缓存大小 → 自定义。

- 功能：允许设置更大的缓存大小，支持输入小数。**输入值无最大限制，但最小限制为5。**

### 下载列表点击行为统一

- 入口：不适用。

- 功能：现在在下载列表无论点击左侧图片还是右侧标题，都会先进入画廊。

### 压缩包下载链接处理

- 入口：不适用。

- 功能：现在点击下载压缩包后，远程下载 URL 将作为网页链接打开，而不是 application/zip，以解决 Android 可能将下载地址交给音乐播放器、文件管理器等不合适应用的问题。

### 排行界面交互优化

- 入口：不适用。

- 功能：在排行榜页面中，右上角书签图标改为带编号的列表图标，与其用于切换榜单的功能更加契合。

## 其它

- 软件包名：包名变更为`moe.hycnyl.ehviewer`；

- 关于页面：相关链接更改，指向当前仓库。

- UI/UX 优化。

# 下载

| 变种      | 最低 Android 版本 | 备注      |
|-------------|---------------|----------|
| Default     | 8.0           | 完全支持     |
| Marshmallow | 6.0*          | 有限支持，无保证 |

*运行 Android 6 的设备需安装 [ISRG Root X1](https://letsencrypt.org/certs/isrgrootx1.pem) 证书

<a href="https://github.com/Hycnyl/EhViewer/releases">
<img alt="Get it on GitHub" src="https://github.com/FooIbar/EhViewer-art/blob/master/get-it-on-github.svg" width="200px"/>
</a>

# 截图

![screenshots-01](https://github.com/FooIbar/EhViewer-art/blob/master/screenshots-01.webp)
![screenshots-02](https://github.com/FooIbar/EhViewer-art/blob/master/screenshots-02.webp)

# 鸣谢

## 原项目与开发者

- "FooIbar / EhViewer" (https://codeberg.org/EhViewer/EhViewer)：本分支的直接上游，基于其 1.14.6 版本继续开发。感谢 FooIbar 及所有贡献者长期以来对 EhViewer 的开发与维护。
- "Hippo Seven（seven332）/ EhViewer" (https://github.com/seven332/EhViewer)：EhViewer 的原始项目及作者，奠定了应用的基础。
- "NekoInverter / EhViewer" (https://gitlab.com/NekoInverter/EhViewer)：EhViewer 的重要后续分支，为项目的持续开发和现代化作出了贡献。
- Tarsin Norbin：EhViewer 后续开发与维护中的重要贡献者。
- "EhViewer Overhauled" (https://github.com/EhViewer-NekoInverter/EhViewer-Overhauled)：EhViewer 现代化开发过程中的重要分支，推动了 Material Design 3 等技术与界面的应用。

## 其他项目与社区贡献

- "Tachiyomi" (https://github.com/tachiyomiorg)：感谢 Tachiyomi 开源社区提供的阅读器实现与相关代码。
- "EhTagTranslation" (https://github.com/EhTagTranslation/Database)：感谢项目维护者及翻译贡献者为中文标签翻译所付出的努力。
- "FooIbar / EhViewer-art" (https://github.com/FooIbar/EhViewer-art)：提供本项目 README 所引用的图标、截图及其他视觉资源。
- EhViewer 的所有贡献者：感谢历代开发者、翻译人员、问题报告者和社区成员的贡献。

## 主要开源依赖

- "Android Open Source Project（AOSP）" (https://source.android.com/)、"AndroidX 与 Jetpack Compose" (https://developer.android.com/jetpack)
- "Kotlin 与 KotlinX" (https://kotlinlang.org/)
- "Arrow" (https://arrow-kt.io/)
- "Ktor" (https://ktor.io/)
- "OkHttp" (https://github.com/square/okhttp) 与 "Okio" (https://github.com/square/okio)
- "Coil" (https://coil-kt.github.io/coil/)
- "Compose Destinations" (https://github.com/raamcosta/compose-destinations)
- "Material Icons" (https://github.com/google/material-design-icons) 与 "MaterialKolor" (https://github.com/jordond/materialkolor)
- "Telephoto" (https://github.com/saket/telephoto)
- "moko-resources" (https://github.com/icerockdev/moko-resources)
- "AboutLibraries" (https://github.com/mikepenz/AboutLibraries)
- "libarchive" (https://github.com/libarchive/libarchive)
- "libwebp" (https://github.com/webmproject/libwebp)
- "Nettle" (https://www.lysator.liu.se/~nisse/nettle/) 与 "XZ Utils" (https://github.com/tukaani-project/xz)
- "Rust" (https://www.rust-lang.org/) 与 "Corrosion" (https://github.com/corrosion-rs/corrosion)

以上仅列出部分主要项目，并非完整依赖清单。各组件的版权、许可协议及其他相关声明，请以项目源码、"LICENSE"、"NOTICE" 和应用内的开源许可信息为准。

再次感谢所有让 EhViewer 得以诞生、延续与发展的贡献者。

# 许可证

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    EhViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    EhViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
