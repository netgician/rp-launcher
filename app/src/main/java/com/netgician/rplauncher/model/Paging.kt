package com.netgician.rplauncher.model

import kotlin.math.max

/** One slot on a page: an item, or a big Next/Back tile. Paging uses tiles, never swipes. */
sealed interface Slot<out T> {
    data class Item<T>(val value: T) : Slot<T>
    data object Next : Slot<Nothing>
    data object Back : Slot<Nothing>
}

object Paging {
    /**
     * Splits [items] into pages of at most [slots] slots. Every page but the first starts
     * with Back; every page but the last ends with Next. Items keep their order and each
     * appears exactly once.
     */
    fun <T> paginate(items: List<T>, slots: Int): List<List<Slot<T>>> {
        if (items.size <= max(1, slots)) return listOf(items.map { Slot.Item(it) })
        // A middle page needs room for Back, at least one item and Next.
        val s = max(3, slots)
        val pages = mutableListOf<List<Slot<T>>>()
        var i = 0
        while (i < items.size) {
            val page = mutableListOf<Slot<T>>()
            if (pages.isNotEmpty()) page += Slot.Back
            val room = s - page.size
            val remaining = items.size - i
            if (remaining <= room) {
                items.subList(i, items.size).forEach { page += Slot.Item(it) }
                i = items.size
            } else {
                val take = room - 1
                items.subList(i, i + take).forEach { page += Slot.Item(it) }
                i += take
                page += Slot.Next
            }
            pages += page
        }
        return pages
    }
}
