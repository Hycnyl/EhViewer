> The version of EhViewer you are viewing is based on FooIbar’s EhViewer 1.14.6. Based on my own experience using it, I have added some new features and adjusted some existing ones. Please note that I am not a professional developer; all changes were made by GPT-6.1 Sol(Max). I cannot and will not meet every user’s needs, cannot guarantee that I can resolve issues that may arise while the software is running, and may introduce destructive new features at any time. I do not recommend this version of EhViewer to anyone seeking the utmost stability.

> This version of EhViewer uses a different package name and can coexist with the [“FooIbar version”](https://github.com/FooIbar/EhViewer/releases) you may already be using, but this also means you will need to reconfigure some settings.

<p align="center">
  <strong>English</strong>
  <span> | </span>
  <a href="/docs/README/zh-cn.md">
  简体中文
  </a>
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
    <a href="#changes">
    Changes
    </a>
    <span> | </span>
    <a href="#download">
    Download
    </a>
    <span> | </span>
    <a href="#screenshots">
    Screenshots
    </a>
    <span> | </span>
    <a href="#acknowledgments">
    Acknowledgments
    </a>
    <span> | </span>
    <a href="#license">
    License
    </a>
  </h3>
</div>

# Changes

> Please note that all changes are relative to FooIbar’s EhViewer 1.14.6.

## Added

### Export cached pages

- Location: Gallery details → Three-dot menu in the upper right → “Export cached pages”.

- Function: When a gallery has been deleted, images cannot be retrieved normally, or similar situations occur, users can try to export cached images that still exist locally as individual images or a CBZ archive and add the gallery to the built-in download manager. **Exporting as individual images is always recommended.**

### [Experimental] Clear reading cache

- Location: Settings → Advanced → [Experimental] Clear reading cache.

- Function: Allows users to manually delete cached data within a specified time range. **Filtering is based on the last modification time of Coil cache files, rather than when the user last read the gallery.**

## Adjusted

### Custom reading cache size

- Location: Settings → Advanced → Reading cache size → Custom.

- Function: Allows larger cache sizes and accepts decimal values. **There is no maximum input value, but the minimum is 5.**

### Consistent tap behavior in the download list

- Location: Not applicable.

- Function: Tapping either the image on the left or the title on the right in the download list now opens the gallery first.

### Archive download link handling

- Location: Not applicable.

- Function: When an archive download is tapped, the remote download URL now opens as a web link rather than application/zip, resolving the issue where Android might pass the download URL to an unsuitable app such as a music player or file manager.

### Improved interactions on the ranking page

- Location: Not applicable.

- Function: On the ranking page, the bookmark icon in the upper right has been replaced with a numbered list icon, which better matches its function of switching between rankings.

## Other

- Download filter controls improved.

- Settings categories reorganized.

- Package name: Changed to `moe.hycnyl.ehviewer`;

- About page: Relevant links now point to this repository.

# Download

| Flavor      | Minimum Android Version | Notes                          |
|-------------|---------------|----------|
| Default     | 8.0                     | Full support                   |
| Marshmallow | 6.0*                    | Limited support, no guarantees |

*Devices running Android 6 need to have the [ISRG Root X1](https://letsencrypt.org/certs/isrgrootx1.pem) certificate installed

<a href="https://github.com/Hycnyl/EhViewer/releases">
<img alt="Get it on GitHub" src="https://github.com/FooIbar/EhViewer-art/blob/master/get-it-on-github.svg" width="200px"/>
</a>

# Screenshots

![screenshots-01](https://github.com/FooIbar/EhViewer-art/blob/master/screenshots-01.webp)
![screenshots-02](https://github.com/FooIbar/EhViewer-art/blob/master/screenshots-02.webp)

# Acknowledgments

## Original Projects and Developers

- "FooIbar / EhViewer" (https://codeberg.org/EhViewer/EhViewer): The direct upstream of this fork, which continues development from version 1.14.6. Thanks to FooIbar and all contributors for their long-standing development and maintenance of EhViewer.
- "Hippo Seven (seven332) / EhViewer" (https://github.com/seven332/EhViewer): The original EhViewer project and its author, who laid the foundation for the app.
- "NekoInverter / EhViewer" (https://gitlab.com/NekoInverter/EhViewer): An important subsequent fork of EhViewer that contributed to the project’s continued development and modernization.
- Tarsin Norbin: An important contributor to EhViewer’s subsequent development and maintenance.
- "EhViewer Overhauled" (https://github.com/EhViewer-NekoInverter/EhViewer-Overhauled): An important fork in EhViewer’s modernization that advanced the adoption of technologies and interfaces such as Material Design 3.

## Other Projects and Community Contributions

- "Tachiyomi" (https://github.com/tachiyomiorg): Thanks to the Tachiyomi open-source community for the reader implementation and related code.
- "EhTagTranslation" (https://github.com/EhTagTranslation/Database): Thanks to the project maintainers and translation contributors for their efforts in translating tags into Chinese.
- "FooIbar / EhViewer-art" (https://github.com/FooIbar/EhViewer-art): Provides the icons, screenshots, and other visual assets referenced in this project’s README.
- All EhViewer contributors: Thanks to developers, translators, issue reporters, and community members throughout the project’s history for their contributions.

## Main Open-Source Dependencies

- "Android Open Source Project (AOSP)" (https://source.android.com/), "AndroidX and Jetpack Compose" (https://developer.android.com/jetpack)
- "Kotlin and KotlinX" (https://kotlinlang.org/)
- "Arrow" (https://arrow-kt.io/)
- "Ktor" (https://ktor.io/)
- "OkHttp" (https://github.com/square/okhttp) and "Okio" (https://github.com/square/okio)
- "Coil" (https://coil-kt.github.io/coil/)
- "Compose Destinations" (https://github.com/raamcosta/compose-destinations)
- "Material Icons" (https://github.com/google/material-design-icons) and "MaterialKolor" (https://github.com/jordond/materialkolor)
- "Telephoto" (https://github.com/saket/telephoto)
- "moko-resources" (https://github.com/icerockdev/moko-resources)
- "AboutLibraries" (https://github.com/mikepenz/AboutLibraries)
- "libarchive" (https://github.com/libarchive/libarchive)
- "libwebp" (https://github.com/webmproject/libwebp)
- "Nettle" (https://www.lysator.liu.se/~nisse/nettle/) and "XZ Utils" (https://github.com/tukaani-project/xz)
- "Rust" (https://www.rust-lang.org/) and "Corrosion" (https://github.com/corrosion-rs/corrosion)

Only some of the main projects are listed above; this is not a complete list of dependencies. For each component’s copyright, license, and other relevant notices, please refer to the project’s source code, "LICENSE", "NOTICE", and the open-source license information in the app.

Thanks again to everyone whose contributions have made EhViewer’s creation, continuation, and development possible.

# License

    Copyright 2014-2019 Hippo Seven
    Copyright 2020-2022 NekoInverter
    Copyright 2022-2023 Tarsin Norbin
    Copyright 2023-2024 Foolbar

    EhViewer is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

    EhViewer is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with EhViewer. If not, see <https://www.gnu.org/licenses/>.
