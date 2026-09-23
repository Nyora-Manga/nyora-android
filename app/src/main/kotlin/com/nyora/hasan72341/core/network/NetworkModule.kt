package com.nyora.hasan72341.core.network

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.nyora.hasan72341.BuildConfig
import com.nyora.hasan72341.core.network.cookies.AndroidCookieJar
import com.nyora.hasan72341.core.network.cookies.MutableCookieJar
import com.nyora.hasan72341.core.network.cookies.PreferencesCookieJar
import com.nyora.hasan72341.core.network.imageproxy.ImageProxyInterceptor
import com.nyora.hasan72341.core.network.imageproxy.RealImageProxyInterceptor
import com.nyora.hasan72341.core.network.proxy.ProxyProvider
import com.nyora.hasan72341.core.prefs.AppSettings
import com.nyora.hasan72341.core.util.ext.assertNotInMainThread
import com.nyora.hasan72341.core.util.ext.printStackTraceDebug
import com.nyora.hasan72341.local.data.LocalStorageManager
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface NetworkModule {

    @Binds
    fun bindCookieJar(androidCookieJar: MutableCookieJar): CookieJar

    @Binds
    fun bindImageProxyInterceptor(impl: RealImageProxyInterceptor): ImageProxyInterceptor

    companion object {

        @Provides
        @Singleton
        fun provideCookieJar(
            @ApplicationContext context: Context
        ): MutableCookieJar = runCatching {
            AndroidCookieJar()
        }.getOrElse { e ->
            e.printStackTraceDebug("NetworkModule::provideCookieJar")
            // WebView is not available
            PreferencesCookieJar(context)
        }

        @Provides
        @Singleton
        fun provideHttpCache(
            localStorageManager: LocalStorageManager,
        ): Cache = localStorageManager.createHttpCache()

        @Provides
        @Singleton
        @BaseHttpClient
        fun provideBaseHttpClient(
            @ApplicationContext contextProvider: Provider<Context>,
            cache: Cache,
            cookieJar: CookieJar,
            settings: AppSettings,
            proxyProvider: ProxyProvider,
        ): OkHttpClient = OkHttpClient.Builder().apply {
            assertNotInMainThread()
            connectTimeout(20, TimeUnit.SECONDS)
            readTimeout(60, TimeUnit.SECONDS)
            writeTimeout(20, TimeUnit.SECONDS)
            cookieJar(cookieJar)
            proxySelector(proxyProvider.selector)
            proxyAuthenticator(proxyProvider.authenticator)
            dns(DoHManager(cache, settings))
            if (settings.isSSLBypassEnabled) {
                disableCertificateVerification()
            } else {
                installExtraCertificates(contextProvider.get())
            }
            cache(cache)
            //addInterceptor(GZipInterceptor())
            addInterceptor(CloudFlareInterceptor())
            addInterceptor(RateLimitInterceptor())
            if (BuildConfig.DEBUG) {
                addInterceptor(CurlLoggingInterceptor())
            }
        }.build()

        @Provides
        @Singleton
        @MangaHttpClient
        fun provideMangaHttpClient(
            @BaseHttpClient baseClient: OkHttpClient,
            commonHeadersInterceptor: CommonHeadersInterceptor,
        ): OkHttpClient = baseClient.newBuilder().apply {
            addNetworkInterceptor(CacheLimitInterceptor())
            addInterceptor(commonHeadersInterceptor)
            // Restore the *Lib family (api.cdnlibs.org): supply Origin/Referer and
            // flatten the new ProseMirror `summary` object back to a string.
            addInterceptor(LibApiHeadersInterceptor())
            // Recovers a Madara chapter list whose admin-AJAX action answers 4xx.
            addInterceptor(MadaraChapterFix)
            // Undoes the per-page XOR MANGA Plus serves its images under.
            addInterceptor(MangaPlusImageInterceptor())
        }.build()

        @Provides
        @Singleton
        fun provideJson(): Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    }
}
