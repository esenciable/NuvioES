package com.nuvio.tv.ext.livetv.domain.model

/**
 * How a category is identified.
 *
 * The identity is **never** display text. The reference fork stored the literal string `"Favoritos"`
 * as the category value and compared against `"todos"` / `"favoritos"` / `"favorites"` in three
 * places, so translating the chip label silently broke favourites and made the category show every
 * channel instead. Here the built-in categories carry no text at all: the UI resolves their labels
 * from our string resources, and selection compares ids.
 */
sealed interface LiveTvCategoryId {

    /** Every channel of the selected addons. */
    data object All : LiveTvCategoryId

    /** The user's favourites, which live in our own store. */
    data object Favorites : LiveTvCategoryId

    /**
     * A category as published by an addon. Keyed by the addon's base URL and the catalog id, both of
     * which are stable even if the addon later renames the category.
     */
    data class Addon(val addonBaseUrl: String, val catalogId: String) : LiveTvCategoryId
}

/**
 * A selectable category.
 *
 * [addonCatalogName] is the name the **addon** published, which is why it can be carried as data: it
 * is not our UI copy and it cannot be translated by the app. Built-in categories leave it null and
 * the UI supplies a localised label for their id.
 */
data class LiveTvCategory(
    val id: LiveTvCategoryId,
    val addonCatalogName: String? = null
) {
    fun matches(channel: LiveTvChannel, isFavorite: (LiveTvChannel) -> Boolean): Boolean = when (id) {
        LiveTvCategoryId.All -> true
        LiveTvCategoryId.Favorites -> isFavorite(channel)
        is LiveTvCategoryId.Addon ->
            channel.addonBaseUrl == id.addonBaseUrl && channel.catalogId == id.catalogId
    }
}

fun List<LiveTvChannel>.inCategory(
    category: LiveTvCategory,
    isFavorite: (LiveTvChannel) -> Boolean
): List<LiveTvChannel> = filter { category.matches(it, isFavorite) }
