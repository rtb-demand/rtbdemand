package com.rtb.rtbdemand.sdk

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.rtb.rtbdemand.common.Files
import com.rtb.rtbdemand.common.URLs.BASE_URL
import com.rtb.rtbdemand.sdk.ConfigProvider.getConfig
import com.rtb.rtbdemand.sdk.RTBDemand.checkForSilentInterstitial
import com.rtb.rtbdemand.sdk.RTBDemand.getStoreService
import com.rtb.rtbdemand.sdk.RTBDemand.getWorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.QueryMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.TimeUnit

internal object ConfigProvider {

    private var configService: ConfigService? = null
    private var countryService: CountryService? = null
    private var httpClient: OkHttpClient? = null
    private var cachedConfig: SDKConfig? = null
    private var cachedCountryConfig: CountryModel? = null

    @Synchronized
    internal fun getHttpClient(): OkHttpClient {
        if (httpClient == null) {
            httpClient = OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .writeTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS).hostnameVerifier { _, _ -> true }.build()
        }
        return httpClient as OkHttpClient
    }

    @Synchronized
    internal fun getConfigService(): ConfigService {
        if (configService == null) {
            val client = getHttpClient()
            configService = Retrofit.Builder().baseUrl(BASE_URL).client(client)
                    .addConverterFactory(GsonConverterFactory.create()).build().create(ConfigService::class.java)
        }
        return configService as ConfigService
    }

    @Synchronized
    internal fun getCountryService(baseUrl: String): CountryService {
        if (countryService == null) {
            val client = getHttpClient()
            countryService = Retrofit.Builder().baseUrl(baseUrl).client(client)
                    .addConverterFactory(GsonConverterFactory.create()).build().create(CountryService::class.java)
        }
        return countryService as CountryService
    }

    internal suspend fun fetchConfig(context: Context, delay: Long? = null) = withContext(Dispatchers.IO) {
        if (delay != null && delay < 900) return@withContext
        try {
            val constraints = Constraints.Builder().build()
            val workerRequest: OneTimeWorkRequest = delay?.let {
                OneTimeWorkRequestBuilder<ConfigFetchWorker>().setConstraints(constraints).setInitialDelay(it, TimeUnit.SECONDS).build()
            } ?: kotlin.run {
                OneTimeWorkRequestBuilder<ConfigFetchWorker>().setConstraints(constraints).build()
            }
            val workName: String = delay?.let {
                String.format("%s_%s", ConfigFetchWorker::class.java.simpleName, it.toString())
            } ?: kotlin.run {
                ConfigFetchWorker::class.java.simpleName
            }
            val workManager = getWorkManager(context)
            workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, workerRequest)
        } catch (_: Throwable) {
            SDKManager.initialize(context, null)
        }
    }

    internal fun getConfig(context: Context, restart: Boolean = true): SDKConfig? {
        if (cachedConfig == null && restart) {
            val constraints = Constraints.Builder().build()
            val inputData = Data.Builder().putBoolean("IS_CONFIG", true).build()
            val workRequest = OneTimeWorkRequestBuilder<FileReadWorker>().setConstraints(constraints).setInputData(inputData).build()
            val workManager = getWorkManager(context)
            workManager.enqueueUniqueWork(FileReadWorker::class.java.simpleName, ExistingWorkPolicy.KEEP, workRequest)
        }
        return cachedConfig
    }

    fun setConfig(sdkConfig: SDKConfig?) {
        cachedConfig = sdkConfig
    }

    fun getDetectedCountry(context: Context, restart: Boolean = true): CountryModel? {
        if (cachedCountryConfig == null && restart) {
            val constraints = Constraints.Builder().build()
            val inputData = Data.Builder().putBoolean("IS_CONFIG", false).build()
            val workRequest = OneTimeWorkRequestBuilder<FileReadWorker>().setConstraints(constraints).setInputData(inputData).build()
            val workManager = getWorkManager(context)
            workManager.enqueueUniqueWork(FileReadWorker::class.java.simpleName, ExistingWorkPolicy.KEEP, workRequest)
        }
        return cachedCountryConfig
    }

    fun setDetectedCountry(detectedCountry: CountryModel?) {
        cachedCountryConfig = detectedCountry
    }
}

