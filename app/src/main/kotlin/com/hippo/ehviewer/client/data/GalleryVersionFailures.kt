package com.hippo.ehviewer.client.data

import com.hippo.ehviewer.client.exception.CloudflareBypassException
import com.hippo.ehviewer.client.exception.IpBannedException
import com.hippo.ehviewer.client.exception.NotLoggedInException
import com.hippo.ehviewer.client.exception.ParseException
import io.ktor.client.plugins.ResponseException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/** Transport-only exception. UI state receives its code, never the exception or response body. */
internal class GalleryVersionRequestException(val code: GalleryVersionIssueCode, cause: Throwable? = null) : Exception(null, cause)

internal fun galleryVersionHttpFailure(status: Int, isLogin: Boolean, cloudflare: Boolean): GalleryVersionIssueCode? = when {
    cloudflare -> GalleryVersionIssueCode.CLOUDFLARE_CHALLENGE
    isLogin || status == 401 -> GalleryVersionIssueCode.LOGIN_REQUIRED
    status !in 200..299 -> GalleryVersionIssueCode.HTTP_ERROR
    else -> null
}

internal fun galleryVersionFailureCode(error: Exception): GalleryVersionIssueCode {
    val causes = generateSequence(error as Throwable) { it.cause }.take(16).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    for (cause in causes) {
        when (cause) {
            is GalleryVersionRequestException -> return cause.code
            is NotLoggedInException -> return GalleryVersionIssueCode.LOGIN_REQUIRED
            is CloudflareBypassException -> return GalleryVersionIssueCode.CLOUDFLARE_CHALLENGE
            is IpBannedException -> return GalleryVersionIssueCode.IP_BANNED
            is ResponseException -> return galleryVersionHttpFailure(cause.response.status.value, false, cause.response.headers["cf-mitigated"] == "challenge") ?: GalleryVersionIssueCode.HTTP_ERROR
        }
    }
    return when {
        causes.any { it is ParseException || it is SerializationException } -> GalleryVersionIssueCode.PARSE_ERROR
        causes.any { it is IOException } -> GalleryVersionIssueCode.NETWORK_ERROR
        else -> GalleryVersionIssueCode.UNKNOWN_ERROR
    }
}

internal val GalleryVersionIssueCode.retryRecommended: Boolean
    get() = this !in setOf(GalleryVersionIssueCode.TOKEN_INVALID, GalleryVersionIssueCode.CANDIDATE_UNLINKED, GalleryVersionIssueCode.CYCLE_DETECTED)

internal fun mergeGalleryVersionIssues(issues: List<GalleryVersionIssue>): List<GalleryVersionIssue> {
    if (issues.isEmpty()) return emptyList()
    val priority = listOf(
        GalleryVersionIssueCode.LOGIN_REQUIRED, GalleryVersionIssueCode.IP_BANNED,
        GalleryVersionIssueCode.CLOUDFLARE_CHALLENGE, GalleryVersionIssueCode.NETWORK_ERROR,
        GalleryVersionIssueCode.HTTP_ERROR, GalleryVersionIssueCode.PARSE_ERROR,
        GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED, GalleryVersionIssueCode.TOKEN_INVALID,
        GalleryVersionIssueCode.API_ROW_ERROR, GalleryVersionIssueCode.CYCLE_DETECTED,
        GalleryVersionIssueCode.GND_MISSING, GalleryVersionIssueCode.METADATA_MISSING,
        GalleryVersionIssueCode.PARENT_UNKNOWN, GalleryVersionIssueCode.ROLE_UNRESOLVED,
        GalleryVersionIssueCode.CANDIDATE_UNLINKED, GalleryVersionIssueCode.UNKNOWN_ERROR,
    )
    return issues.groupBy { it.stage to it.code }.map { (key, entries) ->
        GalleryVersionIssue(key.first, key.second, entries.flatMap { it.affectedGids }.distinct(), entries.any { it.retryable })
    }.sortedWith(compareBy({ priority.indexOf(it.code) }, { it.stage.ordinal }))
}
