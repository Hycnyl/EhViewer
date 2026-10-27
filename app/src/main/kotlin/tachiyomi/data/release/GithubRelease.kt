package tachiyomi.data.release

import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.updater.chooseUpdateApkName
import com.hippo.ehviewer.util.AppConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contains information about the latest release from GitHub.
 */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val version: String,
    @SerialName("body") val info: String? = null,
    @SerialName("html_url") val releaseLink: String,
    @SerialName("assets") val assets: List<GitHubAssets>,
    @SerialName("target_commitish") val targetCommitish: String? = null,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
) {
    fun getDownloadLink(abi: String = AppConfig.abi, flavor: String = BuildConfig.FLAVOR): String {
        val name = chooseUpdateApkName(assets.map { it.name }, abi, flavor) ?: error("No compatible APK in this release")
        val asset = assets.first { it.name == name }
        return asset.browserDownloadUrl ?: asset.url
    }
}

/**
 * Assets class containing download url.
 */
@Serializable
data class GitHubAssets(val url: String, val name: String, @SerialName("browser_download_url") val browserDownloadUrl: String? = null)
