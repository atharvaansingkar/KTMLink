package com.ktmlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import android.content.ComponentName
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule

class MapScraperModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MapScraperService.ACTION_MAPS_UPDATE) {
                val title = intent.getStringExtra(MapScraperService.EXTRA_TITLE) ?: ""
                val text = intent.getStringExtra(MapScraperService.EXTRA_TEXT) ?: ""
                val iconBase64 = intent.getStringExtra(MapScraperService.EXTRA_ICON_BASE64) ?: ""

                val params = Arguments.createMap().apply {
                    putString("title", title)
                    putString("text", text)
                    putString("iconBase64", iconBase64)
                }

                reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onMapUpdate", params)
            }
        }
    }

    init {
        val filter = IntentFilter(MapScraperService.ACTION_MAPS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reactContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            reactContext.registerReceiver(receiver, filter)
        }
    }

    override fun getName(): String {
        return "MapScraper"
    }

    @ReactMethod
    fun hasPermission(promise: Promise) {
        val cn = ComponentName(reactContext, MapScraperService::class.java)
        val flat = Settings.Secure.getString(reactContext.contentResolver, "enabled_notification_listeners")
        val hasPermission = flat != null && flat.contains(cn.flattenToString())
        promise.resolve(hasPermission)
    }

    @ReactMethod
    fun requestPermission() {
        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
    }

    // Required for RN built-in Event Emitter
    @ReactMethod
    fun addListener(eventName: String) {
        // Set up any upstream listeners or background tasks as necessary
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Remove upstream listeners, stop unnecessary background tasks
    }
}
