package com.avshy.bayitvoice.assist

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.avshy.bayitvoice.service.SessionLauncher

/*
 * The system lists an app as a "digital assistant" (side key, long-press home) only when it
 * provides a VoiceInteractionService. These classes do the minimum: when the assistant is
 * invoked they open the normal session UI by class name, and hold no UI themselves.
 */

/** Bound by the system while the app is the default assistant; does nothing on its own. */
class BayitVoiceInteractionService : VoiceInteractionService() {

    /** The side key pressed on the lock screen: open the session over it. */
    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(SessionLauncher.assistIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class BayitSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = Session(this)

    private class Session(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            startAssistantActivity(SessionLauncher.assistIntent(context))
            hide()
        }
    }
}

/** Required by the voice-interaction declaration. Speech goes to Home Assistant, so it offers none. */
class BayitRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
