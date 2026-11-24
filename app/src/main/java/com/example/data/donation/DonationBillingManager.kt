package com.example.data.donation

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "DonationBillingManager"

enum class BillingConnectionStatus {
    IDLE,
    CONNECTING,
    READY,
    UNAVAILABLE
}

data class DonationBillingUiState(
    val connectionStatus: BillingConnectionStatus = BillingConnectionStatus.IDLE,
    val localizedPrices: Map<String, String> = emptyMap(),
    val productsLoaded: Boolean = false,
    val isPurchasing: Boolean = false,
    val statusMessage: String? = null,
    val lastError: String? = null
)

/**
 * Production-grade Google Play Billing Library v8 manager for voluntary one-time donations.
 *
 * Key safeguards:
 * - Always requires a live, non-finishing [Activity] (passed directly from MainActivity or
 *   unwrapped via [findActivity]) and launches `launchBillingFlow` on Dispatchers.Main.immediate,
 *   completely avoiding "Billing Activity requires an Activity session" errors.
 * - Uses PBL v8 `enableAutoServiceReconnection()` plus explicit connection checks.
 * - Consumes verified donation purchases so supporters can tip again later while permanently
 *   granting the Active Supporter badge on the first verified receipt.
 * - Handles emulator / sideloaded / unpublished SKU environments gracefully with clear feedback.
 */
