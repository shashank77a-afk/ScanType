package com.scantype.documentscanner

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.FullScreenContentCallback

/** Google official test ad IDs are intentionally used until the app is verified end-to-end. */
object ScanTypeAds {
    const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"

    @Volatile private var initialized = false
    private var interstitial: InterstitialAd? = null
    private var loading = false

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        MobileAds.initialize(context.applicationContext) { }
        loadInterstitial(context.applicationContext)
    }

    @Synchronized fun loadInterstitial(context: Context) {
        if (loading || interstitial != null) return
        loading = true
        InterstitialAd.load(context, TEST_INTERSTITIAL_ID, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    loading = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitial = null
                    loading = false
                }
            })
    }

    fun showInterstitial(activity: Activity, onFinished: () -> Unit = {}) {
        val ad = interstitial
        if (ad == null) {
            loadInterstitial(activity.applicationContext)
            onFinished()
            return
        }
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadInterstitial(activity.applicationContext)
                onFinished()
            }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                loadInterstitial(activity.applicationContext)
                onFinished()
            }
        }
        ad.show(activity)
    }
}

@Composable
fun ScanTypeBannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    DisposableEffect(context) {
        ScanTypeAds.initialize(context)
        onDispose { }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().wrapContentHeight(),
        factory = { ctx ->
            AdView(ctx).apply {
                adUnitId = ScanTypeAds.TEST_BANNER_ID
                setAdSize(AdSize.BANNER)
                loadAd(AdRequest.Builder().build())
            }
        },
        update = { }
    )
}
