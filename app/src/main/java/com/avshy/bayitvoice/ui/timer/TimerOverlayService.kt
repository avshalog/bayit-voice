package com.avshy.bayitvoice.ui.timer

import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.avshy.bayitvoice.R
import com.avshy.bayitvoice.timer.ActiveTimer
import com.avshy.bayitvoice.timer.TimerAlarms
import com.avshy.bayitvoice.service.IdleVisibility
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The small "timer running" chip at the bottom of the screen, over any app (the clock
 * screen saver included). Runs only while there are timers; tap it for the timer screen.
 */
class TimerOverlayService : Service() {

    private val scope = MainScope()
    private var watching: Job? = null
    private var ticking: Job? = null
    private var chip: Chip? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watching == null) watching = scope.launch {
            // The idle screen shows its own timer chip; this one steps aside while it is in front.
            combine(TimerAlarms.get(this@TimerOverlayService).timers, IdleVisibility.showing) { timers, idle -> timers to idle }
                .collect { (timers, idle) -> render(timers, idle) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        chip?.remove()
        scope.cancel()
        super.onDestroy()
    }

    private fun render(timers: List<ActiveTimer>, idleShowing: Boolean) {
        if (timers.isEmpty()) {
            stopSelf()
            return
        }
        // A ringing timer has its own screen; the chip shows the next one still running.
        val running = timers.filterNot { it.ringing }.sortedBy { it.leftMs() }
        if (running.isEmpty() || idleShowing) {
            chip?.remove()
            chip = null
            ticking?.cancel()
            return
        }
        val view = chip ?: Chip().also { chip = it }
        view.show(running)
        ticking?.cancel()
        ticking = scope.launch {
            while (true) {
                delay(1_000L - System.currentTimeMillis() % 1_000L)
                view.show(running)
            }
        }
    }

    private inner class Chip {
        private val windows = getSystemService(WindowManager::class.java)
        private val name = text(13f, TimerColors.TEXT)
        private val time = text(15f, TimerColors.TEXT).apply { typeface = Typeface.DEFAULT_BOLD }
        private val more = text(13f, TimerColors.MUTED)
        private val root = LinearLayout(this@TimerOverlayService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(TimerColors.CHIP)
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_timer)
                imageTintList = ColorStateList.valueOf(TimerColors.ACCENT)
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            listOf(name, time, more).forEach {
                addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
            }
            setOnClickListener {
                startActivity(Intent(context, TimerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        private var added = false

        fun show(running: List<ActiveTimer>) {
            val first = running.first()
            name.text = TimerText.name(resources, first)
            time.text = TimerText.countdown(first.leftMs())
            more.visibility = if (running.size > 1) View.VISIBLE else View.GONE
            more.text = getString(R.string.timer_more, running.size - 1)
            if (added) return
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(48)
            }
            windows.addView(root, params)
            added = true
        }

        fun remove() {
            if (added) runCatching { windows.removeView(root) }
            added = false
        }

        private fun text(sizeSp: Float, color: Int) = TextView(this@TimerOverlayService).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
