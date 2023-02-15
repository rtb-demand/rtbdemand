package com.rtb.rtbdemand.rewarded

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.rewarded.RewardedAd
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.TAG
import com.rtb.rtbdemand.rewardedinterstitial.Reward

class RewardedAd(private val context: Activity, private val adUnit: String) {
    private var rewardedAdManager = RewardedAdManager(context, adUnit)
    private var mAdManagerInterstitialAd: RewardedAd? = null

    fun load(adRequest: AdRequest, callBack: (loaded: Boolean) -> Unit) {
        rewardedAdManager.load(adRequest) {
            mAdManagerInterstitialAd = it
            callBack(mAdManagerInterstitialAd != null)
        }
    }

    fun show(callBack: (reward: Reward?) -> Unit) {
        if (mAdManagerInterstitialAd != null) {
            mAdManagerInterstitialAd?.show(context) { callBack(Reward(it.amount, it.type)) }
        } else {
            Log.e(TAG, "The rewarded interstitial ad wasn't ready yet.")
            callBack(null)
        }
    }
}