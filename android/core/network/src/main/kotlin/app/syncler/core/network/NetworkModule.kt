package app.syncler.core.network

import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthTokenProviderSetModule {
    @Multibinds
    abstract fun authTokenProviders(): Set<AuthTokenProvider>
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenProviders: Set<@JvmSuppressWildcards AuthTokenProvider>): Interceptor =
        Interceptor { chain ->
            val token = tokenProviders.firstNotNullOfOrNull { it.currentToken() }
            val request = if (token.isNullOrBlank()) {
                chain.request()
            } else {
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            }
            chain.proceed(request)
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(authInterceptor: Interceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        // OkHttp's default connectionSpecs is [MODERN_TLS, COMPATIBLE_TLS]
        // and refuses cleartext at the connection layer. Debug builds point
        // SERVER_BASE_URL at http://10.0.2.2:8000/ (or a LAN IP), so we need
        // CLEARTEXT in the spec list for dev. Release stays HTTPS-only.
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectionSpecs(
                if (BuildConfig.DEBUG) {
                    listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
                } else {
                    listOf(ConnectionSpec.MODERN_TLS)
                },
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(moshi: Moshi, okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideSynclerApi(retrofit: Retrofit): SynclerApi = retrofit.create(SynclerApi::class.java)
}
