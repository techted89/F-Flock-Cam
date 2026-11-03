package com.techted89.flockcam.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Thin wrapper around Play Billing. The server is still the source of truth:
 * the app only obtains a purchase token and hands it to the backend for
 * verification and entitlement granting.
 */
class BillingManager(context: Context) {

    private val appContext = context.applicationContext
    private var billingClient: BillingClient? = null
    private var connected = false
    private var purchaseListener: ((productId: String, purchaseToken: String) -> Unit)? = null
    private var errorListener: ((String) -> Unit)? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { purchase ->
                val productId = purchase.products.firstOrNull() ?: return@forEach
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    purchase.purchaseToken?.let { token ->
                        purchaseListener?.invoke(productId, token)
                    }
                }
            }
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            errorListener?.invoke(result.debugMessage.ifBlank { "Billing failed." })
        }
    }

    fun start(
        onPurchase: (productId: String, purchaseToken: String) -> Unit,
        onError: (String) -> Unit,
        onReady: () -> Unit = {},
    ) {
        purchaseListener = onPurchase
        errorListener = onError
        val existing = billingClient
        if (existing != null && connected) {
            onReady()
            return
        }
        val client = existing ?: BillingClient.newBuilder(appContext)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()
            .also { billingClient = it }

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                connected = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                if (connected) {
                    onReady()
                } else {
                    errorListener?.invoke(billingResult.debugMessage.ifBlank { "Play Billing is unavailable." })
                }
            }

            override fun onBillingServiceDisconnected() {
                connected = false
            }
        })
    }

    fun queryProducts(
        productIds: List<String>,
        onResult: (Map<String, ProductDetails>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val client = billingClient ?: run {
            onError("Play Billing is not ready.")
            return
        }
        val products = productIds.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(products)
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                onResult(details.associateBy { it.productId })
            } else {
                onError(result.debugMessage.ifBlank { "Could not load subscription products." })
            }
        }
    }

    fun purchase(activity: Activity, productDetails: ProductDetails): BillingResult {
        val client = billingClient
            ?: return BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                .setDebugMessage("Play Billing is not ready.")
                .build()
        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .apply { if (offerToken != null) setOfferToken(offerToken) }
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        return client.launchBillingFlow(activity, flowParams)
    }

    fun restorePurchases(
        onPurchase: (productId: String, purchaseToken: String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val client = billingClient ?: run {
            onError("Play Billing is not ready.")
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                onError(result.debugMessage.ifBlank { "Could not restore purchases." })
                return@queryPurchasesAsync
            }
            val active = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            if (active.isEmpty()) {
                onError("No active subscription was found for this Google account.")
                return@queryPurchasesAsync
            }
            active.forEach { purchase ->
                val productId = purchase.products.firstOrNull() ?: return@forEach
                purchase.purchaseToken?.let { onPurchase(productId, it) }
            }
        }
    }

    fun end() {
        connected = false
        purchaseListener = null
        errorListener = null
        runCatching { billingClient?.endConnection() }
        billingClient = null
    }
}
