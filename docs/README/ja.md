> ご覧の EhViewer は、FooIbar 版 EhViewer 1.14.6 をベースに改造したものです。私自身の使用体験に基づいて、新しい機能をいくつか追加し、既存の機能をいくつか調整しています。なお、私はプロの開発者ではなく、すべての変更は GPT-6.1 Sol(Max) が行いました。すべてのユーザーの要望に応えるつもりはなく、応えることもできません。ソフトウェアの実行中に生じる問題を解決できる保証はなく、破壊的な新機能をいつでも導入する可能性があります。極めて高い安定性を求める方には、このバージョンの EhViewer の使用をおすすめしません。

> このバージョンの EhViewer はパッケージ名を変更しているため、すでにお使いかもしれない[「FooIbar 版」](https://github.com/FooIbar/EhViewer/releases)と共存できますが、一部の設定をやり直す必要があります。

<p align="center">
  <a href="/README.md">
  English
  </a>
  <span> | </span>
  <a href="/docs/README/zh-cn.md">
  简体中文
  </a>
  <span> | </span>
  <a href="/docs/README/zh-tw.md">
  正體中文
  </a>
  <span> | </span>
  <strong>日本語</strong>
</p>

<h1 align="center">
  <img src="https://github.com/FooIbar/EhViewer-art/blob/master/launcher_icon-web.svg" width="200" alt="EhViewer">
  <br>EhViewer<br>
</h1>

<div align="center">
  <h3>
    <a href="#変更点">
    変更点
    </a>
    <span> | </span>
    <a href="#ダウンロード">
    ダウンロード
    </a>
    <span> | </span>
    <a href="#スクリーンショット">
    スクリーンショット
    </a>
    <span> | </span>
    <a href="#謝辞">
    謝辞
    </a>
    <span> | </span>
    <a href="#ライセンス">
    ライセンス
    </a>
  </h3>
</div>

# 変更点

> 変更点は常に FooIbar 版 EhViewer 1.14.6 と比較したものであることにご注意ください。

## 追加

### キャッシュ済みページのエクスポート

- 場所：ギャラリー詳細ページ → 右上の三点メニュー →「キャッシュ済みページのエクスポート」。

- 機能：ギャラリーが削除された、画像を正常に取得できないなどの場合に、端末内に残っているキャッシュ画像を個別の画像または CBZ としてエクスポートし、ギャラリーを標準のダウンロード管理一覧に追加することを試みられます。**常に個別の画像としてエクスポートすることをおすすめします。**

### [実験的] 閲覧キャッシュを削除

- 場所：設定 → 詳細設定 → [実験的] 閲覧キャッシュを削除。

- 機能：一定の期間内のキャッシュデータをユーザーが手動で削除できます。**ギャラリーを最後に閲覧した日時ではなく、Coil のキャッシュファイルの最終更新日時に基づいて絞り込みます。**

## 調整

### 閲覧キャッシュ容量のカスタム設定

- 場所：設定 → 詳細設定 → 閲覧キャッシュ容量 → カスタム。

- 機能：より大きなキャッシュ容量を設定でき、小数の入力にも対応しています。**入力値に上限はありませんが、下限は5です。**

### ダウンロード一覧のタップ動作を統一

- 場所：該当なし。

- 機能：ダウンロード一覧で左側の画像と右側のタイトルのどちらをタップしても、まずギャラリーを開くようになりました。

### アーカイブのダウンロードリンクの処理

- 場所：該当なし。

- 機能：アーカイブのダウンロードをタップすると、リモートのダウンロード URL を application/zip ではなくウェブリンクとして開くようになりました。これにより、Android がダウンロード先の URL を音楽プレーヤーやファイルマネージャーなどの不適切なアプリに渡してしまう問題を解消します。

### ランキング画面の操作を改善

- 場所：該当なし。

- 機能：ランキングページの右上にあるブックマークアイコンを、ランキングの切り替えという役割により適した番号付きリストのアイコンに変更しました。

## その他

- 設定画面の分類を整理しました。

- パッケージ名：`moe.hycnyl.ehviewer` に変更しました。

- アプリ情報ページ：関連リンクを変更し、このリポジトリを参照するようにしました。

# ダウンロード

| フレーバー       | 最低 Android バージョン | 備考              |
|-------------|---------------|----------|
| Default     | 8.0               | 完全なサポート           |
| Marshmallow | 6.0*              | 限定的なサポート、保証なし |

*Android 6 を実行するデバイスには [ISRG Root X1](https://letsencrypt.org/certs/isrgrootx1.pem) 証明書のインストールが必要です

<a href="https://github.com/Hycnyl/EhViewer/releases">
<img alt="Get it on GitHub" src="https://github.com/FooIbar/EhViewer-art/blob/master/get-it-on-github.svg" width="200px"/>
</a>

# スクリーンショット

![screenshots-01](https://github.com/FooIbar/EhViewer-art/blob/master/screenshots-01.webp)
![screenshots-02](https://github.com/FooIbar/EhViewer-art/blob/master/screenshots-02.webp)

# 謝辞

## 元のプロジェクトと開発者

- "FooIbar / EhViewer" (https://codeberg.org/EhViewer/EhViewer)：本フォークの直接の派生元です。本フォークはそのバージョン 1.14.6 をベースに開発を続けています。長年にわたり EhViewer の開発と保守に取り組んでくださった FooIbar とすべての貢献者に感謝します。
- "Hippo Seven（seven332）/ EhViewer" (https://github.com/seven332/EhViewer)：EhViewer の元のプロジェクトと作者であり、アプリの基礎を築きました。
- "NekoInverter / EhViewer" (https://gitlab.com/NekoInverter/EhViewer)：EhViewer の重要な後継フォークであり、プロジェクトの継続的な開発と近代化に貢献しました。
- Tarsin Norbin：EhViewer のその後の開発と保守における重要な貢献者です。
- "EhViewer Overhauled" (https://github.com/EhViewer-NekoInverter/EhViewer-Overhauled)：EhViewer の近代化を進めるうえで重要なフォークであり、Material Design 3 などの技術やインターフェースの採用を推進しました。

## その他のプロジェクトとコミュニティの貢献

- "Tachiyomi" (https://github.com/tachiyomiorg)：リーダーの実装と関連コードを提供してくださった Tachiyomi のオープンソースコミュニティに感謝します。
- "EhTagTranslation" (https://github.com/EhTagTranslation/Database)：中国語のタグ翻訳に尽力してくださったプロジェクトのメンテナーと翻訳の貢献者に感謝します。
- "FooIbar / EhViewer-art" (https://github.com/FooIbar/EhViewer-art)：本プロジェクトの README で参照しているアイコン、スクリーンショット、その他のビジュアル素材を提供しています。
- EhViewer のすべての貢献者：歴代の開発者、翻訳者、問題の報告者、コミュニティメンバーの貢献に感謝します。

## 主なオープンソースの依存関係

- "Android Open Source Project（AOSP）" (https://source.android.com/)、"AndroidX と Jetpack Compose" (https://developer.android.com/jetpack)
- "Kotlin と KotlinX" (https://kotlinlang.org/)
- "Arrow" (https://arrow-kt.io/)
- "Ktor" (https://ktor.io/)
- "OkHttp" (https://github.com/square/okhttp) と "Okio" (https://github.com/square/okio)
- "Coil" (https://coil-kt.github.io/coil/)
- "Compose Destinations" (https://github.com/raamcosta/compose-destinations)
- "Material Icons" (https://github.com/google/material-design-icons) と "MaterialKolor" (https://github.com/jordond/materialkolor)
- "Telephoto" (https://github.com/saket/telephoto)
- "moko-resources" (https://github.com/icerockdev/moko-resources)
- "AboutLibraries" (https://github.com/mikepenz/AboutLibraries)
- "libarchive" (https://github.com/libarchive/libarchive)
- "libwebp" (https://github.com/webmproject/libwebp)
- "Nettle" (https://www.lysator.liu.se/~nisse/nettle/) と "XZ Utils" (https://github.com/tukaani-project/xz)
- "Rust" (https://www.rust-lang.org/) と "Corrosion" (https://github.com/corrosion-rs/corrosion)

上記は主なプロジェクトの一部であり、依存関係の完全な一覧ではありません。各コンポーネントの著作権、ライセンス、その他の関連する声明については、プロジェクトのソースコード、"LICENSE"、"NOTICE"、およびアプリ内のオープンソースライセンス情報をご確認ください。

EhViewer の誕生、継続、発展を支えてくださったすべての貢献者に、改めて感謝します。

# ライセンス

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    EhViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    EhViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
