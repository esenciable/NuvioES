package com.nuvio.tv.ext.livetv.di

import com.nuvio.tv.ext.livetv.data.MagisChannelLoader
import com.nuvio.tv.ext.livetv.data.MagisStreamResolver
import com.nuvio.tv.ext.livetv.data.NativeLiveSources
import com.nuvio.tv.ext.livetv.data.premiumtv.PremiumTvChannelLoader
import com.nuvio.tv.ext.livetv.data.premiumtv.PremiumTvStreamResolver
import com.nuvio.tv.ext.livetv.magis.MagisLiveSource
import com.nuvio.tv.ext.livetv.premiumtv.PremiumTvLiveSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The list of native live sources as ONE injected dependency.
 *
 * The ViewModel and the routing resolver consume this list without naming a single one of them:
 * catalog load + merge, the placeholder-addon lookup, the preview-cache bypass and the category
 * slider all iterate [NativeLiveSources]. Magis and PremiumTV are one entry each here; each source
 * keeps its own DI module (see [LiveTvMagisModule] and [LiveTvPremiumTvModule]) — Magis with its
 * disabled-config contract (an unconfigured source is skipped, never a crash), PremiumTV always
 * configured because its list is public.
 */
@Module
@InstallIn(SingletonComponent::class)
object LiveTvNativeModule {

    @Provides
    @Singleton
    fun provideNativeLiveSources(
        magisLoader: MagisChannelLoader,
        magisResolver: MagisStreamResolver,
        premiumTvLoader: PremiumTvChannelLoader,
        premiumTvResolver: PremiumTvStreamResolver,
    ): NativeLiveSources = NativeLiveSources(
        listOf(
            NativeLiveSources.Entry(
                source = MagisLiveSource,
                catalogLoader = magisLoader,
                streamResolver = magisResolver,
            ),
            NativeLiveSources.Entry(
                source = PremiumTvLiveSource,
                catalogLoader = premiumTvLoader,
                streamResolver = premiumTvResolver,
            ),
        ),
    )
}
