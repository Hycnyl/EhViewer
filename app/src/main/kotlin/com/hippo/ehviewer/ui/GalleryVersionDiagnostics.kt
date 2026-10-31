package com.hippo.ehviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.client.data.GalleryVersionDiagnostics
import com.hippo.ehviewer.client.data.GalleryVersionIssue
import com.hippo.ehviewer.client.data.GalleryVersionIssueCode
import com.hippo.ehviewer.client.data.GalleryVersionStage

@Composable
internal fun galleryVersionStageLabel(stage: GalleryVersionStage): String = stringResource(
    when (stage) {
        GalleryVersionStage.KEY_METADATA -> R.string.gallery_version_stage_keys
        GalleryVersionStage.FIRST_HTML -> R.string.gallery_version_stage_first
        GalleryVersionStage.CANDIDATE_METADATA -> R.string.gallery_version_stage_candidates
        GalleryVersionStage.PARENT_TRACE -> R.string.gallery_version_stage_trace
        GalleryVersionStage.CHAIN_VALIDATION -> R.string.gallery_version_stage_validation
    },
)

@Composable
private fun issueLabel(code: GalleryVersionIssueCode): String = stringResource(
    when (code) {
        GalleryVersionIssueCode.NETWORK_ERROR -> R.string.gallery_version_issue_network
        GalleryVersionIssueCode.HTTP_ERROR -> R.string.gallery_version_issue_http
        GalleryVersionIssueCode.LOGIN_REQUIRED -> R.string.gallery_version_issue_login
        GalleryVersionIssueCode.IP_BANNED -> R.string.gallery_version_issue_ip
        GalleryVersionIssueCode.CLOUDFLARE_CHALLENGE -> R.string.gallery_version_issue_cloudflare
        GalleryVersionIssueCode.PARSE_ERROR -> R.string.gallery_version_issue_parse
        GalleryVersionIssueCode.GND_MISSING -> R.string.gallery_version_issue_gnd
        GalleryVersionIssueCode.API_ROW_ERROR -> R.string.gallery_version_issue_api
        GalleryVersionIssueCode.METADATA_MISSING -> R.string.gallery_version_issue_metadata
        GalleryVersionIssueCode.TOKEN_INVALID -> R.string.gallery_version_issue_token
        GalleryVersionIssueCode.REQUEST_BUDGET_EXHAUSTED -> R.string.gallery_version_issue_budget
        GalleryVersionIssueCode.PARENT_UNKNOWN -> R.string.gallery_version_issue_parent
        GalleryVersionIssueCode.CANDIDATE_UNLINKED -> R.string.gallery_version_issue_unlinked
        GalleryVersionIssueCode.CYCLE_DETECTED -> R.string.gallery_version_issue_cycle
        GalleryVersionIssueCode.ROLE_UNRESOLVED -> R.string.gallery_version_issue_roles
        GalleryVersionIssueCode.UNKNOWN_ERROR -> R.string.gallery_version_issue_unknown
    },
)

@Composable
private fun retryAdvice(issue: GalleryVersionIssue) = stringResource(if (issue.retryable) R.string.gallery_version_retry_recommended else R.string.gallery_version_retry_unnecessary)

@Composable
private fun issueSummary(issue: GalleryVersionIssue) = galleryVersionStageLabel(issue.stage) + " · " + issueLabel(issue.code)

@Composable
internal fun GalleryVersionDiagnosticsContent(diagnostics: GalleryVersionDiagnostics) {
    val primary = diagnostics.issues.firstOrNull() ?: return
    var details by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(issueSummary(primary), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (primary.affectedGids.isNotEmpty()) stringResource(R.string.gallery_version_affected_count, primary.affectedGids.size) else retryAdvice(primary),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { details = !details }) {
                Text(stringResource(if (details) R.string.gallery_version_hide_diagnostics else R.string.gallery_version_show_diagnostics), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (details) {
            if (primary.affectedGids.isNotEmpty()) Text(retryAdvice(primary), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            diagnostics.pendingMetadataCount?.let { pending ->
                Text(stringResource(R.string.gallery_version_budget_details, diagnostics.requestBudget, diagnostics.requestsUsed, pending), style = MaterialTheme.typography.bodySmall)
            }
            diagnostics.issues.drop(1).forEach { issue ->
                HorizontalDivider()
                Text(issueSummary(issue), style = MaterialTheme.typography.bodySmall)
                val count = if (issue.affectedGids.isNotEmpty()) stringResource(R.string.gallery_version_affected_count, issue.affectedGids.size) + " · " else ""
                Text(count + retryAdvice(issue), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
