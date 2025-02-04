package com.rtb.rtbdemand.common

import android.annotation.SuppressLint
import android.text.TextUtils
import com.google.android.gms.ads.admanager.AdManagerAdRequest

class AdRequest {

    private lateinit var adRequest: AdManagerAdRequest

    internal fun getAdRequest(): AdManagerAdRequest? {
        return if (this::adRequest.isInitialized) {
            adRequest
        } else {
            null
        }
    }

    fun getBuilder() = Builder()

    @SuppressLint("VisibleForTests")
    inner class Builder {
        private val requestBuilder: AdManagerAdRequest.Builder = AdManagerAdRequest.Builder()

        fun addCategoryExclusion(categoryExclusion: String): AdRequest.Builder {
            requestBuilder.addCategoryExclusion(categoryExclusion)
            return this
        }


        fun addCustomTargeting(key: String, value: String): AdRequest.Builder {
            requestBuilder.addCustomTargeting(key, value)
            return this
        }

        fun addCustomTargeting(key: String, values: List<String>?): AdRequest.Builder {
            if (values != null) {
                addCustomTargeting(key, TextUtils.join(",", values))
            }
            return this
        }

        fun setPublisherProvidedId(publisherProvidedId: String): AdRequest.Builder {
            requestBuilder.setPublisherProvidedId(publisherProvidedId)
            return this
        }

        fun setContentUrl(contentUrl: String): AdRequest.Builder {
            requestBuilder.setContentUrl(contentUrl)
            return this
        }

        fun setRequestAgent(requestAgent: String): AdRequest.Builder {
            requestBuilder.setRequestAgent(requestAgent)
            return this
        }

        fun addKeyword(keyword: String): AdRequest.Builder {
            requestBuilder.addKeyword(keyword)
            return this
        }

        fun setAdString(adString: String): AdRequest.Builder {
            requestBuilder.setAdString(adString)
            return this
        }

        fun setNeighboringContentUrls(neighboringContentUrls: List<String>): AdRequest.Builder {
            requestBuilder.setNeighboringContentUrls(neighboringContentUrls)
            return this
        }

        fun build(): AdRequest {
            adRequest = requestBuilder.addCustomTargeting("ABM_Load", "Yes").build()
            return this@AdRequest
        }

        fun buildWithRequest(request: AdManagerAdRequest): AdRequest {
            adRequest = request
            return this@AdRequest
        }
    }
}