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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    // Always resolve a real Activity — explicit hostActivity first, then ContextWrapper unwrapping
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
            // Header
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
                            isActiveSupporter -> "Active Supporter"
                            milestoneCount != null -> "$milestoneCount Study Tasks Completed!"
                            else -> "Support ExamForge"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isActiveSupporter) {
                            "Thank you for backing independent, ad-free learning."
                        } else {
                            "100% voluntary · Ad-free & offline-first indie app."
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
                            text = "Your Active Supporter badge is active and all donation banners are permanently disabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // Channel Selector (Google Play / PayPal + QR / Ko-fi)
            val channels = DonationChannel.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                channels.forEachIndexed { idx, channel ->
                    val label = when (channel) {
                        DonationChannel.GOOGLE_PLAY -> "Google Play"
                        DonationChannel.PAYPAL -> "PayPal & QR"
                        DonationChannel.KOFI -> "Ko-fi"
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

            // Channel Content
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

            // Footer controls
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
                            text = "Don't ask again",
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
                    Text("Close")
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
            text = "1-Tap Google Pay checkout. Verified supporters permanently receive the Active Supporter header badge and silence all reminders.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 2x2 Tier Grid
        val rows = DonationTier.entries.chunked(2)
        rows.forEach { rowTiers ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                rowTiers.forEach { tier ->
                    val isSelected = selectedTier == tier
                    val price = billingState.localizedPrices[tier.productId] ?: tier.fallbackPrice
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
                                text = tier.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = tier.subtitle,
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
                        Text("Try PayPal & QR instead →")
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
                Text("Opening Google Play…")
            } else {
                Icon(
                    imageVector = Icons.Default.ShoppingBag,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Support $activePrice via Google Play",
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
                BillingConnectionStatus.READY -> "Play Billing connected"
                BillingConnectionStatus.CONNECTING -> "Connecting to Google Play…"
                BillingConnectionStatus.UNAVAILABLE -> "Play Billing unavailable here"
                BillingConnectionStatus.IDLE -> "Ready on tap"
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
                Text("Restore Supporter")
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

    val qrBitmap by produceState<Bitmap?>(initialValue = null, key1 = paypalUrl) {
        value = withContext(Dispatchers.Default) {
            QrCodeGenerator.generateQrBitmap(paypalUrl, sizePx = 512)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Scan the live QR code with any banking or PayPal camera, or open the direct link below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Preset Amount Chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PayPalPreset.entries.forEach { preset ->
                FilterChip(
                    selected = selectedPreset == preset,
                    onClick = { onPresetSelected(preset) },
                    label = { Text(preset.label, maxLines = 1) },
                    modifier = Modifier.testTag("paypal_preset_${preset.name.lowercase()}")
                )
            }
        }

        if (selectedPreset == PayPalPreset.ANY) {
            OutlinedTextField(
                value = customAmount,
                onValueChange = onCustomAmountChanged,
                label = { Text("Custom USD Amount (optional)") },
                placeholder = { Text("e.g. 7 or leave blank for any") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("paypal_custom_amount_input")
            )
        }

        // Crisp Live QR Code Card (high-contrast white card ensures scanning in Dark Mode too)
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
                            contentDescription = "PayPal donation QR code for $paypalUrl",
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

                // Copyable Link Box
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
                                copyTextToClipboard(context, "PayPal Link", paypalUrl)
                                Toast.makeText(context, "PayPal link copied!", Toast.LENGTH_SHORT).show()
                                onExternalAction("PAYPAL_COPY", paypalUrl)
                            },
                            modifier = Modifier.testTag("paypal_copy_link_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy PayPal link",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                shareDonationLink(context, paypalUrl, "Support ExamForge via PayPal")
                                onExternalAction("PAYPAL_SHARE", paypalUrl)
                            },
                            modifier = Modifier.testTag("paypal_share_link_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share PayPal link",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                openUrlWithClipboardFallback(context, paypalUrl, "PayPal Link")
                onExternalAction("PAYPAL_OPEN", paypalUrl)
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("paypal_open_button")
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (effectiveAmount != null) "Open PayPal ($$effectiveAmount USD)" else "Open PayPal.me",
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
                    text = "Buy the Indie Dev a Coffee on Ko-fi",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Ko-fi takes 0% platform fee on tips and supports cards or PayPal without creating an account. Every tip keeps ExamForge ad-free and offline-ready.",
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
                openUrlWithClipboardFallback(context, kofiUrl, "Ko-fi Link")
                onExternalAction("KOFI_OPEN", kofiUrl)
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("kofi_open_button")
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Support on Ko-fi", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = {
                copyTextToClipboard(context, "Ko-fi Link", kofiUrl)
                Toast.makeText(context, "Ko-fi link copied!", Toast.LENGTH_SHORT).show()
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
            Text("Copy Ko-fi Link")
        }
    }
}
