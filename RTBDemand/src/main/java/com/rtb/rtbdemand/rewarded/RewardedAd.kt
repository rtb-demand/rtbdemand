package com.rtb.rtbdemand.rewarded

import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.ServerSideVerificationOptions
import com.rtb.rtbdemand.rewardedinterstitial.Reward
import com.rtb.rtbdemand.sdk.FullScreenContentCallback
import com.rtb.rtbdemand.sdk.Logger
import com.rtb.rtbdemand.sdk.RTBError
import com.rtb.rtbdemand.sdk.log

class RewardedAd(private val context: Activity, private val adUnit: String) {
    private var rewardedAdManager = RewardedAdManager(context, adUnit)
    private var mRewardedAd: RewardedAd? = null

    fun load(adRequest: AdRequest, callBack: (loaded: Boolean) -> Unit) {
        rewardedAdManager.load(adRequest) {
            mRewardedAd = it
            callBack(mRewardedAd != null)
        }
    }

    fun setServerSideVerificationOptions(options: ServerSideVerificationOptions) {
        options.getOptions()?.let {
            mRewardedAd?.setServerSideVerificationOptions(it)
        }
    }

    fun show(callBack: (reward: Reward?) -> Unit) {
        if (mRewardedAd != null) {
            mRewardedAd?.show(context) { callBack(Reward(it.amount, it.type)) }
        } else {
            Logger.ERROR.log(msg = "The rewarded interstitial ad wasn't ready yet.")
            callBack(null)
        }
    }

    fun setContentCallback(callback: FullScreenContentCallback) {
        mRewardedAd?.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
            override fun onAdClicked() {
                super.onAdClicked()
                callback.onAdClicked()
            }

            override fun onAdDismissedFullScreenContent() {
                super.onAdDismissedFullScreenContent()
                mRewardedAd = null
                callback.onAdDismissedFullScreenContent()
            }

            override fun onAdFailedToShowFullScreenContent(p0: AdError) {
                super.onAdFailedToShowFullScreenContent(p0)
                mRewardedAd = null
                callback.onAdFailedToShowFullScreenContent(RTBError(p0.code, p0.message))
            }

            override fun onAdImpression() {
                super.onAdImpression()
                callback.onAdImpression()
            }

            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                callback.onAdShowedFullScreenContent()
            }
        }
    }
}