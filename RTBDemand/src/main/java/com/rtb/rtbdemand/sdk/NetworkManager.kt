package com.rtb.rtbdemand.sdk

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class NetworkManager() : ConnectivityManager.NetworkCallback() {

    var isInternetAvailable: Boolean = true

    suspend fun register(context: Context) = withContext(Dispatchers.IO) {
        try {
            (context.getSystemService(Activity.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.registerDefaultNetworkCallback(this@NetworkManager)
        } catch (_: Throwable) {
        }
    }

    fun unRegister(context: Context) {
        try {
            (context.getSystemService(Activity.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(this)
        } catch (_: Throwable) {
        }
    }

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        isInternetAvailable = true
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        isInternetAvailable = false
    }

    override fun onUnavailable() {
        super.onUnavailable()
        isInternetAvailable = false
    }
}