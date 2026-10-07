package com.nuvio.tv.ext.livetv.di

import com.nuvio.tv.ext.livetv.data.MagisChannelLoader
import com.nuvio.tv.ext.livetv.data.MagisStreamResolver
import com.nuvio.tv.ext.livetv.data.NativeLiveSources
import com.nuvio.tv.ext.livetv.magis.MagisLiveSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The list of native live sources as ONE injected dependency.
 *
 * The ViewModel and the routing resolver consume this list without naming a single source: catalog
 * load + merge, the placeholder-addon lookup, the preview-cache bypass and the category slider all
 * iterate [NativeLiveSources]. Magis is one entry here; each source keeps its own DI module (see
 * [LiveTvMagisModule]) and its disabled-config contract — an unconfigured source is skipped, never
 * a crash.
 */
@Module
@InstallIn(SingletonComponent::class)
object LiveTvNativeModule {

    @Provides
    @Singleton
    fun provideNativeLiveSources(
        magisLoader: MagisChannelLoader,
        magisResolver: MagisStreamResolver,
    ): NativeLiveSources = NativeLiveSources(
        listOf(
            NativeLiveSources.Entry(
                source = MagisLiveSource,
                catalogLoader = magisLoader,
                streamResolver = magisResolver,
            ),
        ),
    )
}
