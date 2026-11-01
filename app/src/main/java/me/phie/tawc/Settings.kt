package me.phie.tawc

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit

/**
 * Process-global settings facade, initialised once from
 * [TawcApplication.onCreate] so non-Activity code can read settings
 * without threading a Context through every call site.
 */
object Settings {
    private const val PREFS_NAME = "tawc-settings"
    private const val KEY_SERVER_RENDERER = "server_renderer"
    private const val KEY_TINT_BUFFERS_BY_TYPE = "tint_buffers_by_type"
    private const val KEY_OUTPUT_SCALE = "output_scale"
    private const val KEY_OUTPUT_REFRESH_MHZ = "output_refresh_mhz"
    private const val KEY_POINTER_EMULATION = "pointer_emulation"
    private const val KEY_EXTERNAL_ACCESS_MODE = "external_access_mode"
    private const val KEY_EXTERNAL_ALLOWED_PACKAGES = "external_allowed_packages"

    const val MIN_OUTPUT_SCALE = 0.5f
    const val MAX_OUTPUT_SCALE = 4.0f
    const val OUTPUT_SCALE_STEP = 0.25f
    const val DEFAULT_OUTPUT_SCALE = 2.0f
    val DEFAULT_TINT_BUFFERS_BY_TYPE = BuildConfig.TINT_BUFFERS_BY_TYPE_DEFAULT

    /**
     * Factory allowlist: Termux, so the launcher that already shipped with
     * TAWC keeps working after an upgrade. Only used while the preference
     * key is absent — clearing every package in the picker persists an
     * empty set and is not re-seeded. Debug builds also pre-allow
     * `com.android.shell` so `adb shell` can drive the broker/launcher.
     */
    val DEFAULT_EXTERNAL_ALLOWED_PACKAGES: Set<String> =
        if (BuildConfig.DEBUG) setOf("com.termux", "com.android.shell") else setOf("com.termux")

    private interface Store {
        var serverRenderer: ServerRenderer
        var tintBuffersByType: Boolean
        var outputScale: Float
        var outputRefreshMhz: Int
        var pointerEmulation: PointerEmulation
        var externalAccessMode: ExternalAccessMode
        var externalAllowedPackages: Set<String>
    }

    private class SharedPreferencesStore(private val prefs: SharedPreferences) : Store {
        override var serverRenderer: ServerRenderer
            get() = ServerRenderer.fromKeyOrDefault(
                prefs.getString(KEY_SERVER_RENDERER, null),
            )
            set(value) {
                // commit, not apply: AppRestart may kill the process before
                // an async write reaches disk.
                prefs.edit(commit = true) { putString(KEY_SERVER_RENDERER, value.key) }
            }

        override var tintBuffersByType: Boolean
            get() = prefs.getBoolean(KEY_TINT_BUFFERS_BY_TYPE, DEFAULT_TINT_BUFFERS_BY_TYPE)
            set(value) {
                prefs.edit { putBoolean(KEY_TINT_BUFFERS_BY_TYPE, value) }
            }

        override var outputScale: Float
            get() = snapOutputScale(prefs.getFloat(KEY_OUTPUT_SCALE, DEFAULT_OUTPUT_SCALE))
            set(value) {
                prefs.edit { putFloat(KEY_OUTPUT_SCALE, snapOutputScale(value)) }
            }

        override var outputRefreshMhz: Int
            get() = prefs.getInt(KEY_OUTPUT_REFRESH_MHZ, 0)
            set(value) {
                prefs.edit { putInt(KEY_OUTPUT_REFRESH_MHZ, value) }
            }

        override var pointerEmulation: PointerEmulation
            get() = PointerEmulation.fromKeyOrNull(prefs.getString(KEY_POINTER_EMULATION, null))
                ?: PointerEmulation.DEFAULT
            set(value) {
                prefs.edit { putString(KEY_POINTER_EMULATION, value.key) }
            }

        override var externalAccessMode: ExternalAccessMode
            get() = ExternalAccessMode.fromKeyOrNull(prefs.getString(KEY_EXTERNAL_ACCESS_MODE, null))
                ?: ExternalAccessMode.DEFAULT
            set(value) {
                prefs.edit { putString(KEY_EXTERNAL_ACCESS_MODE, value.key) }
            }

        override var externalAllowedPackages: Set<String>
            get() = prefs.getStringSet(KEY_EXTERNAL_ALLOWED_PACKAGES, null)
                ?: DEFAULT_EXTERNAL_ALLOWED_PACKAGES
            set(value) {
                prefs.edit { putStringSet(KEY_EXTERNAL_ALLOWED_PACKAGES, value) }
            }
    }

    @Volatile private var store: Store? = null

    /** Renderer selected when this process started, for [serverRendererChangedSinceStart]. */
    @Volatile private var startupServerRenderer: ServerRenderer? = null

