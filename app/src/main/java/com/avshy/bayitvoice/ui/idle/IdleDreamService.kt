package com.avshy.bayitvoice.ui.idle

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.dreams.DreamService
import com.avshy.bayitvoice.service.IdleVisibility
import com.avshy.bayitvoice.config.AppSettings
import com.avshy.bayitvoice.config.AssistLink
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The idle screen as the system screen saver (SPEC section 11), shown while the device charges
 * or is docked once the user picks the app under Settings › Display › Screen saver.
 * A touch ends it, like any screen saver.
 */
class IdleDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {

    // A DreamService is not a ComponentActivity, so Compose needs these owners provided by hand.
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val window = window ?: return
        IdleWindow.hideSystemBars(window)
        val brightness = IdleBrightness(this, window, lifecycleScope)
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { IdleScreen(onNightChange = brightness::apply) }
        }
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        IdleVisibility.onShown()
    }

    override fun onDreamingStopped() {
        IdleVisibility.onHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    companion object {
        /**
         * The idle screen belongs to satellite mode only: in direct mode the screen saver is
         * switched off, so the system does not even list it.
         */
        fun syncAvailability(context: Context) {
            val wanted = if (AppSettings.get(context).assistLink == AssistLink.Satellite) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            val component = ComponentName(context, IdleDreamService::class.java)
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(component) != wanted) {
                pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
