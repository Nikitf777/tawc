package me.phie.tawc.compositor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class OpenWindow(
    val activityId: String,
    val title: String,
    val appId: String,
    val desktopId: String,
    val desktopName: String,
    val iconPath: String,
    val focused: Boolean,
    val fullscreen: Boolean,
    val lastFocusedAtMillis: Long,
)

class WindowRegistry {
    private val records = LinkedHashMap<String, OpenWindow>()
    private val mutableWindows = MutableStateFlow<List<OpenWindow>>(emptyList())

    val windows: StateFlow<List<OpenWindow>> = mutableWindows.asStateFlow()

    fun get(activityId: String): OpenWindow? = records[activityId]

    fun updateMetadata(
        activityId: String,
        title: String,
        appId: String,
        desktopId: String,
        desktopName: String,
        iconPath: String,
    ): OpenWindow {
        val old = records[activityId]
        val next = old?.copy(
            title = title,
            appId = appId,
            desktopId = desktopId,
            desktopName = desktopName,
            iconPath = iconPath,
        ) ?: OpenWindow(
            activityId = activityId,
            title = title,
            appId = appId,
            desktopId = desktopId,
            desktopName = desktopName,
            iconPath = iconPath,
            focused = false,
            fullscreen = false,
            lastFocusedAtMillis = 0L,
        )
        records[activityId] = next
        publish()
        return next
    }

    fun setFocused(activityId: String, focused: Boolean, nowMillis: Long) {
        records.putIfAbsent(activityId, emptyWindow(activityId))
        if (focused) {
            for ((id, window) in records) {
                records[id] = window.copy(focused = id == activityId)
            }
        } else {
            val old = records[activityId] ?: return
            records[activityId] = old.copy(focused = false)
        }
        if (focused) {
            records[activityId]?.let { records[activityId] = it.copy(lastFocusedAtMillis = nowMillis) }
        }
        publish()
    }

    fun setFullscreen(activityId: String, fullscreen: Boolean) {
        val old = records[activityId] ?: emptyWindow(activityId)
        records[activityId] = old.copy(fullscreen = fullscreen)
        publish()
    }

    fun remove(activityId: String) {
        if (records.remove(activityId) != null) publish()
    }

    fun clear() {
        if (records.isEmpty()) return
        records.clear()
        publish()
    }

    private fun publish() {
        mutableWindows.value = records.values.sortedWith(
            compareByDescending<OpenWindow> { it.focused }
                .thenByDescending { it.lastFocusedAtMillis }
                .thenBy { it.title.ifEmpty { it.desktopName.ifEmpty { it.appId } }.lowercase() }
        )
    }

    private fun emptyWindow(activityId: String) = OpenWindow(
        activityId = activityId,
        title = "",
        appId = "",
        desktopId = "",
        desktopName = "",
        iconPath = "",
        focused = false,
        fullscreen = false,
        lastFocusedAtMillis = 0L,
    )
}
