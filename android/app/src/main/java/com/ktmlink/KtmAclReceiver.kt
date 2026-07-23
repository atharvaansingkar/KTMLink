package com.ktmlink

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts KTMLinkForegroundService automatically whenever:
 * - The KTM bike's BT MAC connects at OS level (ACL_CONNECTED)
 * - The Bluetooth adapter is turned on (covers bike-already-near + BT toggle)
 * - The device boots (covers "phone rebooted while bike is on")
 *
 * Does NOT stop the service on ACL_DISCONNECTED — the service owns its own
 * reconnect loop and tearing it down on every brief disconnect prevented
 * auto-reconnect from working (reference: BccuConnectionService.kt AutoConnectReceiver).
 */
class KtmAclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device?.name?.contains("KTM") == true) {
                    Log.d("KtmAclReceiver", "KTM ACL connected (${device.name}) — starting service")
                    startService(context, device.address)
                }
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.d("KtmAclReceiver", "Bluetooth turned ON — starting service to scan for KTM")
                    startService(context)
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d("KtmAclReceiver", "Boot completed — starting service")
                startService(context)
            }
        }
    }

    private fun startService(context: Context, deviceAddress: String? = null) {
        val svc = Intent(context, KTMLinkForegroundService::class.java).apply {
            action = KTMLinkForegroundService.ACTION_START
            if (deviceAddress != null) {
                putExtra("EXTRA_DEVICE_ADDRESS", deviceAddress)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(svc)
        else
            context.startService(svc)
    }
}
