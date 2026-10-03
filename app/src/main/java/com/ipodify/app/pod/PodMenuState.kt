package com.ipodify.app.pod

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

data class MenuItem(
    val title: String,
    val hasArrow: Boolean = true,
    /** A second line, e.g. the artist. */
    val subtitle: String? = null,
    /** Cover art, for [PodCoverFlow]. */
    /** Whatever Coil loads the cover from: a Bitmap or a URI string. */
    val artworkUrl: Any? = null,
    val onClick: () -> Unit
)

class PodMenuState {
    var isNowPlaying = mutableStateOf(false)
    var isScrubbingMode = mutableStateOf(false)
    var scrubPositionMs = mutableStateOf<Long?>(null)
    var title = mutableStateOf("iPodify")
    var items = mutableStateListOf<MenuItem>()
    var selectedIndex = mutableStateOf(0)

    /** The item that is playing now, marked in [PodCoverFlow]; -1 for none. */
    var playingIndex = mutableStateOf(-1)

    /** The music volume, 0–1, as last set from the wheel on Now Playing. */
    var volume = mutableStateOf(0f)

    /** Bumped on every wheel turn on Now Playing, so the volume bar shows itself. */
    var volumeTouches = mutableStateOf(0)
    
    val history = mutableListOf<MenuSnapshot>()

    fun pushMenu(newTitle: String, newItems: List<MenuItem>) {
        if (items.isNotEmpty()) {
            history.add(MenuSnapshot(title.value, items.toList(), selectedIndex.value))
        }
        title.value = newTitle
        items.clear()
        items.addAll(newItems)
        selectedIndex.value = 0
    }

    fun popMenu(): Boolean {
        if (history.isNotEmpty()) {
            val snapshot = history.removeLast()
            title.value = snapshot.title
            items.clear()
            items.addAll(snapshot.items)
            selectedIndex.value = snapshot.selectedIndex
            return true
        }
        return false
    }

    fun scroll(ticks: Int): Boolean {
        if (items.isEmpty() || isNowPlaying.value) return false
        val oldIndex = selectedIndex.value
        val newIndex = oldIndex + ticks
        selectedIndex.value = newIndex.coerceIn(0, items.size - 1)
        return oldIndex != selectedIndex.value
    }
}

data class MenuSnapshot(
    val title: String,
    val items: List<MenuItem>,
    val selectedIndex: Int
)
