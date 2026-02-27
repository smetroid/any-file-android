package com.anyproto.anyfile.di

import com.anyproto.anyfile.data.network.CoordinatorClient
import com.anyproto.anyfile.data.network.FilenodeClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import dagger.hilt.components.SingletonComponent

/**
 * Network module providing HTTP clients and gRPC clients.
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
}
