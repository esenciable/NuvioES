package com.nuvio.tv.ext.livetv.di

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.ext.livetv.data.MagisChannelLoader
import com.nuvio.tv.ext.livetv.data.MagisStreamResolver
import com.nuvio.tv.ext.livetv.magis.DataStoreMagisConfigStore
import com.nuvio.tv.ext.livetv.magis.DataStoreMagisSessionStore
import com.nuvio.tv.ext.livetv.magis.MagisConfigProvider
import com.nuvio.tv.ext.livetv.magis.MagisConfigStore
import com.nuvio.tv.ext.livetv.magis.MagisCryptoHolder
import com.nuvio.tv.ext.livetv.magis.MagisLiveCatalogApi
import com.nuvio.tv.ext.livetv.magis.MagisLiveClient
import com.nuvio.tv.ext.livetv.magis.MagisLivePlaybackApi
import com.nuvio.tv.ext.livetv.magis.MagisPortalClient
import com.nuvio.tv.ext.livetv.magis.MagisRemoteConfig
import com.nuvio.tv.ext.livetv.magis.MagisRuntimeConfig
import com.nuvio.tv.ext.livetv.magis.MagisSession
import com.nuvio.tv.ext.livetv.magis.readOr
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * DI for the native Magis live source.
 *
 * The whole portal stack is built HERE — config, crypto, session store, portal, session, client —
 * because a build without usable MAGIS_* values must produce a DISABLED stack, not a crash:
 * [MagisCrypto] rejects an incomplete key at construction, so an invalid configuration falls back
 * to a gateway whose client is null and whose consumers simply report the source as unconfigured.
 *
 * The runtime config is REMOTE-first (`magis-config.json`, see [MagisRemoteConfig]): startup
 * uses the disk cache or the `BuildConfig` fallback immediately and adopts the remote document
 * when the background fetch lands, so a portal rotation is fixed with one push to the plugins
 * repo instead of a new APK.
 */
@Module
@InstallIn(SingletonComponent::class)
object LiveTvMagisModule {

    @Provides
    @Singleton
    internal fun provideMagisConfigStore(@ApplicationContext context: Context): MagisConfigStore =
        DataStoreMagisConfigStore(context)

    /** `BuildConfig` is only the FINAL fallback: disk cache first, remote later, never network. */
    private fun buildConfigFallback() = MagisRuntimeConfig(
        hosts = BuildConfig.MAGIS_HOSTS.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        appId = BuildConfig.MAGIS_APP_ID.trim(),
        apkVersion = BuildConfig.MAGIS_APK_VERSION.trim(),
        apkVerHeader = BuildConfig.MAGIS_APK_VER_HEADER.trim(),
        spkgVer = BuildConfig.MAGIS_SPKG_VER.trim(),
        threeDesKeyHex = BuildConfig.MAGIS_3DES_KEY.trim(),
    )

    @Provides
    @Singleton
    internal fun provideMagisConfigProvider(store: MagisConfigStore): MagisConfigProvider =
        MagisConfigProvider(store.readOr(buildConfigFallback()))

    @Provides
    @Singleton
    internal fun provideMagisRemoteConfig(
        provider: MagisConfigProvider,
        store: MagisConfigStore,
    ): MagisRemoteConfig = MagisRemoteConfig(provider, store)

    @Provides
    @Singleton
    internal fun provideMagisLiveGateway(
        provider: MagisConfigProvider,
        remoteConfig: MagisRemoteConfig,
        @ApplicationContext context: Context,
    ): MagisLiveGateway {
        // The crypto rebuilds ONLY when the config's 3DES key changes, so a key rotation lands
        // without a restart; the portal/live client read the config PER USE and never capture
        // a snapshot.
        val holder = MagisCryptoHolder { provider.current.threeDesKeyHex }
        val crypto = holder.get()
            ?: return MagisLiveGateway(catalogApi = null, playbackApi = null)
        val store = DataStoreMagisSessionStore(context)
        val portal = MagisPortalClient(
            configProvider = { provider.current },
            cryptoProvider = { holder.get() },
            // The portal signs every body with the minted device's sn; the session mints it and
            // changes it live, so it is READ per call, never captured.
            snProvider = { store.read()?.sn.orEmpty() },
        )
        val session = MagisSession(portal, store)
        val client = MagisLiveClient(portal, session, configProvider = { provider.current })
        // Background refresh: launched ASYNC on a process-lifetime scope the moment the gateway
        // is provisioned — the first fetch fires without blocking injection, and the loop keeps
        // retrying on its tick until a VALID result arms the few-hours interval. It runs even
        // when this build starts disabled: the fetched config is persisted to disk, so the next
        // launch comes up enabled from the cache ("source absent, never a crash" is preserved).
        remoteConfig.start(CoroutineScope(SupervisorJob() + Dispatchers.IO))
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
