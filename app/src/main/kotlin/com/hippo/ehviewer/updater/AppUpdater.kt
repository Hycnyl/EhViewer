package com.hippo.ehviewer.updater

import com.ehviewer.core.files.write
import com.hippo.ehviewer.BuildConfig
import com.hippo.ehviewer.EhApplication.Companion.ktorClient
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.executeAndParseAs
import com.hippo.ehviewer.spider.timeoutBySpeed
import com.hippo.ehviewer.util.copyTo
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.util.zip.ZipInputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.io.asSource
import okio.Path
import tachiyomi.data.release.GithubRelease
import tachiyomi.data.release.GithubRepo
import tachiyomi.data.release.GithubWorkflowRuns

private const val API_URL = "https://api.github.com/repos/${BuildConfig.REPO_NAME}"

object AppUpdater {
    suspend fun checkForUpdate(forceCheck: Boolean = false): Release? {
        val now = Clock.System.now()
        val last = Instant.fromEpochSeconds(Settings.lastUpdateTime)
        val interval = Settings.updateIntervalDays.value
        if (forceCheck || interval != 0 && now > last + interval.days) {
            Settings.lastUpdateTime = now.epochSeconds
            val releases = ghStatement("$API_URL/releases?per_page=30").executeAndParseAs<List<GithubRelease>>().filterNot { it.draft }
            val release = if (Settings.useCIUpdateChannel.value) {
                val branch = ghStatement(API_URL).executeAndParseAs<GithubRepo>().defaultBranch
                val workflowUrl = "$API_URL/actions/workflows/cache-rescue.yml/runs?branch=$branch&event=push&status=success&per_page=1"
                val run = ghStatement(workflowUrl).executeAndParseAs<GithubWorkflowRuns>().workflowRuns.firstOrNull() ?: return null
                releases.firstOrNull { it.targetCommitish == run.headSha } ?: return null
            } else {
                // This fork publishes preview APKs too. /releases/latest alone returns 404
                // until the first stable release, so use published previews as a fallback.
                releases.firstOrNull { !it.prerelease } ?: releases.firstOrNull() ?: return null
            }
            if (isNewerUpdate(BuildConfig.RAW_VERSION_NAME, release.version, BuildConfig.COMMIT_SHA, release.targetCommitish)) {
                return Release(release.version, release.info.orEmpty(), release.getDownloadLink())
            }
        }
        return null
    }

    suspend fun downloadUpdate(url: String, path: Path) {
        val isZip = url.endsWith("zip")
        timeoutBySpeed(
            url,
            {
                ghStatement(url) {
                    // https://docs.github.com/en/rest/releases/assets?apiVersion=2022-11-28#get-a-release-asset
                    if (!isZip) accept(ContentType.Application.OctetStream)
                    it()
                }
            },
            { _, _, _ -> },
            { response ->
                if (isZip) {
                    response.bodyAsChannel().toInputStream().use { stream ->
                        ZipInputStream(stream).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null && (!entry.name.endsWith(".apk") || !com.hippo.ehviewer.util.AppConfig.matchVariant(entry.name.substringAfterLast('/')))) entry = zip.nextEntry
                            check(entry != null) { "No compatible APK in update archive" }
                            path.write { transferFrom(zip.asSource()) }
                        }
                    }
                } else {
                    response.bodyAsChannel().copyTo(path)
                }
            },
        )
    }
}

private suspend inline fun ghStatement(
    url: String,
    builder: HttpRequestBuilder.() -> Unit = {},
) = ktorClient.prepareGet(url) {
    apply(builder)
}

data class Release(
    val version: String,
    val changelog: String,
    val downloadLink: String,
)
