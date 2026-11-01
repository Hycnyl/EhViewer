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
  <img src="https://github.com/Hycnyl/EhViewer-Art/blob/main/launcher_icon-web.svg?raw=true" width="200" alt="EhViewer">
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

### 畫廊版本鏈

- 入口：畫廊詳情頁 → 封面右側資訊卡片 →「版本鏈」。

- 功能：以時間軸顯示最初版本、上層圖庫、正在查看及最新版本，可展開中間版本並點選標題開啟，查看各版本的發佈時間、頁數和大小。載入不完整時可查看原因並重試。

### 版本鏈詳情載入方式

- 入口：設定 → 進階 →「Version chain loading」。

- 功能：允許選擇預設的「GND first (recommended)」或「Parent Walk first」，分別優先從最初版本頁面取得版本清單，或逐級追溯上層圖庫，以載入並驗證中間版本。

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

### 畫廊資訊介面重新設計

- 入口：畫廊詳情頁 → 封面右側資訊卡片。

- 功能：將原有的逐行資訊清單改為概覽與基本資訊卡片，集中顯示標題、分類、語言、評分等資訊。點選標題、GID、Token 或連結可複製，點選上傳者可搜尋其畫廊，長按畫廊連結或上層圖庫連結可開啟。

### 新版本提示互動調整

- 入口：畫廊詳情頁 →「此圖庫有新版本可以使用。」提示。

- 功能：原有的版本選擇對話方塊改為底部彈出面板，以時間軸顯示目前畫廊與較新版本的標題、發佈時間、頁數和大小，標示正在查看及最新版本，點選版本卡片或標題即可開啟。

### 下載篩選入口與圖示最佳化

- 入口：下載清單 → 右上角漏斗圖示。

- 功能：將下載篩選從三點選單移至獨立入口，直接開啟依作者或標籤篩選的側邊面板；以雙向箭頭圖示切換篩選依據，並用下載圖示疊加齒輪表示預設下載標籤設定。

### 設定項目重新分類

- 入口：設定 → EH、主題、下載、隱私、進階及關於。

- 功能：將主題、介面語言、清單樣式及內容顯示等選項集中到獨立的「主題」分類，將桌面版網站選項移入 EH，並用分隔線重新組織各頁設定。純黑深色主題、縮圖列數等選項僅在適用模式下顯示。

### 畫廊詳情頁選單順序調整

- 入口：畫廊詳情頁 → 右上角三點選單。

- 功能：將在瀏覽器中開啟的入口移至選單末尾，使下載與匯出操作排列得更加集中。

### 畫廊資訊與閱讀設定邊緣效果調整

- 入口：畫廊詳情頁 → 封面右側資訊卡片；閱讀介面 → 設定。

- 功能：關閉畫廊資訊清單和閱讀設定分頁到達捲動邊緣時的拉伸效果，減少內容和控制項的形變。

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
<img alt="Get it on GitHub" src="https://github.com/Hycnyl/EhViewer-Art/blob/main/get-it-on-github.svg?raw=true" width="200px"/>
</a>

# 截圖

![screenshots-01](https://github.com/Hycnyl/EhViewer-Art/blob/main/Screenshot-01.png?raw=true)
![screenshots-02](https://github.com/Hycnyl/EhViewer-Art/blob/main/Screenshot-02.png?raw=true)

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
- "FooIbar / EhViewer-art" (https://github.com/FooIbar/EhViewer-art)：軟體圖示及「在 GitHub 取得」圖片的原始來源；副本存放於 [Hycnyl / EhViewer-Art](https://github.com/Hycnyl/EhViewer-Art)。
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
