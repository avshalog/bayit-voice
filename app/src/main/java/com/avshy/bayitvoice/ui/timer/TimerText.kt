package com.avshy.bayitvoice.ui.timer

import android.content.res.Resources
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.timer.ActiveTimer

/** Text for timers, shared by the chip and the timer screen. */
internal object TimerText {

    /** Countdown as `m:ss` or `h:mm:ss`, isolated left-to-right so it reads correctly in RTL text. */
    fun countdown(leftMs: Long): String {
        val total = (leftMs + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        return "⁦$text⁩"
    }

    fun name(res: Resources, timer: ActiveTimer): String =
        timer.name.ifBlank { res.getString(R.string.timer_default_name) }

    /** "10 minutes": the largest whole unit, else seconds. */
    fun duration(res: Resources, seconds: Int): String = when {
        seconds >= 3600 && seconds % 3600 == 0 -> res.getQuantityString(R.plurals.duration_hours, seconds / 3600, seconds / 3600)
        seconds >= 60 && seconds % 60 == 0 -> res.getQuantityString(R.plurals.duration_minutes, seconds / 60, seconds / 60)
        else -> res.getQuantityString(R.plurals.duration_seconds, seconds, seconds)
    }
}

/** The fixed dark palette of the conversation screens. */
internal object TimerColors {
    const val BACKGROUND = 0xFF0B0C10.toInt()
    const val CHIP = 0xFF1E2027.toInt()
    const val TEXT = 0xFFECEDF0.toInt()
    const val TITLE = 0xFFF5F6F8.toInt()
    const val MUTED = 0xFF9EA3AE.toInt()
    const val ACCENT = 0xFF7FA6FF.toInt()
    const val ON_ACCENT = 0xFF0E1013.toInt()
}
