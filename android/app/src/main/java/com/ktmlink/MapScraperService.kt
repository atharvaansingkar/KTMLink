package com.ktmlink

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.widget.RemoteViews
import java.io.ByteArrayOutputStream

class MapScraperService : NotificationListenerService() {

    companion object {
        const val MAPS_PACKAGE = "com.google.android.apps.maps"
        const val ACTION_MAPS_UPDATE   = "com.ktmlink.MAPS_UPDATE"
        const val ACTION_MAPS_REMOVED  = "com.ktmlink.MAPS_REMOVED"
        const val ACTION_NOTIFICATION         = "com.ktmlink.NOTIFICATION"
        const val ACTION_NOTIFICATION_REMOVED = "com.ktmlink.NOTIFICATION_REMOVED"
        const val EXTRA_TITLE          = "title"
        const val EXTRA_TEXT           = "text"
        const val EXTRA_SUB_TEXT       = "sub_text"
        const val EXTRA_BIG_TEXT       = "big_text"
        const val EXTRA_ICON_BASE64    = "icon_base64"
        const val EXTRA_NOTIF_SENDER   = "notif_sender"
        const val EXTRA_NOTIF_BODY     = "notif_body"
        const val EXTRA_NOTIF_TYPE     = "notif_type"    // "call" | "message"
        const val EXTRA_NOTIF_IS_GROUP = "notif_is_group"
        // Packages we mirror to the dash. Expand this list as needed.
        private val MIRRORED_PACKAGES = setOf(
            "com.whatsapp",               // WhatsApp
            "com.whatsapp.w4b",           // WhatsApp Business
            "com.android.mms",            // AOSP SMS
            "com.google.android.apps.messaging",   // Google Messages
            "com.samsung.android.messaging",       // Samsung Messages
            "com.google.android.dialer", // Google Phone (incoming call)
            "com.android.dialer",        // AOSP dialer
        )

        // Packages whose notifications indicate an incoming call (use PHONE icon on dash)
        private val CALL_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.dialer",
        )
    }

    private val SMS_PACKAGES = setOf(
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            if (it.packageName in MIRRORED_PACKAGES) {
                // Acquire a brief WakeLock for SMS so the CPU stays alive long enough
                // to broadcast even if the screen is off and Android is dozing.
                val wl: PowerManager.WakeLock? = if (it.packageName in SMS_PACKAGES) {
                    val pm = getSystemService(POWER_SERVICE) as? PowerManager
                    pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ktmlink:sms_scrape")
                        ?.also { lock -> lock.acquire(3_000L) }
                } else null
                handleMirroredNotification(it)
                wl?.release()
            }
            if (it.packageName == MAPS_PACKAGE) {
                val extras = it.notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                
                // Extract subText (often contains ETA + remaining distance)
                var subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
                
                // Also check EXTRA_INFO_TEXT and EXTRA_SUMMARY_TEXT just in case Google Maps puts time/distance there
                val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: ""
                val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString() ?: ""
                
                if (infoText.isNotEmpty()) subText += " | $infoText"
                if (summaryText.isNotEmpty()) subText += " | $summaryText"
                
                // Extract bigText (expanded notification — sometimes has more detail)
                var bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
                
                // Google Maps hides the total remaining distance (in meters) inside 'android.progressMax'
                val progressMax = extras.getInt("android.progressMax", -1)
                if (progressMax > 0) {
                    // Convert meters to formatted string (e.g., 7278 -> "7.2 km")
                    // Google Maps seems to floor to the nearest 0.1km rather than round up
                    val distStr = if (progressMax >= 1000) {
                        String.format(java.util.Locale.US, "%.1f km", Math.floor(progressMax / 100.0) / 10.0)
                    } else {
                        "$progressMax m"
                    }
                    subText += " | $distStr"
                }
                
                // Google Maps often uses custom RemoteViews which hides the ETA and remaining distance
                // from the standard extras. We must use reflection to scrape the text out of the RemoteViews.
                val remoteViewsText = extractTextFromRemoteViews(it.notification)
                if (remoteViewsText.isNotEmpty()) {
                    bigText += " | $remoteViewsText"
                }
                
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
                    putExtra(EXTRA_SUB_TEXT, subText)
                    putExtra(EXTRA_BIG_TEXT, bigText)
                    putExtra(EXTRA_ICON_BASE64, base64Icon)
                }
                sendBroadcast(intent)
            }
        }
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let {
            if (it.packageName in MIRRORED_PACKAGES) {
                sendBroadcast(Intent(ACTION_NOTIFICATION_REMOVED).apply { setPackage(packageName) })
            }
            if (it.packageName == MAPS_PACKAGE) {
                // Google Maps navigation notification was removed — this might mean
                // navigation ended, or it might be a transient remove+repost (which
                // Maps does routinely). The JS side debounces this with a 4s delay.
                val intent = Intent(ACTION_MAPS_REMOVED).apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }
    }

    private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    private fun handleMirroredNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text  = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()  ?: ""

        // Skip empty or purely system/group summary notifications
        if (title.isEmpty() && text.isEmpty()) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val isCall = sbn.packageName in CALL_PACKAGES
        val type   = if (isCall) "call" else "message"

        // WhatsApp group messages: text starts with "SenderName: actual message".
        // Detect by checking if the notification has a conversationTitle or if the
        // text contains a colon before much content (WA group format).
        val conversationTitle = extras.getCharSequence("android.conversationTitle")?.toString()?.trim()
        val isGroup = when {
            sbn.packageName !in WHATSAPP_PACKAGES -> false
            conversationTitle != null && conversationTitle != title -> true
            // Fallback: text starts with "Name: " pattern and sender looks like a group name
            else -> text.matches(Regex("^[^:]{1,25}: .+"))
        }

        val sender = title.ifEmpty { sbn.packageName }
        val body   = if (isCall) "" else text

        val intent = Intent(ACTION_NOTIFICATION).apply {
            setPackage(packageName)
            putExtra(EXTRA_NOTIF_SENDER,   sender)
            putExtra(EXTRA_NOTIF_BODY,     body)
            putExtra(EXTRA_NOTIF_TYPE,     type)
            putExtra(EXTRA_NOTIF_IS_GROUP, isGroup)
        }
        sendBroadcast(intent)
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

    private fun extractTextFromRemoteViews(notification: Notification): String {
        val viewsToScrape = listOfNotNull(
            notification.contentView,
            notification.bigContentView,
            notification.headsUpContentView
        )

        val extractedTexts = mutableSetOf<String>()

        for (views in viewsToScrape) {
            try {
                val clazz = Class.forName("android.widget.RemoteViews")
                val actionsField = clazz.getDeclaredField("mActions")
                actionsField.isAccessible = true
                
                val actions = actionsField.get(views) as? Iterable<*> ?: continue
                
                for (action in actions) {
                    if (action == null) continue
                    val actionClass = action.javaClass
                    
                    // We are looking for ReflectionAction which calls setText
                    if (actionClass.name.contains("ReflectionAction")) {
                        val methodNameField = actionClass.getDeclaredField("methodName")
                        methodNameField.isAccessible = true
                        val methodName = methodNameField.get(action) as? String
                        
                        if (methodName == "setText") {
                            val valueField = actionClass.getDeclaredField("value")
                            valueField.isAccessible = true
                            val value = valueField.get(action)
                            if (value is CharSequence) {
                                val text = value.toString().trim()
                                if (text.isNotEmpty()) {
                                    extractedTexts.add(text)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore reflection errors for individual views
            }
        }
        return extractedTexts.joinToString(" | ")
    }
}
