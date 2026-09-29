package com.nuvio.tv.ext.livetv.domain.model

/** Where an EPG source came from, which decides how the UI presents and controls it. */
sealed interface EpgSourceOrigin {

    /** Shipped with the app as a fallback. */
    data object BuiltIn : EpgSourceOrigin

    /** Published by one of the installed addons, discovered automatically. */
    data class FromAddon(val addonBaseUrl: String) : EpgSourceOrigin

    /** Typed in by the user. */
    data object User : EpgSourceOrigin
}

/**
 * One XMLTV source.
 *
 * [id] is stable and derived from the origin, never from the display name, so a source keeps its
 * enabled/disabled state across reloads and through a rename.
 */
data class EpgSource(
    val id: String,
    val name: String,
    val url: String,
    val origin: EpgSourceOrigin
)
