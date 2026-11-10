package com.dannycom888.mylrcauto.media

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Declared purely for the permission it carries.
 *
 * `MediaSessionManager.getActiveSessions()` — the only way to read another
 * app's playhead — takes the [ComponentName] of an *enabled notification
 * listener as proof that the user opted in. So we register one, and it does
 * nothing with notifications at all.
 *
 * No idle stand-down in v1: once bound, the listener stays bound, so the app
 * keeps seeing what is playing even with nothing on screen. If the system
 * unbinds it, [notifyDisconnected] asks for the binding back after a short
 * delay.
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        component = ComponentName(this, MediaNotificationListener::class.java)
        notifyConnected()
    }

    override fun onListenerDisconnected() {
        notifyDisconnected()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        // Disconnected without the callback (or never connected): do not
        // leave observers believing the listener is still bound. No rebind
        // is scheduled here — the disconnect path already handles that.
        _connected.value = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Intentionally empty: notifications are never read.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally empty.
    }

    companion object {
        private const val REBIND_DELAY_MS = 3_000L

        private val _connected = MutableStateFlow(false)

        /** True while the system has our listener service bound. */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** The component to rebind, remembered from the last connection. */
        @Volatile
        private var component: ComponentName? = null

        /** Process-lifetime scope: the rebind delay must survive the service going away. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private var rebindJob: Job? = null

        fun notifyConnected() {
            rebindJob?.cancel()
            rebindJob = null
            _connected.value = true
        }

        fun notifyDisconnected() {
            _connected.value = false
            rebindJob?.cancel()
            rebindJob = scope.launch {
                delay(REBIND_DELAY_MS)
                if (_connected.value) return@launch
                val target = component ?: return@launch
                runCatching { requestRebind(target) }
            }
        }
    }
}
