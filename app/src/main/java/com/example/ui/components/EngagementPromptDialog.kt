package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StarRate
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.data.analytics.AutoPopupSuggestion
import com.example.data.analytics.AutoPopupType
import com.example.data.analytics.EngagementTelemetryState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper to open the app's Google Play listing safely (market:// with HTTPS web fallback).
 */
fun openPlayStoreListing(context: Context) {
    val pkg = context.packageName
    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(marketIntent) }
        .onFailure { runCatching { context.startActivity(webIntent) } }
}

/**
 * Helper to share the app via native Android Share Sheet (zero extra permissions).
 */
fun launchShareAppSheet(context: Context) {
    val storeUrl = "https://play.google.com/store/apps/details?id=${context.packageName}"
    val shareBody = context.getString(R.string.share_app_message, storeUrl)
    val chooserTitle = context.getString(R.string.share_app_chooser_title)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, chooserTitle)
        putExtra(Intent.EXTRA_TEXT, shareBody)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}

/**
 * Non-intrusive Material 3 auto-popup dialog for App Update, Rate App, or Share App suggestions.
 * Complies with Google Play policies (never incentivizes reviews or requests specific star ratings).
 */
@Composable
fun EngagementPromptDialog(
    suggestion: AutoPopupSuggestion?,
    onAccept: (AutoPopupSuggestion) -> Unit,
    onDismissLater: (AutoPopupSuggestion) -> Unit,
    onOptOut: (AutoPopupSuggestion) -> Unit,
    onOpenDonationSheet: (milestoneCount: Int?) -> Unit = {}
) {
    if (suggestion == null) return
    val context = LocalContext.current

    val icon = when (suggestion.type) {
        AutoPopupType.UPDATE_APP -> Icons.Default.SystemUpdate
        AutoPopupType.RATE_APP -> Icons.Default.StarRate
        AutoPopupType.SHARE_APP -> Icons.Default.Share
        AutoPopupType.DONATE_SUPPORT -> Icons.Default.LocalCafe
    }

    val title = when (suggestion.type) {
        AutoPopupType.UPDATE_APP -> stringResource(R.string.popup_update_title)
        AutoPopupType.RATE_APP -> stringResource(R.string.popup_rate_title)
        AutoPopupType.SHARE_APP -> stringResource(R.string.popup_share_title)
        AutoPopupType.DONATE_SUPPORT -> if (suggestion.milestoneCount != null) {
            stringResource(R.string.support_dev_milestone_heading, suggestion.milestoneCount)
        } else {
            stringResource(R.string.popup_donate_default_title)
        }
    }

    val body = when (suggestion.type) {
        AutoPopupType.UPDATE_APP -> stringResource(
            R.string.popup_update_body,
            suggestion.targetVersionName ?: stringResource(R.string.popup_update_latest_fallback)
        )
        AutoPopupType.RATE_APP -> stringResource(R.string.popup_rate_body)
        AutoPopupType.SHARE_APP -> stringResource(R.string.popup_share_body)
        AutoPopupType.DONATE_SUPPORT -> if (suggestion.milestoneCount != null) {
            stringResource(R.string.popup_donate_milestone_body, suggestion.milestoneCount)
        } else {
            stringResource(R.string.popup_donate_default_body)
        }
    }

    val primaryLabel = when (suggestion.type) {
        AutoPopupType.UPDATE_APP -> stringResource(R.string.popup_update_primary)
        AutoPopupType.RATE_APP -> stringResource(R.string.popup_rate_primary)
        AutoPopupType.SHARE_APP -> stringResource(R.string.popup_share_primary)
        AutoPopupType.DONATE_SUPPORT -> stringResource(R.string.popup_donate_primary)
    }

    val laterLabel = when (suggestion.type) {
        AutoPopupType.UPDATE_APP -> stringResource(R.string.popup_update_later)
        AutoPopupType.RATE_APP -> stringResource(R.string.popup_rate_later)
        AutoPopupType.SHARE_APP -> stringResource(R.string.popup_share_later)
        AutoPopupType.DONATE_SUPPORT -> stringResource(R.string.popup_rate_later)
    }

    val optOutLabel = when (suggestion.type) {
        AutoPopupType.UPDATE_APP -> stringResource(R.string.popup_update_skip)
        AutoPopupType.RATE_APP -> stringResource(R.string.popup_rate_never)
        AutoPopupType.SHARE_APP -> stringResource(R.string.popup_share_never)
        AutoPopupType.DONATE_SUPPORT -> stringResource(R.string.popup_rate_never)
    }

    AlertDialog(
        onDismissRequest = { onDismissLater(suggestion) },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { onOptOut(suggestion) },
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag("engagement_popup_opt_out")
                ) {
                    Text(
                        text = optOutLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (suggestion.type) {
                        AutoPopupType.UPDATE_APP, AutoPopupType.RATE_APP -> openPlayStoreListing(context)
                        AutoPopupType.SHARE_APP -> launchShareAppSheet(context)
                        AutoPopupType.DONATE_SUPPORT -> onOpenDonationSheet(suggestion.milestoneCount)
                    }
                    onAccept(suggestion)
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("engagement_popup_accept")
            ) {
                Text(primaryLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onDismissLater(suggestion) },
                modifier = Modifier.testTag("engagement_popup_later")
            ) {
                Text(laterLabel)
            }
        }
    )
}

