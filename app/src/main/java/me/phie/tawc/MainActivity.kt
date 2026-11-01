package me.phie.tawc

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import me.phie.tawc.compositor.ExternalWaylandAccess
import me.phie.tawc.compositor.NativeBridge
import me.phie.tawc.licenses.LicensesActivity
import me.phie.tawc.ui.externalAccessGroup
import me.phie.tawc.ui.pointerEmulationGroup
import me.phie.tawc.ui.serverRendererGroup
import me.phie.tawc.ui.tawcCard
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp

/**
 * The only screen. It configures how external Wayland clients connect and
 * how the compositor renders, and offers a process restart so the selected
 * EGL renderer is loaded by the next process.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var allowlistSlot: LinearLayout
    private lateinit var allowlistSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A killed compositor process cannot remove its window tasks. If no
        // compositor is running in this process, any such task is stale.
        if (!NativeBridge.nativeIsCompositorRunning()) {
            CompositorTasks.removeStale(this, this)
        }

        requestNotificationPermissionIfNeeded()

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.addView(
            MaterialToolbar(this).apply { title = getString(R.string.app_name) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(
            buildExternalAccessCard(),
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )
        if (MesaServerSupport.isSupported()) {
            column.addView(
                buildSectionCard(
                    getString(R.string.settings_compositor_renderer),
                    serverRendererGroup(Settings.serverRenderer) { showRendererRestartPrompt(it) },
                ),
                verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
            )
        }
        column.addView(
            buildSectionCard(
                getString(R.string.settings_pointer_emulation),
                pointerEmulationGroup(Settings.pointerEmulation) {
                    Settings.pointerEmulation = it
                    NativeBridge.nativeSetPointerEmulation(it.ordinal)
                },
            ),
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )
        column.addView(
            buildScaleCard(),
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )
        column.addView(
            buildSectionCard(getString(R.string.settings_diagnostics), buildDiagnosticsSettings()),
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )
        column.addView(
            tonalButton(getString(R.string.action_restart_compositor)) { AppRestart.restart(this) },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad),
        )
        column.addView(
            TextView(this).apply {
                text = getString(
                    R.string.settings_about_detail,
                    BuildConfig.APPLICATION_ID,
                    BuildConfig.VERSION_NAME,
                )
                textSize = 14f
                setTextIsSelectable(true)
                alpha = 0.7f
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2),
        )
        column.addView(
            tonalButton(getString(R.string.settings_about_licenses)) {
                startActivity(Intent(this, LicensesActivity::class.java))
            },
            verticalLp(WRAP_CONTENT, WRAP_CONTENT),
        )

        root.addView(
            ScrollView(this).apply {
                addView(column, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshAllowlist()
    }

    private fun buildSectionCard(title: String, body: View): View {
        val cardPad = (12 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPad, cardPad, cardPad, cardPad)
        }
        column.addView(
            TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
        column.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return tawcCard().apply { addView(column) }
    }

    private fun buildSubheading(stringRes: Int): TextView =
        TextView(this).apply {
            text = getString(stringRes)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }

    private fun buildExternalAccessCard(): View {
        val cardPad = (12 * resources.displayMetrics.density).toInt()
        allowlistSlot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@MainActivity).apply {
                    textSize = 13f
                    setPadding(0, cardPad / 2, 0, cardPad / 2)
                    allowlistSummary = this
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
            addView(
                tonalButton(getString(R.string.external_access_manage)) { showAllowlistPicker() },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                externalAccessGroup(Settings.externalAccessMode) {
                    Settings.externalAccessMode = it
                    refreshAllowlist()
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = getString(R.string.external_access_warning)
                    textSize = 13f
                    setPadding(0, cardPad / 2, 0, cardPad / 2)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
            addView(allowlistSlot, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        return buildSectionCard(getString(R.string.settings_external_apps), body)
    }

    private fun refreshAllowlist() {
        val visible = Settings.externalAccessMode == ExternalAccessMode.ALLOWLIST
        allowlistSlot.visibility = if (visible) View.VISIBLE else View.GONE
        val count = Settings.externalAllowedPackages.size
        allowlistSummary.text = when (count) {
            0 -> getString(R.string.external_access_summary_none)
            1 -> getString(R.string.external_access_summary_one, count)
            else -> getString(R.string.external_access_summary_many, count)
        }
    }

    /**
     * Multi-choice dialog over launchable packages plus anything already
     * allowed. Toggling only updates the dialog's pending set; OK writes the
     * final set to [Settings].
     */
    private fun showAllowlistPicker() {
        val pm = packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            0,
        ).mapNotNull { it.activityInfo?.packageName }
        val packages = ExternalWaylandAccess.allowlistCandidates(
            launchable = launchable,
            alreadyAllowed = Settings.externalAllowedPackages,
        )
        if (packages.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.title_external_apps)
                .setMessage(R.string.external_apps_no_launcher)
                .setPositiveButton(R.string.action_close, null)
                .show()
            return
        }
        val labels = packages.map { pkg ->
            runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }.toTypedArray()
        val checked = packages.map { it in Settings.externalAllowedPackages }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.title_external_apps)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.action_close) { _, _ ->
                Settings.externalAllowedPackages = packages
                    .filterIndexed { index, _ -> checked[index] }
                    .toSet()
                refreshAllowlist()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showRendererRestartPrompt(renderer: ServerRenderer) {
        if (renderer == Settings.serverRenderer) return
        Settings.serverRenderer = renderer
        AlertDialog.Builder(this)
            .setTitle(R.string.server_renderer_restart_title)
            .setMessage(getString(R.string.server_renderer_restart_message, renderer.displayName))
            .setPositiveButton(R.string.server_renderer_restart_now) { _, _ ->
                AppRestart.restart(this)
            }
            .setNegativeButton(R.string.server_renderer_restart_later, null)
            .show()
    }

    private fun buildScaleCard(): View {
        val cardPad = (12 * resources.displayMetrics.density).toInt()
        val card = tawcCard()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPad, cardPad, cardPad, cardPad)
            clipToPadding = false
            addView(buildOutputScaleSlider(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        card.addView(column)
        return card
    }

    private fun buildOutputScaleSlider(): View {
        val cardPad = (12 * resources.displayMetrics.density).toInt()
        val steps = ((Settings.MAX_OUTPUT_SCALE - Settings.MIN_OUTPUT_SCALE) /
            Settings.OUTPUT_SCALE_STEP + 0.5f).toInt()
        val current = Settings.outputScale
        val title = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            text = getString(R.string.settings_gui_scale, Settings.formatOutputScale(current))
        }
        val slider = SeekBar(this).apply {
            max = steps
            progress = ((current - Settings.MIN_OUTPUT_SCALE) / Settings.OUTPUT_SCALE_STEP + 0.5f).toInt()
            setPadding(cardPad, cardPad / 2, cardPad, cardPad / 2)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val scale = Settings.snapOutputScale(
                    Settings.MIN_OUTPUT_SCALE + progress * Settings.OUTPUT_SCALE_STEP,
                )
                title.text = getString(R.string.settings_gui_scale, Settings.formatOutputScale(scale))
                if (fromUser) {
                    Settings.outputScale = scale
                    NativeBridge.nativeSetOutputScale(scale)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun buildDiagnosticsSettings(): View {
        val cardPad = (12 * resources.displayMetrics.density).toInt()
        return CheckBox(this).apply {
            text = getString(R.string.settings_tint_buffers_by_type)
            textSize = 15f
            isChecked = Settings.tintBuffersByType
            setPadding(0, cardPad / 2, 0, cardPad / 2)
            setOnCheckedChangeListener { _, checked ->
                Settings.tintBuffersByType = checked
                NativeBridge.nativeSetTintBuffersByType(checked)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_NOTIFICATIONS)
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1
    }
}
