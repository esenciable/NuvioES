package com.nuvio.tv.ext.livetv.di

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.ext.livetv.data.MagisChannelLoader
import com.nuvio.tv.ext.livetv.data.MagisStreamResolver
import com.nuvio.tv.ext.livetv.magis.DataStoreMagisSessionStore
import com.nuvio.tv.ext.livetv.magis.MagisCrypto
import com.nuvio.tv.ext.livetv.magis.MagisLiveCatalogApi
import com.nuvio.tv.ext.livetv.magis.MagisLiveClient
import com.nuvio.tv.ext.livetv.magis.MagisLivePlaybackApi
import com.nuvio.tv.ext.livetv.magis.MagisPortalClient
import com.nuvio.tv.ext.livetv.magis.MagisRuntimeConfig
import com.nuvio.tv.ext.livetv.magis.MagisSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DI for the native Magis live source.
 *
 * The whole portal stack is built HERE — config, crypto, session store, portal, session, client —
 * because a build without usable MAGIS_* values must produce a DISABLED stack, not a crash:
 * [MagisCrypto] rejects an incomplete key at construction, so an invalid configuration falls back
 * to a gateway whose client is null and whose consumers simply report the source as unconfigured.
 */
@Module
@InstallIn(SingletonComponent::class)
object LiveTvMagisModule {

    @Provides
    @Singleton
    fun provideMagisRuntimeConfig(): MagisRuntimeConfig = MagisRuntimeConfig(
        hosts = BuildConfig.MAGIS_HOSTS.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        appId = BuildConfig.MAGIS_APP_ID.trim(),
        apkVersion = BuildConfig.MAGIS_APK_VERSION.trim(),
        threeDesKeyHex = BuildConfig.MAGIS_3DES_KEY.trim(),
    )

    @Provides
    @Singleton
    fun provideMagisLiveGateway(
        config: MagisRuntimeConfig,
        @ApplicationContext context: Context,
    ): MagisLiveGateway {
        val crypto = runCatching { MagisCrypto(config.threeDesKeyHex) }.getOrNull()
            ?: return MagisLiveGateway(catalogApi = null, playbackApi = null)
        val store = DataStoreMagisSessionStore(context)
        val portal = MagisPortalClient(
            crypto = crypto,
            config = config,
            // The portal signs every body with the minted device's sn; the session mints it and
            // changes it live, so it is READ per call, never captured.
            snProvider = { store.read()?.sn.orEmpty() },
        )
        val session = MagisSession(portal, store)
        val client = MagisLiveClient(portal, session, config)
        return MagisLiveGateway(catalogApi = client, playbackApi = client)
    }

    @Provides
    @Singleton
    fun provideMagisChannelLoader(gateway: MagisLiveGateway): MagisChannelLoader =
        MagisChannelLoader(client = gateway.catalogApi)

    @Provides
    @Singleton
    fun provideMagisStreamResolver(gateway: MagisLiveGateway): MagisStreamResolver =
        MagisStreamResolver(client = gateway.playbackApi)
}

/**
 * The Magis portal stack as the app sees it: present and usable, or absent. The separate
 * catalog/playback views exist so each adapter depends on the smallest port it needs.
 */
@Singleton
class MagisLiveGateway internal constructor(
    internal val catalogApi: MagisLiveCatalogApi?,
    internal val playbackApi: MagisLivePlaybackApi?,
) {

    /** Whether this build carries a usable Magis configuration. */
    val isConfigured: Boolean get() = catalogApi != null
}
