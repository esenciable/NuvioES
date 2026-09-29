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

/**
 * The stable key a category is stored under in the per-profile preferences.
 *
 * Built from the typed identity, never from display text: translating the chip label must not change
 * what is selected or hidden. The reference fork stored the literal `"Favoritos"` and compared it in
 * three places, so translating the label silently broke favourites.
 *
 * `All` and `Favorites` use fixed sentinels; an addon category reuses the channel key convention
 * (`addonBaseUrl|catalogId`), so the two can never collide with a real addon base URL.
 */
val LiveTvCategoryId.preferenceKey: String
    get() = when (this) {
        LiveTvCategoryId.All -> CATEGORY_KEY_ALL
        LiveTvCategoryId.Favorites -> CATEGORY_KEY_FAVORITES
        is LiveTvCategoryId.Addon -> "addon:$addonBaseUrl|$catalogId"
    }

/**
 * Whether the user may remove this category from the slider.
 *
 * `All` and `Favorites` cannot be hidden: without `All` the list has no default state, and
 * `Favorites` is a function of our own store rather than a category an addon published. Attempting to
 * hide either is a no-op by construction.
 */
val LiveTvCategoryId.canBeHidden: Boolean
    get() = this !is LiveTvCategoryId.All && this !is LiveTvCategoryId.Favorites

private const val CATEGORY_KEY_ALL = "all"
private const val CATEGORY_KEY_FAVORITES = "favorites"

fun List<LiveTvChannel>.inCategory(
    category: LiveTvCategory,
    isFavorite: (LiveTvChannel) -> Boolean
): List<LiveTvChannel> = filter { category.matches(it, isFavorite) }
