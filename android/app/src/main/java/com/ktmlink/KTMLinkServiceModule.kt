package com.ktmlink

import android.content.Intent
import android.os.Build
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.lang.ref.WeakReference

class KTMLinkServiceModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "KTMLinkService"
        private var ctxRef: WeakReference<ReactApplicationContext>? = null

        /**
         * Called from KTMLinkForegroundService to push handshake/status events to JS.
         * type values: CONNECTING, DEVICE_FOUND, NONCES_SWAPPED, HELLO_EXCHANGED,
         *              KEYS_GENERATED, AUTHENTICATED, DISCONNECTED
         */
        fun emitEvent(type: String, name: String? = null) {
            val ctx = ctxRef?.get() ?: return
            try {
                val map = Arguments.createMap().apply {
                    putString("type", type)
                    name?.let { putString("name", it) }
                }
                ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onKtmEvent", map)
            } catch (e: Exception) {
                Log.w(TAG, "emitEvent failed: ${e.message}")
            }
        }

        /**
         * Emits a timestamped log line to JS for the in-app debug log panel.
         * Filtered to key handshake events only — not general BLE noise.
         */
        fun emitLog(msg: String) {
            val ctx = ctxRef?.get() ?: return
            try {
                val map = Arguments.createMap().apply {
                    putString("type", "LOG")
                    putString("msg", msg)
                }
                ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onKtmEvent", map)
            } catch (e: Exception) {
                Log.w(TAG, "emitLog failed: ${e.message}")
            }
        }

        /**
         * Emits a mirrored notification event to JS so the app can display it in-session.
         * sender: display name (caller name / chat name)
         * body: message text (empty for calls)
         * notifType: "call" | "message"
         */
        fun emitNotification(sender: String, body: String, notifType: String) {
            val ctx = ctxRef?.get() ?: return
            try {
                val map = Arguments.createMap().apply {
                    putString("type", "NOTIF")
                    putString("sender", sender)
                    putString("body", body)
                    putString("notifType", notifType)
                }
                ctx.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onKtmEvent", map)
            } catch (e: Exception) {
                Log.w(TAG, "emitNotification failed: ${e.message}")
            }
        }
    }

    init {
        ctxRef = WeakReference(reactContext)
    }

    override fun getName(): String = "KTMLinkService"

    @ReactMethod
    fun startService() {
        val intent = Intent(reactContext, KTMLinkForegroundService::class.java).apply {
            action = KTMLinkForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }
    }

    @ReactMethod
    fun stopService() {
        val intent = Intent(reactContext, KTMLinkForegroundService::class.java).apply {
            action = KTMLinkForegroundService.ACTION_STOP
        }
        reactContext.startService(intent)
    }

    @ReactMethod
    fun connectDirectly() {
        val intent = Intent(reactContext, KTMLinkForegroundService::class.java).apply {
            action = KTMLinkForegroundService.ACTION_CONNECT_DIRECTLY
        }
        reactContext.startService(intent)
    }

    // Required stubs for NativeEventEmitter on the JS side
    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}
}
