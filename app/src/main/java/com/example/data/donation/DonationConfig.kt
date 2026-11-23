package com.example.data.donation

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Centralized donation configuration for all 3 supported channels:
 * 1. Google Play In-App Purchase (4 one-time support tiers)
 * 2. PayPal.me deep links + live QR code presets
 * 3. Ko-fi web tip jar
 *
 * Update PAYPAL_USERNAME, KOFI_USERNAME, and Play Console product IDs here when publishing.
 */
object DonationConfig {
    const val PAYPAL_USERNAME = "examforgeapp"
    const val KOFI_USERNAME = "examforgeapp"

    fun buildPayPalUrl(amountUsd: String? = null): String {
        val sanitized = sanitizeAmount(amountUsd)
        return if (sanitized != null) {
            "https://paypal.me/$PAYPAL_USERNAME/${sanitized}USD"
        } else {
            "https://paypal.me/$PAYPAL_USERNAME"
        }
    }

    fun buildKofiUrl(): String = "https://ko-fi.com/$KOFI_USERNAME"

    /**
     * Validates a user-entered USD amount (integer or up to 2 decimal places, > 0 and <= 9999).
     */
    fun sanitizeAmount(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val cleaned = trimmed.replace(",", ".").replace("$", "")
        val parsed = cleaned.toDoubleOrNull() ?: return null
        if (parsed <= 0.0 || parsed > 9999.0) return null
        return if (parsed % 1.0 == 0.0) {
            parsed.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", parsed)
        }
    }
}

enum class DonationChannel {
    GOOGLE_PLAY,
    PAYPAL,
    KOFI
}

enum class DonationTier(
    val productId: String,
    val fallbackPrice: String,
    val title: String,
    val subtitle: String
) {
    TIER_1(
        productId = "examforge_donate_tier_1",
        fallbackPrice = "$1.99",
        title = "Quick Espresso",
        subtitle = "Small token of support"
    ),
    TIER_2(
        productId = "examforge_donate_tier_2",
        fallbackPrice = "$4.99",
        title = "Coffee & Notes",
        subtitle = "Most popular tip"
    ),
    TIER_3(
        productId = "examforge_donate_tier_3",
        fallbackPrice = "$9.99",
        title = "Study Fuel",
        subtitle = "Powers server & updates"
    ),
    TIER_4(
        productId = "examforge_donate_tier_4",
        fallbackPrice = "$19.99",
        title = "Patron Supporter",
        subtitle = "Champions indie dev"
    )
}

enum class PayPalPreset(val label: String, val amountUsd: String?) {
    USD_3("$3", "3"),
    USD_5("$5", "5"),
    USD_10("$10", "10"),
    ANY("Any Amount", null)
}

/**
 * Safely unwraps the hosting [Activity] from any nested [ContextWrapper]
 * (including Compose ModalBottomSheet, AlertDialog, and ContextThemeWrapper scopes).
 * Prevents "Billing Activity requires an Activity session" failures.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Launches a web URL via ACTION_VIEW with automatic clipboard fallback if no browser is available.
 * Returns true if launched in a browser/app, false if fell back to copying the URL.
 */
fun openUrlWithClipboardFallback(
    context: Context,
    url: String,
    clipLabel: String
): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrElse {
        copyTextToClipboard(context, clipLabel, url)
        Toast.makeText(
            context,
            "No browser app found. Link copied to clipboard!",
            Toast.LENGTH_LONG
        ).show()
        false
    }
}

fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun shareDonationLink(context: Context, url: String, chooserTitle: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, chooserTitle)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    runCatching {
        val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }.onFailure {
        copyTextToClipboard(context, chooserTitle, url)
        Toast.makeText(context, "Link copied to clipboard.", Toast.LENGTH_SHORT).show()
    }
}
