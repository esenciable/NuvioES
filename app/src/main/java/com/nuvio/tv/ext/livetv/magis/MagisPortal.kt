package com.nuvio.tv.ext.livetv.magis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Runtime configuration for talking to the Magis portal.
 *
 * TODO(M4): the values must come from the app's settings/build config — hosts, the 3DES master
 *  key and the app identity are deployment secrets, NOT code constants. Nothing in this package
 *  hardcodes them; whoever wires this (M2/M3 DI, M4 settings UI) injects the values here, the
 *  same way the fork injects other `local.properties`-sourced secrets through BuildConfig.
 */
data class MagisRuntimeConfig(
    /** Bare portal hosts (no scheme), in preference order; e.g. `host1.example.com`. */
    val hosts: List<String>,
    /** The `appId` the portal expects in the `apk` header and the device dict. */
    val appId: String,
    /** The device dict's `apkVersion` (NOT the fixed `apkVer: 43404` header; different fields). */
    val apkVersion: String,
    /** 48-hex-character 3DES master key. */
    val threeDesKeyHex: String,
)

/**
 * Transport for the Magis portal. The layers above (session, catalog, resolution) depend on
 * this interface and not on [MagisPortalClient], so a double can be injected into tests.
 *
 * Port of the TS reference (`kino-light-addon/src/magis/portal.ts`) and the verified Kotlin
 * original (`kino-light-main` `MagisPortalClient.kt`).
 */
internal interface MagisPortalLike {
    suspend fun call(
        path: String,
        bean: Map<String, Any?> = emptyMap(),
        baseFields: Boolean = true,
        userId: String = "",
        userToken: String = "",
        /** Overrides the device dict's `sn` for this one call (`null` keeps the stored session's). */
        sn: String? = null,
    ): MagisResult<JSONObject>
}

/**
 * Talks directly to the Magis portal. Three things aren't negotiable (if missing, the portal
 * answers "版本已停止使用" or 未登录):
 *  - the body goes encrypted with [MagisCrypto] (hex(base64(3DES))), never bare JSON;
 *  - EVERY body gets [deviceDict]'s ~15 fields glued on (the app's interceptor);
 *  - the `apk` / `apkVer` / `spkgVer` headers always go.
 *
 * [hosts] arrives through [MagisRuntimeConfig] so tests can point the client at a local server;
 * the real values are injected by whoever wires the config (see [MagisRuntimeConfig]'s M4 note).
 */
internal class MagisPortalClient(
    private val crypto: MagisCrypto,
    private val config: MagisRuntimeConfig,
    /** The minted device's `sn`, read on every call: `MagisSession` mints it and changes it live. */
    private val snProvider: () -> String = { "" },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : MagisPortalLike {

    private val jsonType = "application/json;charset=utf-8".toMediaType()

    /** Minimum pace between calls: the portal is sensitive to bursts (the TS reference awaits
     *  a 400 ms queue turn before every request). */
    private val rateLimit = Mutex()
    private var lastCallMs = 0L

    /** Host that worked last time: tried first so as not to pay a dead host's timeout every call. */
    @Volatile
    private var preferredHost: String? = null

    override suspend fun call(
        path: String,
        bean: Map<String, Any?>,
        baseFields: Boolean,
        userId: String,
        userToken: String,
        sn: String?,
    ): MagisResult<JSONObject> {
        val body = buildMap<String, Any?> {
            if (baseFields) {
                put("portalCode", PORTAL_CODE)
                put("userId", userId)
                put("userToken", userToken)
            }
            putAll(bean)
            putAll(deviceDict(sn ?: snProvider()))
        }
        val wire = crypto.encryptBody(JSONObject(body).toString())

        waitTurn()

        var lastError: Throwable? = null
        for (host in hostOrder()) {
            val request = Request.Builder()
                .url("https://$host/api/portalCore/$path")
                .post(wire.toRequestBody(jsonType))
                .header("apk", config.appId)
                .header("apkVer", APK_VER)
                .header("spkgVer", SPKG_VER)
                .header("User-Agent", "okhttp/3.12.12")
                .build()
            try {
                val raw = withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { it.body?.string().orEmpty() }
                }
                val j = JSONObject(raw)
                preferredHost = host
                val rc = j.optString("returnCode").takeIf { it.isNotEmpty() }
                if (rc != null && rc != "0") {
                    return MagisResult.PortalError(rc, j.optString("errorMessage").ifBlank { null })
                }
                val data = j.optString("data")
                return if (data.isNotEmpty()) {
                    MagisResult.Ok(JSONObject(crypto.decryptBlob(data)))
                } else {
                    MagisResult.Ok(j)
                }
            } catch (e: Throwable) {
                // Network down, TLS, or a response that isn't JSON: this host is no good, try
                // the next one. A MagisResult.PortalError above already returned.
                lastError = e
            }
        }
        return MagisResult.RedError(lastError ?: IllegalStateException("sin hosts configurados"))
    }

    private fun hostOrder(): List<String> {
        val preferred = preferredHost ?: return config.hosts
        return listOf(preferred) + config.hosts.filter { it != preferred }
    }

    private suspend fun waitTurn() = rateLimit.withLock {
        val elapsed = System.currentTimeMillis() - lastCallMs
        if (lastCallMs != 0L && elapsed < RATE_LIMIT_MS) delay(RATE_LIMIT_MS - elapsed)
        lastCallMs = System.currentTimeMillis()
    }

    /**
     * The ~15 fields the original app glues to every body. The fixed values are the emulator's,
     * the one the protocol was captured with: changing them is untested and the portal validates
     * some of them against the minted device. `reserve1`/`deviceToken`/`drmId` go EMPTY on
     * purpose — that's how production sends them.
     */
    private fun deviceDict(sn: String): Map<String, Any?> = mapOf(
        "loginType" to "2",
        "appLanguage" to "en",
        "apkVersion" to config.apkVersion,
        "sysVersion" to SPKG_VER,
        "appId" to config.appId,
        "hardwareInfo" to "ranchu",
        "model" to "sdk_gphone64_arm64",
        "product" to "sdk_gphone64_arm64",
        "cpu" to "arm64-v8a",
        "B29" to "",
        "reserve1" to "",
        "deviceToken" to "",
        "sn" to sn,
        "drmId" to "",
        "sdkVer" to 36,
    )

    private companion object {
        const val PORTAL_CODE = "masnew"

        /** `apkVer` is a fixed literal, different from the device dict's `apkVersion`: they're
         *  two different fields of the original app, not a copy-paste error. */
        const val APK_VER = "43404"
        const val SPKG_VER = "2025-08-07 05:40:11_36_16_"
        const val RATE_LIMIT_MS = 400L
    }
}
