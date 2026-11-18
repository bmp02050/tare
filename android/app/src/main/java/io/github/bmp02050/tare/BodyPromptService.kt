package io.github.bmp02050.tare

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * The watch app (wear/) messages here when your heart rate is well above your resting level
 * while you're still. We log it as a "prompt" with label "body" (random ones have no label)
 * and post the same neutral check-in, so the two kinds can be compared later.
 */
class BodyPromptService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/tare/body") return
        val payload = String(event.data)
        if (payload == "test") {
            Checkins.notify(this, null)
            return
        }
        val id = NoticeLog(this).append("prompt", label = "body", note = payload)
        Checkins.notify(this, id)
    }
}
