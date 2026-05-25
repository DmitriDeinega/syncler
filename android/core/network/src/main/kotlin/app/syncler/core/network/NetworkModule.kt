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
            // If the call site set Authorization explicitly (e.g. enroll
            // passes the bootstrap token directly so the Session doesn't
            // have to be temporarily unlocked with it), honor it. Only
            // inject the Session-provided token when no header is present.
            val incoming = chain.request()
            val withAuth = if (incoming.header("Authorization") != null) {
                incoming
            } else {
                val token = tokenProviders.firstNotNullOfOrNull { it.currentToken() }
                if (token.isNullOrBlank()) {
                    incoming
                } else {
                    incoming.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                }
            }
            // Phase 8 §10.11 — mixed-client gate. Phase-8-aware apps
            // advertise minimum-phase 8 so the server serves us (and
            // 426s any pre-Phase-8 client on a rotated account). We
            // set this on every outbound request — refactoring an
            // endpoint can never accidentally strip the header.
            val withPhase = withAuth.newBuilder()
                .header(CLIENT_MIN_PHASE_HEADER, CLIENT_MIN_PHASE_VALUE)
                .build()
            chain.proceed(withPhase)
        }

    /** Header name per docs/crypto-spec.md §10.11. */
    private const val CLIENT_MIN_PHASE_HEADER = "X-Syncler-Client-Min-Phase"

    /** Phase the current build supports. Bumped only on protocol upgrades. */
    private const val CLIENT_MIN_PHASE_VALUE = "8"

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

    /**
     * Unauthenticated OkHttp client for the V1.5 automated-pairing broker POST.
     *
     * The bootstrap envelope goes to a **sender-controlled URL**. The
     * default [provideOkHttpClient] adds an auth interceptor that
     * injects the user's bearer token into every outbound request, which
     * would leak the session token to the sender's broker. This client
     * is built from scratch with NO interceptors (no auth, no logging)
     * so that path stays clean (consultation 87 RED). The broker POST
     * authenticates by being able to decrypt — no HTTP-level auth header
     * is required or appropriate.
     */
    @Provides
    @Singleton
    @BrokerOkHttp
    fun provideBrokerOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectionSpecs(
                if (BuildConfig.DEBUG) {
                    listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
                } else {
                    listOf(ConnectionSpec.MODERN_TLS)
                },
            )
            .build()

    @Provides
    @Singleton
    fun provideBrokerApi(@BrokerOkHttp client: OkHttpClient, moshi: Moshi): BrokerApi =
        // Base URL placeholder — every BrokerApi call uses @Url to
        // override with the sender-supplied broker URL.
        Retrofit.Builder()
            .baseUrl("http://placeholder/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BrokerApi::class.java)
}

/**
 * Qualifier for the unauthenticated broker OkHttp client. Prevents
 * accidental reuse of the auth-interceptor-wrapped client when injecting
 * an OkHttpClient elsewhere.
 */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BrokerOkHttp
