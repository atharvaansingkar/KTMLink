package com.ktmlink

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Receives BLE scan results delivered via PendingIntent — works even when the
 * app process is fully dead. Android calls this receiver directly when a BLE
 * advertisement matching our filter is seen, so we can wake the foreground
 * service without needing WorkManager or a long-lived process.
 *
 * Registration: this receiver is declared in AndroidManifest.xml (exported=true)
 * so the system can deliver intents to it. The actual scan is started by
 * scheduleBackgroundBleScan() below, which is called from:
 *   - KTMLinkForegroundService.onDestroy() (service killed → arm background watch)
 *   - KtmAclReceiver (boot completed, but no bonded MAC yet found)
 */
class KtmBleScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (errorCode != -1) {
            Log.w(TAG, "Background scan error: $errorCode")
            return
        }

        val results: List<ScanResult>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        }

        val ktmResult = results?.firstOrNull { it.device?.name?.contains("KTM") == true }
            ?: return

        Log.d(TAG, "Background scan found KTM: ${ktmResult.device.name} (${ktmResult.device.address}) — starting service")

        val svc = Intent(context, KTMLinkForegroundService::class.java).apply {
            action = KTMLinkForegroundService.ACTION_START
            putExtra("EXTRA_DEVICE_ADDRESS", ktmResult.device.address)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(svc)
        else
            context.startService(svc)
    }

    companion object {
        private const val TAG = "KtmBleScanReceiver"
        private const val ACTION_SCAN_RESULT = "com.ktmlink.ACTION_BLE_SCAN_RESULT"

        /**
         * Arms a background BLE scan via PendingIntent. Fires KtmBleScanReceiver
         * when a KTM advertisement is seen, even if the app is fully killed.
         *
         * If the saved MAC is known, scans for that exact address — this is the fast
         * path. If not, no filter is set and the receiver checks the device name.
         *
         * Android limits background scan to SCAN_MODE_LOW_POWER; that is fine here
         * because we only need to wake once, not stream results.
         *
         * Safe to call multiple times — existing pending intents with the same
         * requestCode are replaced (FLAG_UPDATE_CURRENT).
         */
        fun scheduleBackgroundBleScan(context: Context, savedMac: String? = null) {
            val hasScan = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasScan) {
                Log.w(TAG, "scheduleBackgroundBleScan: BLUETOOTH_SCAN permission missing")
                return
            }

            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val scanner = manager?.adapter?.bluetoothLeScanner
            if (scanner == null) {
                Log.w(TAG, "scheduleBackgroundBleScan: scanner unavailable (BT off?)")
                return
            }

            val filters = if (savedMac != null) {
                listOf(ScanFilter.Builder().setDeviceAddress(savedMac).build())
            } else {
                emptyList()
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                .build()

            val receiverIntent = Intent(context, KtmBleScanReceiver::class.java).apply {
                action = ACTION_SCAN_RESULT
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            else
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val pi = android.app.PendingIntent.getBroadcast(context, 0, receiverIntent, flags)

            try {
                scanner.startScan(filters, settings, pi)
                Log.d(TAG, "Background BLE scan armed (target=${savedMac ?: "any KTM by name"})")
            } catch (e: Exception) {
                Log.w(TAG, "scheduleBackgroundBleScan: startScan threw: ${e.message}")
            }
        }

        /**
         * Cancels any pending background BLE scan PendingIntent.
         * Call from the service when it successfully connects — no need to keep
         * the background scan running once we have a live GATT.
         */
        fun cancelBackgroundBleScan(context: Context) {
            val hasScan = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasScan) return

            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val scanner = manager?.adapter?.bluetoothLeScanner ?: return

            val receiverIntent = Intent(context, KtmBleScanReceiver::class.java).apply {
                action = ACTION_SCAN_RESULT
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            else
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val pi = android.app.PendingIntent.getBroadcast(context, 0, receiverIntent, flags)

            try {
                scanner.stopScan(pi)
                Log.d(TAG, "Background BLE scan cancelled")
            } catch (e: Exception) {
                Log.w(TAG, "cancelBackgroundBleScan: stopScan threw: ${e.message}")
            }
        }
    }
}
