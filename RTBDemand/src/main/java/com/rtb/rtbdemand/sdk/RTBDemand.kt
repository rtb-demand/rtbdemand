package com.rtb.rtbdemand.sdk

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.*
import com.google.android.gms.ads.MobileAds
import com.google.gson.Gson
import com.rtb.rtbdemand.common.TAG
import com.rtb.rtbdemand.common.URLs.BASE_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.prebid.mobile.Host
import org.prebid.mobile.PrebidMobile
import org.prebid.mobile.api.exceptions.InitError
import org.prebid.mobile.rendering.listeners.SdkInitializationListener
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.QueryMap
import java.util.concurrent.TimeUnit

object RTBDemand {
    fun initialize(context: Context) {
        startKoin {
            androidContext(context)
            modules(sdkModule)
        }
        fetchConfig(context)
    }

    private fun fetchConfig(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val workerRequest = OneTimeWorkRequestBuilder<ConfigSetWorker>().setConstraints(constraints).build()
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(ConfigSetWorker::class.java.simpleName, ExistingWorkPolicy.REPLACE, workerRequest)
        workManager.getWorkInfoByIdLiveData(workerRequest.id).observeForever {
            if (it.state == WorkInfo.State.SUCCEEDED) {
                SDKManager.initialize(context)
            }
        }
    }
}

internal val sdkModule = module {

    single { WorkManager.getInstance(androidContext()) }

    single {
        StoreService(androidContext().getSharedPreferences(androidContext().packageName, Context.MODE_PRIVATE))
    }

    single {
        OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS).hostnameVerifier { _, _ -> true }.build()
    }

    single {
        val client = Retrofit.Builder().baseUrl(BASE_URL).client(get())
                .addConverterFactory(GsonConverterFactory.create()).build()
        client.create(ConfigService::class.java)
    }
}

internal class ConfigSetWorker(private val context: Context, params: WorkerParameters) : Worker(context, params), KoinComponent {
    override fun doWork(): Result {
        val storeService: StoreService by inject()
        return try {
            val configService: ConfigService by inject()
            val response = configService.getConfig(hashMapOf("Name" to context.packageName)).execute()
            if (response.isSuccessful && response.body() != null) {
                storeService.config = response.body()
                Result.success()
            } else {
                storeService.config?.let {
                    Result.success()
                } ?: Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, e.message ?: "")
            storeService.config?.let {
                Result.success()
            } ?: Result.failure()
        }
    }
}

internal class PrebidWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.Main) {
            return@withContext try {
                val storeService: StoreService by inject()
                val config = storeService.config
                if (config != null && config.switch == 1) {
                    PrebidMobile.setPrebidServerHost(Host.createCustomHost(config.prebid?.host ?: ""))
                    PrebidMobile.setPrebidServerAccountId(config.prebid?.accountId ?: "")
                    PrebidMobile.initializeSdk(context, object : SdkInitializationListener {
                        override fun onSdkInit() {
                            Log.i(TAG, "Prebid Initialized")
                        }

                        override fun onSdkFailedToInit(error: InitError?) {
                            Log.e(TAG, error?.error ?: "")
                        }
                    })
                }
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, e.message ?: "")
                Result.failure()
            }
        }
        return Result.success()
    }

}

internal object SDKManager : KoinComponent {

    fun initialize(context: Context) {
        val storeService: StoreService by inject()
        val config = storeService.config ?: return
        if (config.switch != 1) return
        PrebidMobile.setPrebidServerHost(Host.createCustomHost(config.prebid?.host ?: ""))
        PrebidMobile.setPrebidServerAccountId(config.prebid?.accountId ?: "")
        PrebidMobile.initializeSdk(context, object : SdkInitializationListener {
            override fun onSdkInit() {
                Log.i(TAG, "Prebid Initialized")
            }

            override fun onSdkFailedToInit(error: InitError?) {
                Log.e(TAG, error?.error ?: "")
            }
        })
        initializeGAM(context)
    }

    private fun initializeGAM(context: Context) {
        MobileAds.initialize(context) {
            Log.i(TAG, "GAM Initialization complete.")
        }
    }
}

internal interface ConfigService {
    @GET("appconfig1.php")
    fun getConfig(@QueryMap params: HashMap<String, Any>): Call<SDKConfig>
}

internal class StoreService(private val prefs: SharedPreferences) {

    var config: SDKConfig?
        get() {
            val string = prefs.getString("CONFIG", "") ?: ""
            if (string.isEmpty()) return null
            return Gson().fromJson(string, SDKConfig::class.java)
        }
        set(value) = prefs.edit().apply {
            value?.let { putString("CONFIG", Gson().toJson(value)) } ?: kotlin.run { remove("CONFIG") }
        }.apply()

    var prebidPending: Boolean
        get() = prefs.getBoolean("PREBID_PENDING", false)
        set(value) = prefs.edit().putBoolean("PREBID_PENDING", value).apply()
}