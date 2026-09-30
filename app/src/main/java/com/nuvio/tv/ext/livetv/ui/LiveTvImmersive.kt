package com.nuvio.tv.ext.livetv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether live TV is playing edge to edge right now.
 *
 * The rail that shows and hides the app's sidebar lives in the shell, while the fullscreen state lives in
 * our ViewModel, so something has to bridge the two. This is the smallest bridge available: our screen
 * writes it, the shell reads it, and it needs no DI, no new destination and no extra upstream file.
 *
 * Why an observable instead of a parameter: the shell composables are upstream code and threading a new
 * argument through both of them would cost far more than the two lines this needs. The cost of the
 * trade-off is that the state is process-wide rather than scoped to a composition, so it is written in a
 * `DisposableEffect` that also clears it on the way out -- a flag left set would hide the sidebar
 * everywhere.
 */
object LiveTvImmersive {

    var isActive by mutableStateOf(false)
        private set

    fun set(active: Boolean) {
        isActive = active
    }
}