class DonationBillingManager(
    appContext: Context,
    private val onSupporterVerified: (productId: String, purchaseToken: String) -> Unit
) : PurchasesUpdatedListener {

    private val context: Context = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val productDetailsCache = mutableMapOf<String, ProductDetails>()

    private val _billingState = MutableStateFlow(DonationBillingUiState())
    val billingState: StateFlow<DonationBillingUiState> = _billingState.asStateFlow()

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun startConnectionAndLoad() {
        scope.launch {
            val connected = ensureConnected()
            if (connected) {
                queryDonationProducts()
                restoreSupporterPurchases(silent = true)
            }
        }
    }

    fun clearMessages() {
        _billingState.update { it.copy(statusMessage = null, lastError = null) }
    }

    /**
     * Launches Google Play In-App Purchase flow using a verified live [Activity].
     */
    fun launchDonationPurchase(
        activity: Activity?,
        tier: DonationTier
    ) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.e(TAG, "Cannot launch billing flow: Activity is null or finishing")
            _billingState.update {
                it.copy(
                    isPurchasing = false,
                    lastError = context.getString(R.string.billing_err_no_activity)
                )
            }
            return
        }

        scope.launch {
            _billingState.update {
                it.copy(isPurchasing = true, lastError = null, statusMessage = null)
            }

            val connected = ensureConnected()
            if (!connected) {
                _billingState.update {
                    it.copy(
                        isPurchasing = false,
                        lastError = context.getString(R.string.billing_err_unavailable_alt)
                    )
                }
                return@launch
            }

            if (productDetailsCache[tier.productId] == null) {
                queryDonationProducts()
            }

            val details = productDetailsCache[tier.productId]
            if (details == null) {
                _billingState.update {
                    it.copy(
                        isPurchasing = false,
                        lastError = context.getString(R.string.billing_err_sku_inactive, tier.productId)
                    )
                }
                return@launch
            }

            val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()

            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productDetailsParams))
                .build()

            val launchOutcome = withContext(Dispatchers.Main.immediate) {
                if (activity.isFinishing || activity.isDestroyed || activity.window?.decorView?.windowToken == null) {
                    Result.failure(IllegalStateException(context.getString(R.string.billing_err_window_closed)))
                } else {
                    runCatching {
                        billingClient.launchBillingFlow(activity, billingFlowParams)
                    }
                }
            }

            if (launchOutcome.isFailure) {
                val err = launchOutcome.exceptionOrNull()
                Log.w(TAG, "launchBillingFlow threw exception", err)
                _billingState.update {
                    it.copy(
                        isPurchasing = false,
                        lastError = err?.localizedMessage
                            ?: context.getString(R.string.billing_err_launch_failed)
                    )
                }
                return@launch
            }

            val result = launchOutcome.getOrThrow()

            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    // PurchasesUpdatedListener will receive the completion result
                    Log.d(TAG, "Billing flow launched successfully for ${tier.productId}")
                }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    restoreSupporterPurchases(silent = false)
                    _billingState.update {
                        it.copy(
                            isPurchasing = false,
                            statusMessage = context.getString(R.string.billing_status_restored_prev)
                        )
                    }
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> {
                    _billingState.update { it.copy(isPurchasing = false) }
                }
                else -> {
                    Log.w(TAG, "launchBillingFlow failed: code=${result.responseCode}, msg=${result.debugMessage}")
                    _billingState.update {
                        it.copy(
                            isPurchasing = false,
                            lastError = formatBillingError(result)
                        )
                    }
                }
            }
        }
    }

    /**
     * Queries existing INAPP purchases to verify/restore Active Supporter status across reinstalls.
     */
    fun restoreSupporterPurchases(silent: Boolean = false) {
        scope.launch {
            val connected = ensureConnected()
            if (!connected) {
                if (!silent) {
                    _billingState.update {
                        it.copy(lastError = context.getString(R.string.billing_err_unavailable))
                    }
                }
                return@launch
            }

            val queryParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()

            billingClient.queryPurchasesAsync(queryParams) { billingResult, purchases ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    if (!silent) {
                        _billingState.update { it.copy(lastError = formatBillingError(billingResult)) }
                    }
                    return@queryPurchasesAsync
                }

                val validDonationIds = DonationTier.entries.map { it.productId }.toSet()
                val matching = purchases.filter { purchase ->
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        purchase.products.any { it in validDonationIds }
                }

                if (matching.isNotEmpty()) {
                    matching.forEach { handleVerifiedPurchase(it) }
                    if (!silent) {
                        _billingState.update {
                            it.copy(statusMessage = context.getString(R.string.billing_status_restored))
                        }
                    }
                } else if (!silent) {
                    _billingState.update {
                        it.copy(statusMessage = context.getString(R.string.billing_status_none_found))
                    }
                }
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val validPurchases = purchases.orEmpty()
                var processedAny = false
                validPurchases.forEach { purchase ->
                    when (purchase.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> {
                            processedAny = true
                            handleVerifiedPurchase(purchase)
                        }
                        Purchase.PurchaseState.PENDING -> {
                            _billingState.update {
                                it.copy(
                                    isPurchasing = false,
                                    statusMessage = context.getString(R.string.billing_status_pending)
                                )
                            }
                        }
                        else -> Unit
                    }
                }
                if (processedAny) {
                    _billingState.update {
                        it.copy(
                            isPurchasing = false,
                            lastError = null,
                            statusMessage = context.getString(R.string.vm_supporter_unlocked)
                        )
                    }
                } else {
                    _billingState.update { it.copy(isPurchasing = false) }
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _billingState.update { it.copy(isPurchasing = false) }
            }

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                restoreSupporterPurchases(silent = false)
                _billingState.update { it.copy(isPurchasing = false) }
            }

            else -> {
                Log.w(TAG, "onPurchasesUpdated error: ${billingResult.responseCode} ${billingResult.debugMessage}")
                _billingState.update {
                    it.copy(
                        isPurchasing = false,
                        lastError = formatBillingError(billingResult)
                    )
                }
            }
        }
    }

    private fun handleVerifiedPurchase(purchase: Purchase) {
        val productId = purchase.products.firstOrNull() ?: DonationTier.TIER_1.productId
        // Permanently mark user as Active Supporter
        onSupporterVerified(productId, purchase.purchaseToken)

        // In Play Billing v8, consumeAsync both acknowledges and consumes a one-time product
        // so supporters can tip again later without ITEM_ALREADY_OWNED blocks.
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.consumeAsync(consumeParams) { consumeResult, _ ->
            Log.d(TAG, "Consume donation result: ${consumeResult.responseCode}")
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (billingClient.isReady) {
            _billingState.update { it.copy(connectionStatus = BillingConnectionStatus.READY) }
            return true
        }
        _billingState.update { it.copy(connectionStatus = BillingConnectionStatus.CONNECTING) }

        return suspendCancellableCoroutine { cont ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    val ok = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                    _billingState.update {
                        it.copy(
                            connectionStatus = if (ok) {
                                BillingConnectionStatus.READY
                            } else {
                                BillingConnectionStatus.UNAVAILABLE
                            }
                        )
                    }
                    if (cont.isActive) cont.resume(ok)
                }

                override fun onBillingServiceDisconnected() {
                    _billingState.update { it.copy(connectionStatus = BillingConnectionStatus.IDLE) }
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    private suspend fun queryDonationProducts() {
        val productList = DonationTier.entries.map { tier ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(tier.productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        suspendCancellableCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val priceMap = mutableMapOf<String, String>()
                    queryProductDetailsResult.productDetailsList.forEach { details ->
                        productDetailsCache[details.productId] = details
                        val formatted = details.oneTimePurchaseOfferDetails?.formattedPrice
                        if (!formatted.isNullOrBlank()) {
                            priceMap[details.productId] = formatted
                        }
                    }
                    val unfetched = queryProductDetailsResult.unfetchedProductList
                    if (unfetched.isNotEmpty()) {
                        Log.w(
                            TAG,
                            "Unfetched donation products: ${unfetched.joinToString { "${it.productId}(${it.statusCode})" }}"
                        )
                    }
                    _billingState.update {
                        it.copy(
                            localizedPrices = priceMap,
                            productsLoaded = true
                        )
                    }
                } else {
                    Log.w(TAG, "queryProductDetails failed: ${billingResult.responseCode} ${billingResult.debugMessage}")
                    _billingState.update { it.copy(productsLoaded = true) }
                }
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private fun formatBillingError(result: BillingResult): String {
        return when (result.responseCode) {
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                context.getString(R.string.billing_err_unavailable_short)
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.NETWORK_ERROR ->
                context.getString(R.string.billing_err_network)
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
                context.getString(R.string.billing_err_item_unavailable)
            else ->
                result.debugMessage.ifBlank { context.getString(R.string.billing_err_code, result.responseCode) }
        }
    }

    fun release() {
        runCatching {
            if (billingClient.isReady) billingClient.endConnection()
        }
    }
}
