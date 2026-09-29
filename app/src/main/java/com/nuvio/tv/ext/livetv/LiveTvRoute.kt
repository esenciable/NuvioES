@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ext.livetv

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.ext.livetv.ui.LiveTvScreen
import com.nuvio.tv.ui.navigation.Screen

/**
 * The single entry point of the Live TV feature, and the only thing the upstream
 * navigation host knows about.
 *
 * Navigation stays behind this seam on purpose. The handoff to the main player
 * goes through `Screen.Player.createRoute(...)`, which is upstream code and the
 * most fragile thing we couple to; when upstream changes that signature, the fix
 * lands here -- inside our own package -- and the one-line hook in
 * `NuvioNavHost.kt` does not change.
 */
@Composable
fun LiveTvRoute(navController: NavController) {
    LiveTvScreen(
        onBack = { navController.popBackStack() },
        onManageAddons = { navController.navigate(Screen.AddonManager.route) }
    )
}
