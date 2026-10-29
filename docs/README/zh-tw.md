> 您目前瀏覽的 EhViewer 基於 FooIbar 1.14.6 版 EhViewer 改造而來，基於我的實際使用體驗，增加了一些新功能，調整了一些既有功能。需要說明，我並非專業的開發者，所有變更均由 GPT-6.1 Sol(Max) 完成。我不會、也無法滿足所有使用者的需求，我無法保證解決軟體執行中可能出現的問題，我隨時可能引入具有破壞性的新特性，我不建議任何追求極致穩定的使用者使用此版本 EhViewer。

> 此版本 EhViewer 修改了套件名稱，與您可能正在使用的[「FooIbar 版」](https://github.com/FooIbar/EhViewer/releases)能夠共存，但這也意味著您需要重新設定一些設定項目。

<p align="center">
  <a href="/README.md">
  English
  </a>
  <span> | </span>
  <a href="/docs/README/zh-cn.md">
  简体中文
  </a>
  <span> | </span>
  <strong>正體中文</strong>
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
    <a href="#變更">
    變更
    </a>
    <span> | </span>
    <a href="#下載">
    下載
    </a>
    <span> | </span>
    <a href="#截圖">
    截圖
    </a>
    <span> | </span>
    <a href="#鳴謝">
    鳴謝
    </a>
    <span> | </span>
    <a href="#授權條款">
    授權條款
    </a>
  </h3>
</div>

# 變更

> 請注意，變更始終是與 FooIbar 1.14.6 版 EhViewer 相比較的。

## 新增

### 匯出已快取頁面

- 入口： 畫廊詳情頁 → 右上角三點選單 →「匯出已快取頁面」。

- 功能：允許使用者在畫廊已被刪除、無法正常取得圖片等情況下，嘗試將本機仍然存在的快取圖片以個別圖片或 CBZ 形式匯出，並將畫廊加入原生下載管理清單。**始終建議以個別圖片形式匯出。**

### [實驗性] 清理閱讀快取

- 入口：設定 → 進階 → [實驗性] 清理閱讀快取。

- 功能：允許使用者主動刪除一定時間範圍內的快取資料。**根據 Coil 快取檔案的最後修改時間進行篩選，而不是根據使用者上次閱讀畫廊的時間。**

## 調整

### 自訂閱讀快取大小

- 入口：設定 → 進階 → 閱讀快取大小 → 自訂。

- 功能：允許設定更大的快取大小，支援輸入小數。**輸入值無最大限制，但最小限制為5。**

### 下載清單點選行為統一

- 入口：不適用。

- 功能：現在在下載清單無論點選左側圖片還是右側標題，都會先進入畫廊。

### 壓縮檔下載連結處理

- 入口：不適用。

- 功能：現在點選下載壓縮檔後，遠端下載 URL 將作為網頁連結開啟，而不是 application/zip，以解決 Android 可能將下載位址交給音樂播放器、檔案管理員等不合適應用程式的問題。

### 排行介面互動最佳化

- 入口：不適用。

- 功能：在排行榜頁面中，右上角書籤圖示改為帶編號的清單圖示，與其用於切換榜單的功能更加契合。

## 其它

- 軟體套件名稱：套件名稱變更為`moe.hycnyl.ehviewer`；

- 關於頁面：相關連結變更，指向目前的儲存庫。

# 下載

| 變種      | 最低 Android 版本 | 備註      |
|-------------|---------------|----------|
| Default     | 8.0           | 完全支援     |
| Marshmallow | 6.0*          | 有限支援，無保證 |

*執行 Android 6 的裝置需安裝 [ISRG Root X1](https://letsencrypt.org/certs/isrgrootx1.pem) 憑證

<a href="https://github.com/Hycnyl/EhViewer/releases">
<img alt="Get it on GitHub" src="https://github.com/FooIbar/EhViewer-art/blob/master/get-it-on-github.svg" width="200px"/>
</a>

# 截圖

![screenshots-01](https://github.com/FooIbar/EhViewer-art/blob/master/screenshots-01.webp)
![screenshots-02](https://github.com/FooIbar/EhViewer-art/blob/master/screenshots-02.webp)

# 鳴謝

## 原始專案與開發者

- "FooIbar / EhViewer" (https://codeberg.org/EhViewer/EhViewer)：本分支的直接上游，基於其 1.14.6 版本繼續開發。感謝 FooIbar 及所有貢獻者長期以來對 EhViewer 的開發與維護。
- "Hippo Seven（seven332）/ EhViewer" (https://github.com/seven332/EhViewer)：EhViewer 的原始專案及作者，奠定了應用程式的基礎。
- "NekoInverter / EhViewer" (https://gitlab.com/NekoInverter/EhViewer)：EhViewer 的重要後續分支，為專案的持續開發和現代化作出了貢獻。
- Tarsin Norbin：EhViewer 後續開發與維護中的重要貢獻者。
- "EhViewer Overhauled" (https://github.com/EhViewer-NekoInverter/EhViewer-Overhauled)：EhViewer 現代化開發過程中的重要分支，推動了 Material Design 3 等技術與介面的應用。

## 其他專案與社群貢獻

- "Tachiyomi" (https://github.com/tachiyomiorg)：感謝 Tachiyomi 開源社群提供的閱讀器實作與相關程式碼。
- "EhTagTranslation" (https://github.com/EhTagTranslation/Database)：感謝專案維護者及翻譯貢獻者為中文標籤翻譯所付出的努力。
- "FooIbar / EhViewer-art" (https://github.com/FooIbar/EhViewer-art)：提供本專案 README 所引用的圖示、截圖及其他視覺資源。
- EhViewer 的所有貢獻者：感謝歷代開發者、翻譯人員、問題回報者和社群成員的貢獻。

## 主要開源依賴

- "Android Open Source Project（AOSP）" (https://source.android.com/)、"AndroidX 與 Jetpack Compose" (https://developer.android.com/jetpack)
- "Kotlin 與 KotlinX" (https://kotlinlang.org/)
- "Arrow" (https://arrow-kt.io/)
- "Ktor" (https://ktor.io/)
- "OkHttp" (https://github.com/square/okhttp) 與 "Okio" (https://github.com/square/okio)
- "Coil" (https://coil-kt.github.io/coil/)
- "Compose Destinations" (https://github.com/raamcosta/compose-destinations)
- "Material Icons" (https://github.com/google/material-design-icons) 與 "MaterialKolor" (https://github.com/jordond/materialkolor)
- "Telephoto" (https://github.com/saket/telephoto)
- "moko-resources" (https://github.com/icerockdev/moko-resources)
- "AboutLibraries" (https://github.com/mikepenz/AboutLibraries)
- "libarchive" (https://github.com/libarchive/libarchive)
- "libwebp" (https://github.com/webmproject/libwebp)
- "Nettle" (https://www.lysator.liu.se/~nisse/nettle/) 與 "XZ Utils" (https://github.com/tukaani-project/xz)
- "Rust" (https://www.rust-lang.org/) 與 "Corrosion" (https://github.com/corrosion-rs/corrosion)

以上僅列出部分主要專案，並非完整依賴清單。各元件的著作權、授權條款及其他相關聲明，請以專案原始碼、"LICENSE"、"NOTICE" 和應用程式內的開源授權資訊為準。

再次感謝所有讓 EhViewer 得以誕生、延續與發展的貢獻者。

# 授權條款

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    EhViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    EhViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
