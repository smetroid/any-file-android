package com.anyproto.anyfile.di

import com.anyproto.anyfile.data.network.CoordinatorClient
import com.anyproto.anyfile.data.network.FilenodeClient
import com.anyproto.anyfile.data.network.tls.TlsConfigProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import dagger.hilt.components.SingletonComponent

/**
 * Network module providing HTTP clients, P2P clients, gRPC clients, and TLS configuration.
 *
 * Note: P2P-based clients (P2PCoordinatorClient, P2PFilenodeClient) are automatically
 * provided by Hilt via their @Inject constructors with @Singleton scope.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Content-Type", "application/grpc")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideCoordinatorClient(httpClient: OkHttpClient): CoordinatorClient {
        return CoordinatorClient(httpClient)
    }

    @Provides
    @Singleton
    fun provideFilenodeClient(httpClient: OkHttpClient): FilenodeClient {
        return FilenodeClient(httpClient)
    }

    // P2P-based clients (correct way to talk to any-sync infrastructure)
    // These use the full protocol stack: TLS → Handshake → Yamux → DRPC
    // Note: P2PCoordinatorClient and P2PFilenodeClient are automatically provided by Hilt
    // via their @Inject constructors with @Singleton scope. No need for explicit @Provides.

    @Provides
    @Singleton
    fun provideTlsConfigProvider(): TlsConfigProvider {
        return TlsConfigProvider()
    }
}
