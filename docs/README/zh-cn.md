> 您当前浏览的 EhViewer 基于 Foolbar 1.14.6 版 EhViewer 改造而来，基于我的实际使用体验，增加了一些新功能，调整了一些已有功能。需要说明，我并非专业的开发者，所有变更均由 GPT-6.1 Sol(Max) 完成。我不会、也无法满足所有用户的需求，我无法保证解决软件运行中可能出现的问题，我随时可能引入具有破坏性的新特性，我不建议任何追求极致稳定的用户使用此版本 EhViewer。

> 此版本 EhViewer 修改了包名，与您可能正在使用的[“Foolbar 版”](https://github.com/FooIbar/EhViewer/releases)能够共存，但这也意味着您需要重新配置一些设置项。

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
    <a href="#感谢">
    感谢
    </a>
    <span> | </span>
    <a href="#许可证">
    许可证
    </a>
  </h3>
</div>

# 变更

> 请注意，变更始终是与 Foolbar 1.14.6 版 EhViewer 相比较的。

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

# 感谢

本项目受到了诸多开源项目的帮助

- [Arrow](https://arrow-kt.io/)
- [AOSP & AndroidX](https://source.android.com/)
- [Kotlin & KotlinX](https://kotlinlang.org/)
- [Material Icons](https://github.com/google/material-design-icons)
- [Ktor](https://ktor.io/)
- [Coil](https://coil-kt.github.io/coil/)
- [Compose Destinations](https://composedestinations.rafaelcosta.xyz/)
- [libarchive](https://www.libarchive.org/)

# 许可证

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    EhViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    EhViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