internal class FileReadWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val isConfig = inputData.getBoolean("IS_CONFIG", true)
            if (isConfig) {
                if (getConfig(context, false) == null) {
                    val config = readConfig()
                    ConfigProvider.setConfig(config)
                }
            } else {
                if (ConfigProvider.getDetectedCountry(context, false) == null) {
                    val detectedCountry = readDetectedCountry()
                    ConfigProvider.setDetectedCountry(detectedCountry)
                }
            }
            Result.success()
        } catch (_: Throwable) {
            Result.success()
        }
    }

    private suspend fun readConfig(): SDKConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val configFile = File(context.applicationContext.filesDir, Files.CONFIG_FILE)
                if (configFile.exists()) {
                    val ois = ObjectInputStream(FileInputStream(configFile))
                    val config = ois.readObject() as? SDKConfig
                    ois.close()
                    config
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private suspend fun readDetectedCountry(): CountryModel? {
        return withContext(Dispatchers.IO) {
            try {
                val configFile = File(context.applicationContext.filesDir, Files.COUNTRY_CONFIG_FILE)
                if (configFile.exists()) {
                    val ois = ObjectInputStream(FileInputStream(configFile))
                    val country = ois.readObject() as? CountryModel
                    ois.close()
                    country
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}

internal class ConfigFetchWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var config: SDKConfig? = null
        val result = try {
            log("Fetching config for ${context.packageName}")
            val configService = ConfigProvider.getConfigService()
            val response = configService.getConfig(context.packageName).execute()
            if (response.isSuccessful && response.body() != null) {
                config = response.body()
                ConfigProvider.setConfig(config)
                store(config)
                log("Config fetched successfully.")
                Result.success()
            } else {
                log("Failed softly to fetch config.")
                config = read()
                ConfigProvider.setConfig(config)
                Result.success()
            }
        } catch (e: Throwable) {
            log("Failed hard to fetch config")
            Logger.ERROR.log(msg = e.message ?: "")
            config = read()
            ConfigProvider.setConfig(config)
            Result.success()
        }
        withContext(Dispatchers.Main) {
            if (config?.refetch != null) {
                ConfigProvider.fetchConfig(context, config.refetch)
            }
            val countryFetchStatus = config?.countryStatus
            if (countryFetchStatus?.active == 1 && !countryFetchStatus.url.isNullOrEmpty()) {
                fetchDetectedCountry(countryFetchStatus.url)
            }
            RTBDemand.configFetched(context, config)
        }
        return result
    }

    private fun fetchDetectedCountry(baseUrl: String) {
        try {
            val constraints = Constraints.Builder().build()
            val data = Data.Builder()
            data.putString("URL", baseUrl)
            val workerRequest: OneTimeWorkRequest = OneTimeWorkRequestBuilder<CountryDetectionWorker>().setConstraints(constraints).setInputData(data.build()).build()
            val workName: String = CountryDetectionWorker::class.java.simpleName
            val workManager = getWorkManager(context)
            getStoreService(context)
            workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, workerRequest)
        } catch (_: Throwable) {
        }
    }

    private suspend fun store(config: SDKConfig?) {
        withContext(Dispatchers.IO) {
            try {
                val configFile = File(context.applicationContext.filesDir, Files.CONFIG_FILE)
                val oos = ObjectOutputStream(FileOutputStream(configFile))
                oos.writeObject(config)
                oos.flush()
                oos.close()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun read(): SDKConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val configFile = File(context.applicationContext.filesDir, Files.CONFIG_FILE)
                if (configFile.exists()) {
                    val ois = ObjectInputStream(FileInputStream(configFile))
                    ois.readObject() as? SDKConfig
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}


internal class CountryDetectionWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        var detectedCountry: CountryModel? = null
        val result = try {
            var baseUrl = inputData.getString("URL")
            baseUrl = if (baseUrl?.contains("apiip") == true) {
                baseUrl.substring(0, baseUrl.indexOf("check"))
            } else if (baseUrl?.contains("andbeyond") == true) {
                baseUrl.substring(0, baseUrl.indexOf("maxmind"))
            } else {
                ""
            }
            if (baseUrl.isEmpty()) {
                Result.failure()
            } else {
                val countryService = ConfigProvider.getCountryService(baseUrl)
                val response = if (baseUrl.contains("apiip")) {
                    countryService.getConfig(hashMapOf("accessKey" to "7ef45bac-167a-4aa8-8c99-bc8a28f80bc5", "fields" to "countryCode,latitude,longitude,city,regionCode,ip,postalCode")).execute()
                } else {
                    countryService.getConfig().execute()
                }
                if (response.isSuccessful && response.body() != null) {
                    detectedCountry = response.body()
                    ConfigProvider.setDetectedCountry(detectedCountry)
                    store(detectedCountry)
                    Result.success()
                } else {
                    detectedCountry = read()
                    ConfigProvider.setDetectedCountry(detectedCountry)
                    Result.success()
                }
            }
        } catch (e: Throwable) {
            Logger.ERROR.log(msg = e.message ?: "")
            detectedCountry = read()
            ConfigProvider.setDetectedCountry(detectedCountry)
            Result.success()
        }
        withContext(Dispatchers.Main) {
            getConfig(context)?.let { config ->
                checkForSilentInterstitial(context, config.silentInterstitialConfig, detectedCountry)
            }
        }
        return result
    }

    private suspend fun store(detectedCountry: CountryModel?) {
        withContext(Dispatchers.IO) {
            try {
                val configFile = File(context.applicationContext.filesDir, Files.COUNTRY_CONFIG_FILE)
                val oos = ObjectOutputStream(FileOutputStream(configFile))
                oos.writeObject(detectedCountry)
                oos.flush()
                oos.close()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun read(): CountryModel? {
        return withContext(Dispatchers.IO) {
            try {
                val configFile = File(context.applicationContext.filesDir, Files.COUNTRY_CONFIG_FILE)
                if (configFile.exists()) {
                    val ois = ObjectInputStream(FileInputStream(configFile))
                    ois.readObject() as? CountryModel
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}

internal interface ConfigService {
    @GET("appconfig_{package}.js")
    fun getConfig(@Path("package") packageName: String): Call<SDKConfig>
}

internal interface CountryService {
    @GET("check")
    fun getConfig(@QueryMap params: HashMap<String, Any>): Call<CountryModel>

    @GET("maxmind.php")
    fun getConfig(): Call<CountryModel>
}