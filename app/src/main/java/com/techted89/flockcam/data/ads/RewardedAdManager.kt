package com.techted89.flockcam.data.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.techted89.flockcam.BuildConfig

/**
 * Loads a single rewarded ad. The reward is only granted by the server after
 * the client reports completion, so a modified client cannot simply claim it.
 */
class RewardedAdManager(context: Context) {

    private val appContext = context.applicationContext
    private var initialized = false
    private var rewardedAd: RewardedAd? = null
    private var loading = false

    fun preload() {
        if (!initialized) {
            initialized = true
            MobileAds.initialize(appContext) { }
        }
        if (loading || rewardedAd != null) return
        loading = true
        RewardedAd.load(
            appContext,
            BuildConfig.ADMOB_REWARDED_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    loading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    loading = false
                }
            },
        )
    }

    val isReady: Boolean get() = rewardedAd != null

    fun show(
        activity: Activity,
        onReward: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val ad = rewardedAd
        if (ad == null) {
            onError("The ad is not ready yet. Try again in a moment.")
            preload()
            return
        }
        rewardedAd = null
        ad.show(activity) { onReward() }
        preload()
    }
}
