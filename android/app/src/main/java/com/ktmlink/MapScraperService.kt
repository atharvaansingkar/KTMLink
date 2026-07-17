package com.ktmlink

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.io.ByteArrayOutputStream

class MapScraperService : NotificationListenerService() {

    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        const val ACTION_MAPS_UPDATE = "com.ktmlink.MAPS_UPDATE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ICON_BASE64 = "icon_base64"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            if (it.packageName == MAPS_PACKAGE) {
                val extras = it.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                
                var base64Icon = ""
                // Attempt to extract turn arrow from EXTRA_LARGE_ICON if it exists
                val largeIcon = extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Icon
                if (largeIcon != null) {
                    base64Icon = getBase64FromIcon(largeIcon)
                }

                val intent = Intent(ACTION_MAPS_UPDATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                    putExtra(EXTRA_ICON_BASE64, base64Icon)
                }
                sendBroadcast(intent)
            }
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    private fun getBase64FromIcon(icon: Icon): String {
        return try {
            val drawable = icon.loadDrawable(this) ?: return ""
            val bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth.takeIf { it > 0 } ?: 1,
                drawable.intrinsicHeight.takeIf { it > 0 } ?: 1,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }
}
