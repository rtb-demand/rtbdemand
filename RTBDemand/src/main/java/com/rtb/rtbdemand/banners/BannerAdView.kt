package com.rtb.rtbdemand.banners

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.widget.LinearLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.admanager.AdManagerAdView
import com.google.gson.Gson
import com.rtb.rtbdemand.R
import com.rtb.rtbdemand.common.AdRequest
import com.rtb.rtbdemand.common.AdTypes
import com.rtb.rtbdemand.common.TAG
import com.rtb.rtbdemand.databinding.BannerAdViewBinding
import org.prebid.mobile.PrebidMobile
import java.util.*

class BannerAdView : LinearLayout, BannerManagerListener {

    private lateinit var mContext: Context
    private lateinit var binding: BannerAdViewBinding
    private lateinit var adView: AdManagerAdView
    private lateinit var bannerManager: BannerManager
    private var adType: String = AdTypes.OTHER
    private lateinit var currentAdUnit: String
    private lateinit var currentAdSizes: List<AdSize>
    private var firstLook = true
    private var bannerAdListener: BannerAdListener? = null

    constructor(context: Context) : super(context) {
        init(context, null)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        init(context, attrs)
    }

    private fun init(context: Context, attrs: AttributeSet?) {
        this.mContext = context
        bannerManager = BannerManager(this)
        val view = inflate(context, R.layout.banner_ad_view, this)
        binding = BannerAdViewBinding.bind(view)
        attrs?.let {
            context.obtainStyledAttributes(it, R.styleable.BannerAdView).apply {
                val adUnitId = getString(R.styleable.BannerAdView_adUnitId) ?: ""
                val adSize = getString(R.styleable.BannerAdView_adSize) ?: ""
                var adSizes = getString(R.styleable.BannerAdView_adSizes) ?: ""
                adType = getString(R.styleable.BannerAdView_adType) ?: AdTypes.OTHER
                if (!adSizes.contains(adSize)) {
                    adSizes = if (adSizes.isEmpty()) adSize
                    else String.format(Locale.ENGLISH, "%s,%s", adSizes, adSize)
                }
                if (adUnitId.isNotEmpty() && adSizes.isNotEmpty()) {
                    attachAdView(adUnitId, bannerManager.convertStringSizesToAdSizes(adSizes))
                }
            }
        }
    }


    override fun attachAdView(adUnitId: String, adSizes: List<AdSize>) {
        adView = AdManagerAdView(mContext)
        currentAdSizes = adSizes
        currentAdUnit = adUnitId
        adView.setAdSizes(*adSizes.toTypedArray())
        adView.adUnitId = adUnitId
        adView.adListener = adListener
        binding.root.removeAllViews()
        binding.root.addView(adView)
    }

    fun setAdSize(adSize: BannerAdSize) = setAdSizes(adSize)

    fun setAdSizes(vararg adSizes: BannerAdSize) {
        this.currentAdSizes = bannerManager.convertVaragsToAdSizes(*adSizes)
        if (this::currentAdSizes.isInitialized && this::currentAdUnit.isInitialized) {
            attachAdView(adUnitId = currentAdUnit, adSizes = currentAdSizes)
        }
    }


    fun setAdUnitID(adUnitId: String) {
        this.currentAdUnit = adUnitId
        if (this::currentAdSizes.isInitialized && this::currentAdUnit.isInitialized) {
            attachAdView(adUnitId = currentAdUnit, adSizes = currentAdSizes)
        }
    }

    fun setAdType(adType: String) {
        this.adType = adType
        if (this::currentAdSizes.isInitialized && this::currentAdUnit.isInitialized) {
            attachAdView(adUnitId = currentAdUnit, adSizes = currentAdSizes)
        }
    }

    fun setAdListener(listener: BannerAdListener) {
        this.bannerAdListener = listener
    }

    override fun loadAd(request: AdRequest): Boolean {
        var adRequest = request.getAdRequest() ?: return false
        fun load() {
            if (this::adView.isInitialized) {
                checkPrebid()
                bannerManager.fetchDemand(firstLook, adRequest) { adView.loadAd(adRequest) }
            }
        }
        if (firstLook) {
            bannerManager.shouldSetConfig {
                if (it) {
                    bannerManager.setConfig(currentAdUnit, currentAdSizes as ArrayList<AdSize>, adType)
                    adRequest = bannerManager.checkOverride() ?: adRequest
                }
                load()
            }
        } else load()
        return true
    }

    private fun checkPrebid() {
        Log.d(TAG, "checkPrebid: ${PrebidMobile.getPrebidServerAccountId()}")
        Log.d(TAG, "checkPrebid: ${Gson().toJson(PrebidMobile.isSdkInitialized())}")
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        bannerManager.saveVisibility(isVisible)
    }

    private val adListener = object : AdListener() {
        override fun onAdClicked() {
            super.onAdClicked()
            bannerManager.clearConfig()
            bannerAdListener?.onAdClicked()
        }

        override fun onAdClosed() {
            super.onAdClosed()
            bannerAdListener?.onAdClosed()
        }

        override fun onAdFailedToLoad(p0: LoadAdError) {
            super.onAdFailedToLoad(p0)
            bannerAdListener?.onAdFailedToLoad(p0.toString())
            if (firstLook) {
                firstLook = false
                bannerManager.adFailedToLoad()
            }
        }

        override fun onAdImpression() {
            bannerAdListener?.onAdImpression()
            super.onAdImpression()
        }

        override fun onAdLoaded() {
            super.onAdLoaded()
            bannerAdListener?.onAdLoaded()
            bannerManager.adLoaded(firstLook)
            if (firstLook) {
                firstLook = false
            }
        }

        override fun onAdOpened() {
            bannerAdListener?.onAdOpened()
            super.onAdOpened()
        }
    }

    fun pauseAd() {
        adView.pause()
        bannerManager.adPaused()
    }

    fun resumeAd() {
        adView.resume()
        bannerManager.adResumed()
    }

    fun destroyAd() {
        adView.destroy()
        bannerManager.adDestroyed()
    }
}