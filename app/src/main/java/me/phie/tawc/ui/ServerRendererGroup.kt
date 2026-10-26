package me.phie.tawc.ui

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.text.inSpans
import me.phie.tawc.R
import me.phie.tawc.ServerRenderer

/**
 * Compositor renderer picker. Shown only when [me.phie.tawc.MesaServerSupport]
 * says the build/device can use Mesa; the caller hides the whole control
 * otherwise.
 */
fun Context.serverRendererGroup(
    current: ServerRenderer,
    onPick: (ServerRenderer) -> Unit,
): View {
    val density = resources.displayMetrics.density
    val rowPad = (6 * density).toInt()
    val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }

    for (renderer in ServerRenderer.entries) {
        val detail = when (renderer) {
            ServerRenderer.VENDOR_GLES -> R.string.server_renderer_vendor_detail
            ServerRenderer.MESA_GLES -> R.string.server_renderer_mesa_detail
        }
        group.addView(
            RadioButton(this).apply {
                // Ids are ordinal+1; RadioGroup uses 0 for "nothing checked".
                id = renderer.ordinal + 1
                text = SpannableStringBuilder()
                    .append(renderer.displayName)
                    .append('\n')
                    .inSpans(RelativeSizeSpan(0.85f)) { append(getString(detail)) }
                textSize = 15f
                isChecked = renderer == current
                setPadding(0, rowPad, 0, rowPad)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    group.setOnCheckedChangeListener { _, checkedId ->
        ServerRenderer.entries.firstOrNull { it.ordinal + 1 == checkedId }?.let(onPick)
    }

    val note = TextView(this).apply {
        text = getString(R.string.server_renderer_restart)
        textSize = 13f
        alpha = 0.7f
        setPadding(0, rowPad, 0, 0)
    }

    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(note, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
}
