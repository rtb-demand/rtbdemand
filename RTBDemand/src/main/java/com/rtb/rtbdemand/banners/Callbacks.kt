package com.rtb.rtbdemand.banners

import com.google.android.gms.ads.AdSize
import com.rtb.rtbdemand.common.AdRequest

internal interface BannerManagerListener {
    fun attachAdView(adUnitId: String, adSizes: List<AdSize>)

    fun loadAd(request: AdRequest): Boolean
}

interface BannerAdListener {
    fun onAdClicked()

    fun onAdClosed()

    fun onAdFailedToLoad(error: String)

    fun onAdImpression()

    fun onAdLoaded()

    fun onAdOpened()
}