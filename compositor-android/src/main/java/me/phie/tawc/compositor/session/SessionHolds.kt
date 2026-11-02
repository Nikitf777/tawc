package me.phie.tawc.compositor.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What keeps the process in the foreground. */
sealed interface Reason {
    /** The compositor is running, currently presenting [windowCount] windows. */
    data class Compositor(val windowCount: Int) : Reason
}

/** One acquired [Reason]. [release] is idempotent and thread-safe. */
interface Hold {
    /** Swap the reason this hold reports (e.g. a new window count). */
    fun update(reason: Reason)
    fun release()
}

/**
 * Process-wide registry of what keeps TAWC alive. While it is non-empty
 * [SessionService] runs in the foreground.
 *
 * Callable from any thread. The registry never touches Android itself:
 * [starter] (set once by [SessionService.install]) is invoked, outside
 * the lock, on every acquire that finds no service alive.
 */
object SessionHolds {
    private val lock = Any()
    private val holds = LinkedHashMap<Hold, Reason>()
    private val state = MutableStateFlow<List<Reason>>(emptyList())

    /** The live [SessionService] instance, as an opaque token. */
    private var service: Any? = null

    @Volatile
    var starter: (() -> Unit)? = null

    val reasons: StateFlow<List<Reason>> = state.asStateFlow()

    fun acquire(reason: Reason): Hold {
        val hold = HoldImpl()
        val start = synchronized(lock) {
            holds[hold] = reason
            publish()
            service == null
        }
        if (start) starter?.invoke()
        return hold
    }

    /** Service side: [token]'s `onCreate`. */
    internal fun serviceStarted(token: Any) = synchronized(lock) { service = token }

    /**
     * Service side: if nothing is held, mark the service dead and return
     * true — the caller must then stop. Atomic with [acquire].
     */
    internal fun serviceStopIfIdle(token: Any): Boolean = synchronized(lock) {
        if (holds.isNotEmpty()) return false
        if (service === token) service = null
        true
    }

    /** Service side: [token]'s `onDestroy`. */
    internal fun serviceStopped(token: Any) = synchronized(lock) {
        if (service === token) service = null
    }

    private fun publish() {
        state.value = holds.values.toList()
    }

    private class HoldImpl : Hold {
        override fun update(reason: Reason) = synchronized(lock) {
            if (holds.containsKey(this)) {
                holds[this] = reason
                publish()
            }
        }

        override fun release() = synchronized(lock) {
            if (holds.remove(this) != null) publish()
        }
    }

    /** Unit tests only: forget everything. */
    internal fun resetForTest() = synchronized(lock) {
        holds.clear()
        service = null
        starter = null
        publish()
    }
}

/** What the notification says, reduced from a list of reasons. */
data class SessionSummary(val windows: Int) {
    companion object {
        fun of(reasons: List<Reason>): SessionSummary {
            var windows = 0
            for (r in reasons) {
                when (r) {
                    is Reason.Compositor -> windows += r.windowCount
                }
            }
            return SessionSummary(windows)
        }
    }
}