/**
 * Settings card for manual Update Check, Rate App, Share App, Privacy Policy,
 * and an expandable on-device Event Log showing real tracked telemetry & anti-spam guardrails.
 */
@Composable
fun EngagementCommunityCard(
    versionName: String,
    telemetry: EngagementTelemetryState,
    onCheckForUpdate: () -> Unit,
    onManualRate: () -> Unit,
    onManualShare: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit
) {
    val context = LocalContext.current
    var showEventLog by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Analytics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_community_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_community_subtitle, versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick manual actions row 1: Check Update + Rate App
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalButton(
                    onClick = onCheckForUpdate,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("settings_check_update_button")
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_check_update), maxLines = 1)
                }
                FilledTonalButton(
                    onClick = {
                        openPlayStoreListing(context)
                        onManualRate()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("settings_rate_app_button")
                ) {
                    Icon(Icons.Default.StarRate, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_rate_app), maxLines = 1)
                }
            }

            // Quick manual actions row 2: Share App + Privacy Policy
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = {
                        launchShareAppSheet(context)
                        onManualShare()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("settings_share_app_button")
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_share_app), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onOpenPrivacyPolicy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("settings_privacy_policy_button")
                ) {
                    Icon(Icons.Default.Policy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_privacy_policy), maxLines = 1)
                }
            }

            // Expandable on-device Event Tracking & Guardrail Log
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(
                            R.string.settings_telemetry_summary,
                            telemetry.sessionCount,
                            telemetry.distinctActiveDays,
                            telemetry.positiveScore
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_telemetry_guardrails),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = { showEventLog = !showEventLog },
                        modifier = Modifier.testTag("settings_toggle_event_log_button")
                    ) {
                        Icon(
                            imageVector = if (showEventLog) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showEventLog) {
                                stringResource(R.string.settings_telemetry_toggle_hide)
                            } else {
                                stringResource(R.string.settings_telemetry_toggle_show, telemetry.recentEvents.size)
                            }
                        )
                    }

                    AnimatedVisibility(visible = showEventLog) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            telemetry.recentEvents.take(10).forEach { ev ->
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "${ev.type.label}${if (ev.detail.isNotBlank()) " · ${ev.detail.take(28)}" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = timeFmt.format(Date(ev.timestampMs)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * In-app Privacy Policy viewer rendering the bundled localized HTML asset cleanly.
 */
@Composable
fun PrivacyPolicyDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.privacy_policy_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        loadUrl("file:///android_asset/privacy_policy.html")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp, max = 440.dp)
                    .testTag("privacy_policy_webview")
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("privacy_policy_close_button")
            ) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}
