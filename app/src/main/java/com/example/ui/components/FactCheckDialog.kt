package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ai.FactCheckVerdict
import com.example.data.model.Flashcard
import com.example.ui.viewmodel.FactCheckUiState

@Composable
fun FactCheckDialog(
    state: FactCheckUiState,
    onDismiss: () -> Unit,
    onApplyAnswer: (card: Flashcard, improvedAnswer: String, improvedExplanation: String) -> Unit,
    onOpenAiSettings: () -> Unit
) {
    val context = LocalContext.current

    when (state) {
        FactCheckUiState.Idle -> Unit

        is FactCheckUiState.Checking -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(id = R.string.fact_check_verifying_title)) },
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(id = R.string.fact_check_verifying_body, state.card.question.take(80)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(id = R.string.common_cancel))
                    }
                }
            )
        }

        is FactCheckUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = {
                    Text(
                        text = if (state.isQuotaLimit) {
                            stringResource(id = R.string.fact_check_quota_title)
                        } else {
                            stringResource(id = R.string.fact_check_error_title)
                        }
                    )
                },
                text = {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    if (state.isQuotaLimit) {
                        Button(
                            onClick = {
                                onDismiss()
                                onOpenAiSettings()
                            },
                            modifier = Modifier.testTag("fact_check_open_settings_button")
                        ) {
                            Text(stringResource(id = R.string.fact_check_add_byok))
                        }
                    } else {
                        Button(onClick = onDismiss) {
                            Text(stringResource(id = R.string.fact_check_ok))
                        }
                    }
                },
                dismissButton = {
                    if (state.isQuotaLimit) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(id = R.string.fact_check_later))
                        }
                    }
                }
            )
        }

        is FactCheckUiState.Ready -> {
            val result = state.result
            val badgeContainer = when (result.verdict) {
                FactCheckVerdict.VERIFIED -> MaterialTheme.colorScheme.secondaryContainer
                FactCheckVerdict.PARTIAL_NUANCE -> MaterialTheme.colorScheme.tertiaryContainer
                FactCheckVerdict.NEEDS_CORRECTION -> MaterialTheme.colorScheme.errorContainer
            }
            val badgeContent = when (result.verdict) {
                FactCheckVerdict.VERIFIED -> MaterialTheme.colorScheme.onSecondaryContainer
                FactCheckVerdict.PARTIAL_NUANCE -> MaterialTheme.colorScheme.onTertiaryContainer
                FactCheckVerdict.NEEDS_CORRECTION -> MaterialTheme.colorScheme.onErrorContainer
            }
            val badgeIcon = when (result.verdict) {
                FactCheckVerdict.VERIFIED -> Icons.Default.Verified
                FactCheckVerdict.PARTIAL_NUANCE -> Icons.Default.Info
                FactCheckVerdict.NEEDS_CORRECTION -> Icons.Default.ErrorOutline
            }

            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = badgeContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = badgeIcon,
                                    contentDescription = null,
                                    tint = badgeContent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = result.verdict.badgeLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeContent
                                )
                            }
                        }
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = result.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = stringResource(id = R.string.fact_check_suggested_answer),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = result.improvedAnswer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (result.detailedExplanation.isNotBlank()) {
                            Text(
                                text = result.detailedExplanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (result.sources.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(id = R.string.fact_check_sources),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                result.sources.forEach { source ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(source.url))
                                                    )
                                                }
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = source.title,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(
                                                imageVector = Icons.Default.OpenInNew,
                                                contentDescription = stringResource(id = R.string.fact_check_cd_open_citation),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = result.poweredByLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    val hasChanges = !result.improvedAnswer.equals(state.card.answer, ignoreCase = true)
                    if (hasChanges) {
                        Button(
                            onClick = {
                                onApplyAnswer(
                                    state.card,
                                    result.improvedAnswer,
                                    result.detailedExplanation.ifBlank { state.card.explanation }
                                )
                            },
                            modifier = Modifier.testTag("fact_check_apply_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(id = R.string.fact_check_apply_answer))
                        }
                    } else {
                        Button(onClick = onDismiss) {
                            Text(stringResource(id = R.string.fact_check_done))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(id = R.string.common_close))
                    }
                }
            )
        }
    }
}