    /** Called from [TawcApplication.onCreate]. Idempotent. */
    fun init(context: Context) {
        if (store == null) {
            store = SharedPreferencesStore(
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
            startupServerRenderer = serverRenderer
        }
    }

    private fun requireStore(): Store =
        store ?: error("Settings.init(context) was not called — see TawcApplication.onCreate")

    /**
     * Which renderer the compositor uses. Read when the compositor starts.
     * Changing it requires restarting the app because Smithay binds one
     * libEGL per process.
     */
    var serverRenderer: ServerRenderer
        get() = requireStore().serverRenderer
        set(value) { requireStore().serverRenderer = value }

    /**
     * True when the setting differs from the renderer bound at process start.
     * The EGL library cannot be swapped in-process, so this is the "restart
     * required" state used by the home screen.
     */
    val serverRendererChangedSinceStart: Boolean
        get() = startupServerRenderer?.let { it != serverRenderer } ?: false

    /**
     * Whether the compositor tints surfaces by buffer type so the
     * fallback path is visually obvious — today this means SHM
     * surfaces get a magenta wash. Debug builds default on; release
     * builds default off. Read live by the renderer (no restart).
     */
    var tintBuffersByType: Boolean
        get() = requireStore().tintBuffersByType
        set(value) { requireStore().tintBuffersByType = value }

    /** Physical pixels per Wayland logical pixel. */
    var outputScale: Float
        get() = requireStore().outputScale
        set(value) { requireStore().outputScale = snapOutputScale(value) }

    /**
     * Display refresh rate (mHz) a `CompositorActivity` last resolved; 0
     * until one has. Persisted so the compositor's first `wl_output.mode`
     * is right: only an Activity can read the display's modes.
     */
    var outputRefreshMhz: Int
        get() = requireStore().outputRefreshMhz
        set(value) { requireStore().outputRefreshMhz = value }

    /** What touch does to `wl_pointer` (notes/input.md "Pointer emulation"). */
    var pointerEmulation: PointerEmulation
        get() = requireStore().pointerEmulation
        set(value) { requireStore().pointerEmulation = value }

    /** Who may open external Wayland clients; see [ExternalAccessMode]. */
    var externalAccessMode: ExternalAccessMode
        get() = requireStore().externalAccessMode
        set(value) { requireStore().externalAccessMode = value }

    /** Package names allowed when [externalAccessMode] is
     *  [ExternalAccessMode.ALLOWLIST]. */
    var externalAllowedPackages: Set<String>
        get() = requireStore().externalAllowedPackages
        set(value) { requireStore().externalAllowedPackages = value }

    fun snapOutputScale(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_OUTPUT_SCALE
        val clamped = value.coerceIn(MIN_OUTPUT_SCALE, MAX_OUTPUT_SCALE)
        val steps = ((clamped - MIN_OUTPUT_SCALE) / OUTPUT_SCALE_STEP).toIntWithRound()
        return MIN_OUTPUT_SCALE + steps * OUTPUT_SCALE_STEP
    }

    fun formatOutputScale(value: Float): String =
        String.format(java.util.Locale.US, "%.2f", snapOutputScale(value))

    private fun Float.toIntWithRound(): Int =
        kotlin.math.floor(this + 0.5f).toInt()
}

/**
 * Who may open external Wayland clients through
 * [me.phie.tawc.compositor.ExternalWaylandService].
 */
enum class ExternalAccessMode(val key: String) {
    /** Only packages in [Settings.externalAllowedPackages] are accepted. */
    ALLOWLIST("allowlist"),

    /** Any installed app is accepted. */
    ANY("any");

    companion object {
        val DEFAULT = ALLOWLIST

        fun fromKeyOrNull(key: String?): ExternalAccessMode? =
            entries.firstOrNull { it.key == key }
    }
}

/**
 * What touchscreen input does to `wl_pointer`; see notes/input.md
 * ("Pointer emulation"). [ordinal] is the JNI encoding, matching
 * `pointer_emulation::Mode::from_index` — keep the order.
 */
enum class PointerEmulation(val key: String) {
    /** `wl_touch` only. */
    NONE("none"),

    /** `wl_touch`, and the pointer (no buttons) follows the finger. */
    HOVER("hover"),

    /** No `wl_touch`: taps click, drags hold the left button, a long
     *  press right-clicks. */
    FULL("full");

    companion object {
        val DEFAULT = HOVER

        fun fromKeyOrNull(key: String?): PointerEmulation? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Which EGL/GLES renderer the compositor uses for its own output.
 *
 * Stored by [key], independent of any client-side driver choice.
 * [DEFAULT] keeps the experimental Mesa path selected on supported debug
 * builds and falls back to vendor everywhere else.
 */
enum class ServerRenderer(val key: String, val displayName: String) {
    /** System/vendor EGL bound to the Activity's SurfaceView. */
    VENDOR_GLES("vendor", "Vendor GLES"),

    /** Private Mesa surfaceless EGL with Zink/Turnip. */
    MESA_GLES("mesa", "Mesa GLES (experimental)");

    companion object {
        val DEFAULT: ServerRenderer
            get() = if (MesaServerSupport.isSupported()) MESA_GLES else VENDOR_GLES

        fun fromKeyOrNull(key: String?): ServerRenderer? =
            entries.firstOrNull { it.key == key }

        fun fromKeyOrDefault(key: String?): ServerRenderer {
            val match = fromKeyOrNull(key) ?: DEFAULT
            return if (match == MESA_GLES && !MesaServerSupport.isSupported()) {
                VENDOR_GLES
            } else {
                match
            }
        }
    }
}
