package com.rtb.rtbdemand.sdk

import com.google.android.gms.ads.AdSize
import com.rtb.rtbdemand.admob.AdMobBannerAdView
import com.rtb.rtbdemand.banners.BannerAdView
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.intersitial.InterstitialAd

internal interface BannerManagerListener {
    fun attachAdView(adUnitId: String, adSizes: List<AdSize>, attach: Boolean)

    fun attachFallback(fallbackBanner: Fallback.Banner)

    fun loadAd(request: AdRequest): Boolean
}

interface FullScreenContentCallback {
    fun onAdClicked()
    fun onAdDismissedFullScreenContent()
    fun onAdFailedToShowFullScreenContent(error: RTBError)
    fun onAdImpression()
    fun onAdShowedFullScreenContent()
}

interface BannerAdListener {
    fun onAdClicked(bannerAdView: BannerAdView)
    fun onAdClosed(bannerAdView: BannerAdView)
    fun onAdFailedToLoad(bannerAdView: BannerAdView, error: RTBError, retrying: Boolean)
    fun onAdImpression(bannerAdView: BannerAdView)
    fun onAdLoaded(bannerAdView: BannerAdView)
    fun onAdOpened(bannerAdView: BannerAdView)
}

interface AdMobBannerListener {
    fun onAdClicked(bannerAdView: AdMobBannerAdView)
    fun onAdClosed(bannerAdView: AdMobBannerAdView)
    fun onAdFailedToLoad(bannerAdView: AdMobBannerAdView, error: RTBError, retrying: Boolean)
    fun onAdImpression(bannerAdView: AdMobBannerAdView)
    fun onAdLoaded(bannerAdView: AdMobBannerAdView)
    fun onAdOpened(bannerAdView: AdMobBannerAdView)
}

fun interface OnShowAdCompleteListener {
    fun onShowAdComplete()
}

interface AdLoadCallback {
    fun onAdLoaded()
    fun onAdFailedToLoad(error: RTBError)
}

interface InterstitialAdListener {

    fun onAdReceived(var1: InterstitialAd)

    fun onAdFailedToLoad(var1: InterstitialAd, var2: RTBError)

    fun onAdFailedToShow(var1: InterstitialAd, var2: RTBError)

    fun onAppLeaving(var1: InterstitialAd)

    fun onAdOpened(var1: InterstitialAd)

    fun onAdClosed(var1: InterstitialAd)

    fun onAdClicked(var1: InterstitialAd)

    fun onAdExpired(var1: InterstitialAd)
}

