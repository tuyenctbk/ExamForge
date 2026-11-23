package com.example.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.donation.BillingConnectionStatus
import com.example.data.donation.DonationBillingUiState
import com.example.data.donation.DonationChannel
import com.example.data.donation.DonationConfig
import com.example.data.donation.DonationTier
import com.example.data.donation.PayPalPreset
import com.example.data.donation.QrCodeGenerator
import com.example.data.donation.copyTextToClipboard
import com.example.data.donation.findActivity
import com.example.data.donation.openUrlWithClipboardFallback
import com.example.data.donation.shareDonationLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multi-channel voluntary donation bottom sheet:
 * - Google Play In-App Purchase (4 tiers via live Activity-bound Play Billing)
 * - PayPal & Live QR Code (presets + custom USD + scannable QR + copy/share)
 * - Ko-fi Web Tip Jar (ACTION_VIEW with clipboard fallback)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonationSheetDialog(
    visible: Boolean,
    hostActivity: Activity?,
    isActiveSupporter: Boolean,
    milestoneCount: Int? = null,
    billingState: DonationBillingUiState,
    onPurchaseGooglePlay: (Activity?, DonationTier) -> Unit,
    onRestorePurchases: () -> Unit,
    onExternalChannelUsed: (channel: String, detail: String) -> Unit,
    onOptOutReminders: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    val resolvedActivity = remember(hostActivity, context) {
        hostActivity ?: context.findActivity()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedChannel by remember { mutableStateOf(DonationChannel.GOOGLE_PLAY) }
    var selectedTier by remember { mutableStateOf(DonationTier.TIER_2) }
    var selectedPayPalPreset by remember { mutableStateOf(PayPalPreset.USD_5) }
    var customPayPalAmount by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("donation_bottom_sheet")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isActiveSupporter) Icons.Default.Verified else Icons.Default.LocalCafe,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isActiveSupporter -> stringResource(id = R.string.support_dev_active_supporter)
                            milestoneCount != null -> stringResource(id = R.string.support_dev_milestone_heading, milestoneCount)
                            else -> stringResource(id = R.string.support_dev_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isActiveSupporter) {
                            stringResource(id = R.string.support_dev_thanks_subtitle)
                        } else {
                            stringResource(id = R.string.support_dev_voluntary_subtitle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isActiveSupporter) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(id = R.string.support_dev_active_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            val channels = DonationChannel.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                channels.forEachIndexed { idx, channel ->
                    val label = when (channel) {
                        DonationChannel.GOOGLE_PLAY -> stringResource(id = R.string.support_dev_google_play_tab)
                        DonationChannel.PAYPAL -> stringResource(id = R.string.support_dev_paypal_tab)
                        DonationChannel.KOFI -> stringResource(id = R.string.support_dev_kofi_tab)
                    }
                    SegmentedButton(
                        selected = selectedChannel == channel,
                        onClick = { selectedChannel = channel },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = channels.size),
                        modifier = Modifier.testTag("donation_tab_${channel.name.lowercase()}"),
                        icon = {}
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            when (selectedChannel) {
                DonationChannel.GOOGLE_PLAY -> {
                    GooglePlayDonationTab(
                        selectedTier = selectedTier,
                        billingState = billingState,
                        onSelectTier = { selectedTier = it },
                        onPurchase = { onPurchaseGooglePlay(resolvedActivity, selectedTier) },
                        onRestore = onRestorePurchases,
                        onSwitchToPayPal = { selectedChannel = DonationChannel.PAYPAL }
                    )
                }

                DonationChannel.PAYPAL -> {
                    PayPalQrDonationTab(
                        selectedPreset = selectedPayPalPreset,
                        customAmount = customPayPalAmount,
                        onPresetSelected = { preset ->
                            selectedPayPalPreset = preset
                            if (preset != PayPalPreset.ANY) customPayPalAmount = ""
                        },
                        onCustomAmountChanged = { input ->
                            val filtered = input.filter { it.isDigit() || it == '.' }.take(7)
                            customPayPalAmount = filtered
                            selectedPayPalPreset = PayPalPreset.ANY
                        },
                        onExternalAction = onExternalChannelUsed
                    )
                }

                DonationChannel.KOFI -> {
                    KofiDonationTab(onExternalAction = onExternalChannelUsed)
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (onOptOutReminders != null && !isActiveSupporter) {
                    TextButton(
                        onClick = {
                            onOptOutReminders()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("donation_opt_out_button")
                    ) {
                        Text(
                            text = stringResource(id = R.string.support_dev_dont_ask),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("donation_close_button")
                ) {
                    Text(stringResource(id = R.string.common_close))
                }
            }
        }
    }
}

@Composable
internal fun GooglePlayDonationTab(
    selectedTier: DonationTier,
    billingState: DonationBillingUiState,
    onSelectTier: (DonationTier) -> Unit,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onSwitchToPayPal: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(id = R.string.support_dev_play_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val rows = DonationTier.entries.chunked(2)
        rows.forEach { rowTiers ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowTiers.forEach { tier ->
                    val isSelected = selectedTier == tier
                    val price = billingState.localizedPrices[tier.productId] ?: tier.fallbackPrice
                    val tierTitle = localizedTierTitle(tier)
                    val tierSubtitle = localizedTierSubtitle(tier)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectTier(tier) }
                            .testTag("donation_tier_${tier.name.lowercase()}")
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = price,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = tierTitle,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = tierSubtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (!billingState.statusMessage.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = billingState.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (!billingState.lastError.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = billingState.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = onSwitchToPayPal,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(stringResource(id = R.string.support_dev_play_try_paypal))
                    }
                }
            }
        }

        val activePrice = billingState.localizedPrices[selectedTier.productId] ?: selectedTier.fallbackPrice
        Button(
            onClick = onPurchase,
            enabled = !billingState.isPurchasing,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("donation_play_purchase_button")
        ) {
            if (billingState.isPurchasing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(id = R.string.support_dev_play_opening))
            } else {
                Icon(
                    imageVector = Icons.Default.ShoppingBag,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.support_dev_play_cta, activePrice),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val statusLabel = when (billingState.connectionStatus) {
                BillingConnectionStatus.READY -> stringResource(id = R.string.support_dev_play_status_ready)
                BillingConnectionStatus.CONNECTING -> stringResource(id = R.string.support_dev_play_status_connecting)
                BillingConnectionStatus.UNAVAILABLE -> stringResource(id = R.string.support_dev_play_status_unavailable)
                BillingConnectionStatus.IDLE -> stringResource(id = R.string.support_dev_play_status_idle)
            }
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onRestore,
                modifier = Modifier.testTag("donation_restore_purchases_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(id = R.string.support_dev_restore_supporter))
            }
        }
    }
}

@Composable
internal fun PayPalQrDonationTab(
    selectedPreset: PayPalPreset,
    customAmount: String,
    onPresetSelected: (PayPalPreset) -> Unit,
    onCustomAmountChanged: (String) -> Unit,
    onExternalAction: (channel: String, detail: String) -> Unit
) {
    val context = LocalContext.current
    val effectiveAmount = if (selectedPreset == PayPalPreset.ANY) {
        customAmount.ifBlank { null }
    } else {
        selectedPreset.amountUsd
    }
    val paypalUrl = remember(effectiveAmount) { DonationConfig.buildPayPalUrl(effectiveAmount) }
    val paypalClipLabel = stringResource(id = R.string.support_dev_paypal_clipboard_label)
    val paypalCopiedToast = stringResource(id = R.string.support_dev_paypal_copied)
    val paypalShareSubject = stringResource(id = R.string.support_dev_share_subject)

    val qrBitmap by produceState<Bitmap?>(initialValue = null, key1 = paypalUrl) {
        value = withContext(Dispatchers.Default) {
            QrCodeGenerator.generateQrBitmap(paypalUrl, sizePx = 512)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(id = R.string.support_dev_paypal_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PayPalPreset.entries.forEach { preset ->
                val chipLabel = if (preset == PayPalPreset.ANY) {
                    stringResource(id = R.string.support_dev_paypal_any_amount)
                } else {
                    preset.label
                }
                FilterChip(
                    selected = selectedPreset == preset,
                    onClick = { onPresetSelected(preset) },
                    label = { Text(chipLabel, maxLines = 1) },
                    modifier = Modifier.testTag("paypal_preset_${preset.name.lowercase()}")
                )
            }
        }

        if (selectedPreset == PayPalPreset.ANY) {
            OutlinedTextField(
                value = customAmount,
                onValueChange = onCustomAmountChanged,
                label = { Text(stringResource(id = R.string.support_dev_paypal_custom_label)) },
                placeholder = { Text(stringResource(id = R.string.support_dev_paypal_custom_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("paypal_custom_amount_input")
            )
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(14.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(184.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .padding(10.dp)
                ) {
                    val bmp = qrBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(id = R.string.support_dev_cd_paypal_qr, paypalUrl),
                            modifier = Modifier
                                .size(164.dp)
                                .testTag("paypal_live_qr_image")
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = paypalUrl,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                copyTextToClipboard(context, paypalClipLabel, paypalUrl)
                                Toast.makeText(context, paypalCopiedToast, Toast.LENGTH_SHORT).show()
                                onExternalAction("PAYPAL_COPY", paypalUrl)
                            },
                            modifier = Modifier.testTag("paypal_copy_link_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(id = R.string.support_dev_cd_copy_paypal),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                shareDonationLink(context, paypalUrl, paypalShareSubject)
                                onExternalAction("PAYPAL_SHARE", paypalUrl)
                            },
                            modifier = Modifier.testTag("paypal_share_link_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = stringResource(id = R.string.support_dev_cd_share_paypal),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                openUrlWithClipboardFallback(context, paypalUrl, paypalClipLabel)
                onExternalAction("PAYPAL_OPEN", paypalUrl)
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("paypal_open_button")
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (effectiveAmount != null) {
                    stringResource(id = R.string.support_dev_paypal_open_amount, effectiveAmount)
                } else {
                    stringResource(id = R.string.support_dev_paypal_open_default)
                },
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun KofiDonationTab(
    onExternalAction: (channel: String, detail: String) -> Unit
) {
    val context = LocalContext.current
    val kofiUrl = remember { DonationConfig.buildKofiUrl() }
    val kofiClipLabel = stringResource(id = R.string.support_dev_kofi_clipboard_label)
    val kofiCopiedToast = stringResource(id = R.string.support_dev_kofi_copied)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(18.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.LocalCafe,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
                Text(
                    text = stringResource(id = R.string.support_dev_kofi_headline),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(id = R.string.support_dev_kofi_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = kofiUrl,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Button(
            onClick = {
                openUrlWithClipboardFallback(context, kofiUrl, kofiClipLabel)
                onExternalAction("KOFI_OPEN", kofiUrl)
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("kofi_open_button")
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(id = R.string.support_dev_kofi_cta), fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = {
                copyTextToClipboard(context, kofiClipLabel, kofiUrl)
                Toast.makeText(context, kofiCopiedToast, Toast.LENGTH_SHORT).show()
                onExternalAction("KOFI_COPY", kofiUrl)
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("kofi_copy_button")
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(id = R.string.support_dev_kofi_copy))
        }
    }
}

@Composable
private fun localizedTierTitle(tier: DonationTier): String = when (tier) {
    DonationTier.TIER_1 -> stringResource(id = R.string.support_dev_tier1_title)
    DonationTier.TIER_2 -> stringResource(id = R.string.support_dev_tier2_title)
    DonationTier.TIER_3 -> stringResource(id = R.string.support_dev_tier3_title)
    DonationTier.TIER_4 -> stringResource(id = R.string.support_dev_tier4_title)
}

@Composable
private fun localizedTierSubtitle(tier: DonationTier): String = when (tier) {
    DonationTier.TIER_1 -> stringResource(id = R.string.support_dev_tier1_sub)
    DonationTier.TIER_2 -> stringResource(id = R.string.support_dev_tier2_sub)
    DonationTier.TIER_3 -> stringResource(id = R.string.support_dev_tier3_sub)
    DonationTier.TIER_4 -> stringResource(id = R.string.support_dev_tier4_sub)
}
