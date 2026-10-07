package com.nuvio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local-plugin gate: with no TMDB key (or a failed lookup) the app falls back to the raw
 * video id, and this gate decides whether the plugin layer still runs. Regression guard for
 * "Playback unavailable" with zero attempted sources on IMDb-id content.
 */
class CanRunLocalPluginsTest {

    @Test
    fun `imdb tt ids run local plugins`() {
        assertTrue(canRunLocalPlugins("tt21097264"))
        assertTrue(canRunLocalPlugins("tt0499549"))
    }

    @Test
    fun `imdb tt ids are case insensitive`() {
        assertTrue(canRunLocalPlugins("TT0499549"))
    }

    @Test
    fun `anime namespace ids still run local plugins`() {
        assertTrue(canRunLocalPlugins("kitsu:12345"))
        assertTrue(canRunLocalPlugins("anilist:9253"))
        assertTrue(canRunLocalPlugins("mal:21"))
    }

    @Test
    fun `youtube and garbage ids do not run local plugins`() {
        assertFalse(canRunLocalPlugins("youtube:xyz"))
        assertFalse(canRunLocalPlugins(""))
        assertFalse(canRunLocalPlugins("ttx-not-an-id"))
    }
}
