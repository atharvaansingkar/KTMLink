package com.ktmlink

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class BondedKtmModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "BondedKtmModule"
    }

    @ReactMethod
    fun getPairedDevices(promise: Promise) {
        try {
            val bluetoothManager = reactApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

            if (bluetoothAdapter == null) {
                promise.reject("ERR_NO_BLUETOOTH", "Bluetooth is not supported on this device.")
                return
            }

            val pairedDevices = bluetoothAdapter.bondedDevices
            val writableArray = Arguments.createArray()

            if (pairedDevices != null) {
                for (device in pairedDevices) {
                    val map = Arguments.createMap()
                    map.putString("name", device.name ?: "Unknown")
                    map.putString("id", device.address)
                    writableArray.pushMap(map)
                }
            }
            
            promise.resolve(writableArray)
        } catch (e: SecurityException) {
            promise.reject("ERR_SECURITY", "Missing BLUETOOTH_CONNECT permission", e)
        } catch (e: Exception) {
            promise.reject("ERR_UNKNOWN", "An error occurred fetching bonded devices", e)
        }
    }
}
