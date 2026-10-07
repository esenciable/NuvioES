package com.nuvio.tv.ext.livetv.di

import com.nuvio.tv.ext.livetv.data.premiumtv.M3uDocumentFetcher
import com.nuvio.tv.ext.livetv.data.premiumtv.OkHttpM3uFetcher
import com.nuvio.tv.ext.livetv.data.premiumtv.PremiumTvM3uFeed
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

/**
 * DI for the PremiumTV M3U feed. The fetch rides the fork's own addon-permissive client — the same
 * base the EPG fetcher builds on — with the Chrome UA stamped per request; the feed itself owns the
 * TTL and the last-known-good retention, so a failed fetch never empties the catalog.
 */
@Module
@InstallIn(SingletonComponent::class)
object LiveTvPremiumTvModule {

    @Provides
    @Singleton
    fun provideM3uDocumentFetcher(
        @Named("addonPermissive") okHttpClient: OkHttpClient,
    ): M3uDocumentFetcher = OkHttpM3uFetcher(okHttpClient, Dispatchers.IO)

    @Provides
    @Singleton
    fun providePremiumTvM3uFeed(fetcher: M3uDocumentFetcher): PremiumTvM3uFeed =
        PremiumTvM3uFeed(fetcher = fetcher)
}
