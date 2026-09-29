package com.nuvio.tv.ext.livetv.di

import android.content.Context
import com.nuvio.tv.ext.livetv.data.epg.EpgDiskCache
import com.nuvio.tv.ext.livetv.data.epg.EpgDocumentFetcher
import com.nuvio.tv.ext.livetv.data.epg.EpgRepository
import com.nuvio.tv.ext.livetv.data.epg.OkHttpEpgDocumentFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * The only DI the feature needs.
 *
 * Everything else is built from upstream's own bindings: the channels come from `CatalogRepository` and
 * the addons from `AddonRepository`, both already provided. This module exists because a fetcher and a
 * disk cache have no upstream equivalent.
 */
@Module
@InstallIn(SingletonComponent::class)
object LiveTvEpgModule {

    @Provides
    @Singleton
    fun provideEpgDocumentFetcher(
        @Named("addonPermissive") okHttpClient: OkHttpClient
    ): EpgDocumentFetcher = OkHttpEpgDocumentFetcher(okHttpClient, Dispatchers.IO)

    @Provides
    @Singleton
    fun provideEpgRepository(
        @ApplicationContext context: Context,
        fetcher: EpgDocumentFetcher
    ): EpgRepository = EpgRepository(
        fetcher = fetcher,
        // Its own directory: clearing guide data must not touch upstream's HTTP caches.
        cache = EpgDiskCache(root = File(context.cacheDir, "nuvioes_epg")),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        worker = Dispatchers.IO
    )
}
