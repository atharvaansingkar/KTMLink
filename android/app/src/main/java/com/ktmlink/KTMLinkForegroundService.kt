package com.ktmlink

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

@SuppressLint("MissingPermission")
class KTMLinkForegroundService : Service() {

    companion object {
        private const val TAG = "KTMLinkService"
        private const val NOTIF_CHANNEL_ID = "ktmlink_channel_v2"
        private const val NOTIF_ID = 1
        private const val PREFS_NAME = "KTMLinkPrefs"
        private const val PREFS_SESSION_KEYS = "ktm_session_keys"
        private const val PREFS_HAS_PAIRED = "hasPairedBefore"
        private const val PREFS_DEVICE_MAC = "ktm_device_mac"

        // From BccuConnectionService.kt reference — timing constants
        private const val CONNECT_SETTLE_MS = 10_000L   // dash boot grace — only used when MAC is unknown (scan path)
        private const val RECONNECT_AFTER_DISCONNECT_MS = 1_500L
        private const val RECONNECT_WATCHDOG_MS = 20_000L
        private const val NAV_END_DEBOUNCE_MS = 1_500L  // Maps remove+repost grace (short so welcome is near-instant)

        const val ACTION_START = "com.ktmlink.ACTION_START"
        const val ACTION_STOP  = "com.ktmlink.ACTION_STOP"
        const val ACTION_CONNECT_DIRECTLY = "com.ktmlink.ACTION_CONNECT_DIRECTLY"
        const val ACTION_TEST_WEATHER = "com.ktmlink.ACTION_TEST_WEATHER"
    }

    // ── BLE state ─────────────────────────────────────────────────────────────
    private var bluetoothGatt: BluetoothGatt? = null
    @Volatile private var scanning = false
    private var bleScanCallback: ScanCallback? = null

    // Runnables
    private var settleRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private var handshakeWatchdog: Runnable? = null

    private val gattThread = HandlerThread("GattWorker").also { it.start() }
    private val gattHandler = Handler(gattThread.looper)
    private val mainHandler  = Handler(Looper.getMainLooper())

    // ── Handshake state ───────────────────────────────────────────────────────
    private var m1: ByteArray? = null
    private var m2: ByteArray? = null
    private var tempIv: ByteArray? = null
    private var tempSecret: ByteArray? = null
    private var sessionKeys: List<ByteArray> = emptyList()
    private var activeSessionKey: ByteArray? = null
    // Counts handshake timeouts since last successful auth. On retry, the silent
    // reconnect path is disabled so the bike can fall back to its normal flow.
    private var handshakeRecoveries: Int = 0

    // ── Nav-active flag ───────────────────────────────────────────────────────
    // True while a Google Maps route is live. Set to true the instant the first
    // MAPS_UPDATE is accepted; set to false immediately when MAPS_REMOVED fires
    // (before any debounce delay). All deferred or periodic writes (slot6 ticks,
    // late gattHandler posts) check this flag and abort if it is false.
    @Volatile private var navActive = false

    // ── GATT write queue with coalescing ──────────────────────────────────────
    // coalesceKey non-null = "latest value wins": any still-pending write to the
    // same characteristic is dropped before the new one is added. This prevents
    // stale nav data from piling up behind fresh updates (the Phase 1 BleManager
    // coalescing behavior that made nav feel instant).
    private data class WriteEntry(
        val label: String,
        val coalesceKey: java.util.UUID?,
        val block: () -> Boolean,
        val onDone: (() -> Unit)? = null
    )
    private val writeQueue = ArrayDeque<WriteEntry>()
    private var writeInFlight = false
    private var currentWriteOnDone: (() -> Unit)? = null
    private var currentWriteIsDescriptor = false

    private fun enqueueWrite(
        label: String,
        coalesceKey: java.util.UUID? = null,
        onDone: (() -> Unit)? = null,
        block: () -> Boolean
    ) {
        gattHandler.post {
            if (coalesceKey != null) {
                writeQueue.removeAll { it.coalesceKey == coalesceKey }
            }
            writeQueue.addLast(WriteEntry(label, coalesceKey, block, onDone))
            if (!writeInFlight) drainQueue()
        }
    }

    private fun onWriteComplete(onDone: (() -> Unit)?) {
        gattHandler.post {
            writeInFlight = false
            currentWriteIsDescriptor = false
            onDone?.invoke()
            drainQueue()
        }
    }

    private fun drainQueue() {
        if (writeInFlight || writeQueue.isEmpty()) return
        val entry = writeQueue.removeFirst()
        writeInFlight = true
        Log.d(TAG, "GattWrite: ${entry.label}")
        val initiated = entry.block()
        if (!initiated) {
            Log.w(TAG, "GattWrite FAILED initiation: ${entry.label}")
            writeInFlight = false
            currentWriteOnDone = null
            entry.onDone?.invoke()
            drainQueue()
        } else {
            currentWriteOnDone = entry.onDone
        }
    }

    // Must be called on gattHandler thread (or via post).
    private fun clearWriteQueue() {
        writeQueue.clear()
        writeInFlight = false
        currentWriteOnDone = null
        currentWriteIsDescriptor = false
    }

    // ── GATT callbacks ────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // Discard late callbacks from a GATT client we already recycled.
            // bluetoothGatt is nulled before old?.close() in closeGatt(), so a
            // stale client arrives here with gatt !== bluetoothGatt.
            if (gatt !== bluetoothGatt && newState != BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Ignoring state change from stale GATT client (status=$status newState=$newState)")
                try { gatt.close() } catch (_: Exception) {}
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected — discovering services")
                    emitLog("GATT connected")
                    KTMLinkServiceModule.emitEvent("DEVICE_FOUND", gatt.device?.name ?: "KTM")
                    val isKnown = prefs().getBoolean(PREFS_HAS_PAIRED, false)
                    val budget = if (isKnown) 25000L else 45000L
                    startHandshakeWatchdog(budget)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status)")
                    if (activeSessionKey != null) handshakeRecoveries = 0
                    navEndDebounceHandler?.removeCallbacksAndMessages(null)
                    navEndDebounceHandler = null
                    stopSlot6()
                    closeGatt()
                    KTMLinkServiceModule.emitEvent("DISCONNECTED")
                    mainHandler.post {
                        updateNotification("KTMLink — Searching for KTM...")
                        scheduleReconnectScan()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered — requesting MTU 517")
                gatt.requestMtu(517)
            } else {
                Log.w(TAG, "onServicesDiscovered failed status=$status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== bluetoothGatt) return
            Log.d(TAG, "MTU changed to $mtu (status=$status)")
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            if (gatt.getService(KtmNativeProtocol.MAIN_SERVICE) != null) {
                enableAuthReqIndications(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onDescriptorWrite FAILED uuid=${descriptor.characteristic.uuid} status=$status")
            } else if (descriptor.characteristic.uuid == KtmNativeProtocol.AUTH_REQ) {
                Log.d(TAG, "AUTH_REQ indications enabled — waiting for M1")
            }
            val done = currentWriteOnDone
            currentWriteOnDone = null
            onWriteComplete(done)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (gatt !== bluetoothGatt) return
            if (characteristic.uuid == KtmNativeProtocol.AUTH_REQ) {
                handleAuthPacket(gatt, characteristic.value ?: return)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== bluetoothGatt) return
            if (characteristic.uuid == KtmNativeProtocol.AUTH_REQ) {
                handleAuthPacket(gatt, value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onCharacteristicWrite FAILED uuid=${characteristic.uuid} status=$status")
                emitLog("WRITE FAILED uuid=${characteristic.uuid.toString().takeLast(6)} status=$status")
            }
            val done = currentWriteOnDone
            currentWriteOnDone = null
            onWriteComplete(done)
        }
    }

    // ── BLE connect / scan ────────────────────────────────────────────────────
    // When the MAC is already known (saved or bonded), skip the scan+settle and
    // call connectGattDirect immediately — same as KTMLinkTest behaviour.
    // The 10-second settle is only needed the very first time (MAC unknown, bike
    // found by name scan) to let the dash finish booting.
    private fun startBleScan() {
        if (bluetoothGatt != null) {
            Log.d(TAG, "startBleScan: already have GATT — skipping")
            return
        }

        val hasBtScan = ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val hasBtConnect = ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!hasBtScan || !hasBtConnect) {
            Log.w(TAG, "startBleScan: missing BT permissions")
            return
        }

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "startBleScan: adapter unavailable or off")
            return
        }

        val savedMac = prefs().getString(PREFS_DEVICE_MAC, null)
        val hasConnectPerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val bondedMac = if (hasConnectPerm) {
            adapter.bondedDevices?.firstOrNull { it.name?.contains("KTM") == true }?.address
        } else null
        val knownMac = bondedMac ?: savedMac

        // Fast path: MAC is known — connect directly without scanning or settling
        if (knownMac != null) {
            Log.d(TAG, "startBleScan: known MAC $knownMac — connecting directly (no scan/settle)")
            val device = adapter.getRemoteDevice(knownMac)
            connectGattDirect(device)
            return
        }

        // Slow path: first-ever connection, MAC unknown — scan by name then settle
        if (scanning) return
        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "startBleScan: scanner unavailable")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                if (dev.name?.contains("KTM") != true) return
                Log.d(TAG, "Scan: found KTM ${dev.name} (${dev.address}) — settling ${CONNECT_SETTLE_MS / 1000}s before GATT connect")
                stopBleScan()
                if (bluetoothGatt != null || settleRunnable != null) return
                val r = Runnable {
                    settleRunnable = null
                    if (bluetoothGatt == null) connectGattDirect(dev)
                }
                settleRunnable = r
                mainHandler.postDelayed(r, CONNECT_SETTLE_MS)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed errorCode=$errorCode")
                scanning = false
                bleScanCallback = null
            }
        }

        try {
            scanner.startScan(emptyList(), settings, cb)
            bleScanCallback = cb
            scanning = true
            Log.d(TAG, "BLE scan started (no known MAC — scanning by name)")
        } catch (e: Exception) {
            Log.w(TAG, "startScan threw: ${e.message}")
        }
    }

    private fun stopBleScan() {
        if (!scanning) return
        scanning = false
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        try { bleScanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } } catch (_: Exception) {}
        bleScanCallback = null
    }

    private fun connectGattDirect(device: BluetoothDevice) {
        if (bluetoothGatt != null) return
        Log.d(TAG, "connectGattDirect: ${device.name} (${device.address})")
        prefs().edit().putString(PREFS_DEVICE_MAC, device.address).commit()
        // Cancel background scan — we have a live GATT connection in progress.
        KtmBleScanReceiver.cancelBackgroundBleScan(this)
        KTMLinkServiceModule.emitEvent("CONNECTING", device.name ?: "KTM")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        cancelHandshakeWatchdog()
        val old = bluetoothGatt
        bluetoothGatt = null          // null FIRST so stale callbacks self-discard
        gattHandler.post { clearWriteQueue() }
        resetHandshakeState()
        try { old?.disconnect(); old?.close() } catch (_: Exception) {}
    }

    private fun scheduleReconnectScan() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            reconnectRunnable = null
            startBleScan()
        }
        reconnectRunnable = r
        mainHandler.postDelayed(r, RECONNECT_AFTER_DISCONNECT_MS)
    }

    private fun startHandshakeWatchdog(budgetMs: Long) {
        cancelHandshakeWatchdog()
        val r = Runnable {
            handshakeWatchdog = null
            handshakeRecoveries++
            Log.w(TAG, "Handshake watchdog timeout ($budgetMs ms) attempt=$handshakeRecoveries - forcing disconnect to recover ACL")
            emitLog("ERROR: Timeout ($budgetMs ms) attempt=$handshakeRecoveries - Reconnecting")
            closeGatt()
            scheduleReconnectScan()
        }
        handshakeWatchdog = r
        mainHandler.postDelayed(r, budgetMs)
    }

    private fun cancelHandshakeWatchdog() {
        handshakeWatchdog?.let { mainHandler.removeCallbacks(it) }
        handshakeWatchdog = null
    }

    // ── Adapter state receiver ────────────────────────────────────────────────
    private var adapterStateReceiver: BroadcastReceiver? = null

    private fun registerAdapterStateReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned OFF — forcing disconnect state")
                        cancelPendingRunnables()
                        stopBleScan()
                        closeGatt()
                        stopSlot6()
                        navEndDebounceHandler?.removeCallbacksAndMessages(null)
                        navEndDebounceHandler = null
                        KTMLinkServiceModule.emitEvent("DISCONNECTED")
                        updateNotification("KTMLink — Bluetooth off")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned ON — starting scan")
                        updateNotification("KTMLink — Searching for KTM...")
                        if (bluetoothGatt == null) startBleScan()
                    }
                }
            }
        }
        registerReceiver(r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        adapterStateReceiver = r
    }

    private fun cancelPendingRunnables() {
        settleRunnable?.let { mainHandler.removeCallbacks(it) }; settleRunnable = null
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }; reconnectRunnable = null
        cancelHandshakeWatchdog()
    }

    // ── Handshake helpers ─────────────────────────────────────────────────────

    private fun enableAuthReqIndications(gatt: BluetoothGatt) {
        // Routed through the write queue so onDescriptorWrite arrives with
        // writeInFlight=true and queue accounting stays consistent. A raw
        // gatt.writeDescriptor() outside the queue corrupts writeInFlight state
        // and causes the next queued write to be double-fired.
        enqueueCccd(gatt, KtmNativeProtocol.MAIN_SERVICE, KtmNativeProtocol.AUTH_REQ,
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, "CCCD: AUTH_REQ indication")
    }

    private fun handleAuthPacket(gatt: BluetoothGatt, packet: ByteArray) {
        if (m1 == null && tempIv == null && sessionKeys.isEmpty()) {
            val stored = loadSessionKeys()
            if (stored.isNotEmpty()) {
                sessionKeys = stored
                Log.d(TAG, "KtmHandshake: pre-loaded ${stored.size} keys for reconnect path")
            }
        }

        if (m1 == null) {
            Log.d(TAG, "KtmHandshake: M1 received (${packet.size}B): ${KtmNativeCrypto.hex(packet)}")
            emitLog("M1 received (${packet.size}B)")
            m1 = packet
            val m2Bytes = KtmNativeCrypto.randomNonce()
            m2 = m2Bytes
            val (iv, secret) = KtmNativeCrypto.computeTempIvAndSecret(packet, m2Bytes)
            tempIv = iv; tempSecret = secret

            emitLog("M2 queued")
            writeAuthRep(gatt, m2Bytes, "M2 nonce") { emitLog("M2 write ACK'd") }
            KTMLinkServiceModule.emitEvent("NONCES_SWAPPED")
            return
        }

        val iv     = tempIv     ?: run { Log.e(TAG, "tempIv null on encrypted packet"); return }
        val secret = tempSecret ?: run { Log.e(TAG, "tempSecret null on encrypted packet"); return }

        val decrypted = try {
            KtmNativeCrypto.decryptControl(packet, secret, iv)
        } catch (e: Exception) {
            Log.e(TAG, "decryptControl failed: ${e.message}")
            emitLog("DECRYPT ERROR: ${e.message}")
            return
        }

        val cmd = decrypted[2].toInt() and 0xFF
        Log.d(TAG, "KtmHandshake: cmd=0x${"%02X".format(cmd)}")
        emitLog("cmd=0x${"%02X".format(cmd)}")

        if (cmd > 0x20) {
            Log.e(TAG, "KtmHandshake: UNKNOWN cmd=0x${"%02X".format(cmd)}. Corrupted state or unexpected M1 re-transmission! Forcing disconnect to recover.")
            emitLog("ERROR: UNKNOWN cmd=0x${"%02X".format(cmd)} (Garbage)")
            closeGatt()
            scheduleReconnectScan()
            return
        }

        when {
            cmd == KtmNativeProtocol.CMD_HELLO -> {
                val hasPaired = prefs().getBoolean(PREFS_HAS_PAIRED, false)
                val storedKeys = if (hasPaired) loadSessionKeys() else emptyList()

                if (hasPaired && storedKeys.isNotEmpty() && handshakeRecoveries == 0) {
                    // Known bike + persisted keys + first attempt: silent reconnect path.
                    // Load the key pool so SELECT_KEY can use it immediately, then reply
                    // CMD_GENERATE_KEYS (1) — signals to the bike that we have the keys
                    // and it should skip re-pairing and proceed straight to SELECT_KEY.
                    sessionKeys = storedKeys
                    Log.d(TAG, "KtmHandshake: CMD_HELLO (known bike, ${storedKeys.size} keys) — silent reconnect, replying GENERATE_KEYS")
                    emitLog("CMD_HELLO known bike — silent reconnect (${storedKeys.size} keys)")
                    val reply = KtmNativeCrypto.encryptControl(
                        KtmNativeCrypto.buildControlPacket(KtmNativeProtocol.CMD_GENERATE_KEYS), secret, iv
                    )
                    writeAuthRep(gatt, reply, "HELLO→GENERATE_KEYS (silent reconnect)")
                } else {
                    // New bike, no stored keys, or retry after stall: echo HELLO back.
                    Log.d(TAG, "KtmHandshake: CMD_HELLO (${if (hasPaired) "known/retry#$handshakeRecoveries" else "new bike"}, keys=${storedKeys.size}) — echoing")
                    emitLog("CMD_HELLO ${if (hasPaired) "retry#$handshakeRecoveries" else "new bike"} — echoing")
                    val reply = KtmNativeCrypto.encryptControl(
                        KtmNativeCrypto.buildControlPacket(KtmNativeProtocol.CMD_HELLO), secret, iv
                    )
                    writeAuthRep(gatt, reply, "HELLO echo")
                }
                KTMLinkServiceModule.emitEvent("HELLO_EXCHANGED")
            }

            cmd == KtmNativeProtocol.CMD_GENERATE_KEYS -> {
                Log.d(TAG, "KtmHandshake: CMD_GENERATE_KEYS → deriving 16 keys")
                emitLog("CMD_GENERATE_KEYS — deriving 16 keys")
                val mirrored = KtmNativeCrypto.buildMirrored(decrypted)
                val keys = KtmNativeCrypto.deriveSessionKeys(decrypted, mirrored, iv, secret)
                sessionKeys = keys
                saveSessionKeys(keys)
                Log.d(TAG, "KtmHandshake: ${keys.size} keys saved, key[0]=${KtmNativeCrypto.hex(keys[0])}")
                emitLog("${keys.size} keys saved")

                val reply = KtmNativeCrypto.encryptControl(
                    KtmNativeCrypto.buildControlPacket(0x02), secret, iv
                )
                writeAuthRep(gatt, reply, "KEYS_GENERATED ack")
                KTMLinkServiceModule.emitEvent("KEYS_GENERATED")
            }

            cmd >= KtmNativeProtocol.CMD_KEY_ACK_BASE &&
            cmd <= (KtmNativeProtocol.CMD_KEY_ACK_BASE + 0x0F) -> {
                val keyIndex = cmd - KtmNativeProtocol.CMD_KEY_ACK_BASE
                val alreadyAuthenticated = activeSessionKey != null
                
                Log.d(TAG, "KtmHandshake: CMD_SELECT_KEY index=$keyIndex")
                if (!alreadyAuthenticated) {
                    emitLog("CMD_SELECT_KEY idx=$keyIndex")
                }

                val keys = if (sessionKeys.isNotEmpty()) sessionKeys else loadSessionKeys()
                if (keys.isEmpty() || keyIndex >= keys.size) {
                    Log.e(TAG, "KtmHandshake: no keys for index $keyIndex — keys.size=${keys.size}")
                    emitLog("ERROR: no keys for idx=$keyIndex size=${keys.size}")
                    return
                }
                activeSessionKey = keys[keyIndex]
                sessionKeys = keys
                
                if (!alreadyAuthenticated) {
                    Log.d(TAG, "KtmHandshake: activeSessionKey set to index $keyIndex")
                }

                val reply = KtmNativeCrypto.encryptControl(
                    KtmNativeCrypto.buildControlPacket(cmd), secret, iv
                )
                
                if (alreadyAuthenticated) {
                    Log.d(TAG, "KtmHandshake: SELECT_KEY retransmit idx=$keyIndex — echoing, skipping activate")
                    emitLog("SELECT_KEY retry idx=$keyIndex — echo only")
                    writeAuthRep(gatt, reply, "SELECT_KEY echo (retry)")
                } else {
                    writeAuthRep(gatt, reply, "SELECT_KEY echo") {
                        Log.d(TAG, "KtmHandshake: AUTHENTICATED ✓")
                        emitLog("AUTHENTICATED ✓")
                        cancelHandshakeWatchdog()
                        handshakeRecoveries = 0
                        prefs().edit().putBoolean(PREFS_HAS_PAIRED, true).commit()
                        KTMLinkServiceModule.emitEvent("AUTHENTICATED")
                        mainHandler.post { updateNotification("KTMLink — Connected to ${gatt.device?.name ?: "KTM"}") }
                        activateDashboard(gatt)
                    }
                }
            }

            else -> {
                Log.w(TAG, "KtmHandshake: unknown cmd=0x${"%02X".format(cmd)}")
                emitLog("UNKNOWN cmd=0x${"%02X".format(cmd)} (bug if 0xFF)")
            }
        }
    }

    private fun emitLog(msg: String) {
        KTMLinkServiceModule.emitLog(msg)
    }

    private fun writeAuthRep(gatt: BluetoothGatt, data: ByteArray, label: String, onDone: (() -> Unit)? = null) {
        val svc  = gatt.getService(KtmNativeProtocol.MAIN_SERVICE) ?: run {
            Log.e(TAG, "MAIN_SERVICE not found for AUTH_REP"); return
        }
        val char = svc.getCharacteristic(KtmNativeProtocol.AUTH_REP) ?: run {
            Log.e(TAG, "AUTH_REP char not found"); return
        }
        enqueueWrite("AUTH_REP: $label", coalesceKey = null, onDone = onDone) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                @Suppress("DEPRECATION")
                char.value = data
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }
    }

    private fun writeNavChar(
        gatt: BluetoothGatt,
        charUuid: java.util.UUID,
        payload: ByteArray,
        label: String,
        coalesce: Boolean = true
    ) {
        val key = activeSessionKey ?: run { Log.w(TAG, "writeNavChar: no active key"); return }
        val iv  = tempIv           ?: run { Log.w(TAG, "writeNavChar: no tempIv"); return }
        val encrypted = KtmNativeCrypto.frameAndEncryptData(payload, key, iv)

        val svc  = gatt.getService(KtmNativeProtocol.MAIN_SERVICE) ?: return
        val char = svc.getCharacteristic(charUuid) ?: return

        enqueueWrite(label, coalesceKey = if (coalesce) charUuid else null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, encrypted, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                @Suppress("DEPRECATION")
                char.value = encrypted
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }
    }

    private fun addToQueueDirect(
        gatt: BluetoothGatt,
        charUuid: java.util.UUID,
        payload: ByteArray,
        label: String,
        coalesce: Boolean = true
    ) {
        val key = activeSessionKey ?: return
        val iv  = tempIv           ?: return
        val encrypted = KtmNativeCrypto.frameAndEncryptData(payload, key, iv)
        val svc  = gatt.getService(KtmNativeProtocol.MAIN_SERVICE) ?: return
        val char = svc.getCharacteristic(charUuid) ?: return
        val coalesceKey = if (coalesce) charUuid else null
        if (coalesceKey != null) writeQueue.removeAll { it.coalesceKey == coalesceKey }
        val block: () -> Boolean = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, encrypted, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                @Suppress("DEPRECATION")
                char.value = encrypted
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }
        writeQueue.addLast(WriteEntry(label, coalesceKey, block))
    }

    private fun enqueueCccd(
        gatt: BluetoothGatt,
        serviceUuid: java.util.UUID,
        charUuid: java.util.UUID,
        value: ByteArray,
        label: String
    ) {
        val svc = gatt.getService(serviceUuid) ?: run {
            Log.e(TAG, "$label: Service not found"); return
        }
        val char = svc.getCharacteristic(charUuid) ?: run {
            Log.e(TAG, "$label: Characteristic not found"); return
        }
        val cccd = char.getDescriptor(KtmNativeProtocol.CCCD) ?: run {
            Log.e(TAG, "$label: CCCD not found"); return
        }

        enqueueWrite(label, coalesceKey = null, onDone = null) {
            currentWriteIsDescriptor = true
            val enable = (value contentEquals BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) ||
                         (value contentEquals BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (enable) gatt.setCharacteristicNotification(char, true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value) == 0
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }
    }

    private fun activateDashboard(gatt: BluetoothGatt) {
        enqueueCccd(gatt, KtmNativeProtocol.RCM_SERVICE, KtmNativeProtocol.RCM_REMOTE_CONTROL,
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, "CCCD: RCM_REMOTE_CONTROL indication")
        
        enqueueCccd(gatt, KtmNativeProtocol.MAIN_SERVICE, KtmNativeProtocol.TBT_NAV_REQUEST,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, "CCCD: TBT_NAV_REQUEST notification")

        writeNavChar(gatt, KtmNativeProtocol.NAVIGATION_STATE,
            KtmNativeProtocol.buildNavigationStatePayload(guidanceOn = true, gpsIconOn = true),
            "NavState: guidance ON", coalesce = false)
        showWelcomeScreen(gatt)
    }

    private fun showWelcomeScreen(gatt: BluetoothGatt) {
        gattHandler.post {
            slot6Version++
            slot6Handler?.removeCallbacksAndMessages(null)
            slot6Handler = null; slot6DistText = ""; slot6TimeText = ""

            writeQueue.removeAll {
                it.label.startsWith("Nav:") || it.label.startsWith("Slot6:")
            }

            val off  = KtmNativeProtocol.Visibility.OFF
            val full = KtmNativeProtocol.Visibility.FULL

            addToQueueDirect(gatt, KtmNativeProtocol.TURN_DISTANCE,
                KtmNativeProtocol.buildTurnDistancePayload(" ", off),      "Welcome: clear distance")
            addToQueueDirect(gatt, KtmNativeProtocol.TURN_INFO,
                KtmNativeProtocol.buildTurnInfoPayload(" ", off),          "Welcome: clear info")
            addToQueueDirect(gatt, KtmNativeProtocol.ETA,
                KtmNativeProtocol.buildEtaPayload(" ", off),               "Welcome: clear ETA")
            addToQueueDirect(gatt, KtmNativeProtocol.REMAINING_DISTANCE,
                KtmNativeProtocol.buildRemainingDistancePayload(" ", off), "Welcome: clear remaining")
            addToQueueDirect(gatt, KtmNativeProtocol.TURN_ROAD,
                KtmNativeProtocol.buildTurnRoadPayload("Hello Atharva!", full), "Welcome: greeting")
            addToQueueDirect(gatt, KtmNativeProtocol.TURN_ICON,
                KtmNativeProtocol.buildTurnIconPayload(KtmNativeProtocol.TurnIcon.START, full), "Welcome: START icon")

            Log.d(TAG, "Welcome screen queued atomically (queue depth=${writeQueue.size})")
            if (!writeInFlight) drainQueue()
            mainHandler.post { triggerIdleSlotRefresh(gatt) }
        }
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveSessionKeys(keys: List<ByteArray>) {
        val arr = JSONArray()
        keys.forEach { arr.put(KtmNativeCrypto.hex(it).replace(" ", "")) }
        prefs().edit().putString(PREFS_SESSION_KEYS, arr.toString()).commit()
        Log.d(TAG, "saveSessionKeys: ${keys.size} keys persisted")
    }

    private fun loadSessionKeys(): List<ByteArray> {
        val json = prefs().getString(PREFS_SESSION_KEYS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val hex = arr.getString(i)
                ByteArray(hex.length / 2) { j ->
                    hex.substring(j * 2, j * 2 + 2).toInt(16).toByte()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resetHandshakeState() {
        m1 = null; m2 = null; tempIv = null; tempSecret = null
        activeSessionKey = null
        navActive = false
    }

    private var navEndDebounceHandler: Handler? = null

    @Volatile private var slot6Version = 0
    private var slot6Handler: Handler? = null
    private var slot6DistText = ""
    private var slot6TimeText = ""
    private var slot6ShowingDist = true

    private fun startSlot6Direct(distText: String, timeText: String, gatt: BluetoothGatt) {
        val hasBoth = distText.isNotEmpty() && timeText.isNotEmpty()
        val hadBoth = slot6DistText.isNotEmpty() && slot6TimeText.isNotEmpty()
        val distChanged = distText != slot6DistText
        val timeChanged = timeText != slot6TimeText
        slot6DistText = distText; slot6TimeText = timeText

        if (!hasBoth) {
            slot6Version++
            slot6Handler?.removeCallbacksAndMessages(null); slot6Handler = null
            val single = distText.ifEmpty { timeText }
            if (single.isNotEmpty()) {
                addToQueueDirect(gatt, KtmNativeProtocol.REMAINING_DISTANCE,
                    KtmNativeProtocol.buildRemainingDistancePayload(single, KtmNativeProtocol.Visibility.FULL),
                    "Slot6: static")
            }
            return
        }

        if (slot6Handler != null && !distChanged && !timeChanged) return

        slot6Version++
        val myVersion = slot6Version
        slot6Handler?.removeCallbacksAndMessages(null)

        if (!hadBoth) slot6ShowingDist = true
        val nowText = if (slot6ShowingDist) distText else timeText
        addToQueueDirect(gatt, KtmNativeProtocol.REMAINING_DISTANCE,
            KtmNativeProtocol.buildRemainingDistancePayload(nowText, KtmNativeProtocol.Visibility.FULL),
            "Slot6: immediate")

        val h = Handler(gattThread.looper)
        slot6Handler = h
        val tick = object : Runnable {
            override fun run() {
                if (slot6Version != myVersion || !navActive) return
                val g = bluetoothGatt ?: return
                slot6ShowingDist = !slot6ShowingDist
                val text = if (slot6ShowingDist) slot6DistText else slot6TimeText
                if (text.isNotEmpty()) {
                    addToQueueDirect(g, KtmNativeProtocol.REMAINING_DISTANCE,
                        KtmNativeProtocol.buildRemainingDistancePayload(text, KtmNativeProtocol.Visibility.FULL),
                        "Slot6: alt")
                    if (!writeInFlight) drainQueue()
                }
                h.postDelayed(this, 2000)
            }
        }
        h.postDelayed(tick, 2000)
    }

    private fun stopSlot6() {
        slot6Version++
        slot6Handler?.removeCallbacksAndMessages(null)
        slot6Handler = null; slot6DistText = ""; slot6TimeText = ""
    }

    // ── Idle weather refresh ──────────────────────────────────────────────────
    @Volatile private var idleFetchVersion = 0
    private var weatherRefreshRunnable: Runnable? = null

    private fun triggerIdleSlotRefresh(gatt: BluetoothGatt) {
        val myVersion = ++idleFetchVersion
        weatherRefreshRunnable?.let { mainHandler.removeCallbacks(it) }
        weatherRefreshRunnable = null

        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)

        if (loc == null) {
            Log.d(TAG, "Idle weather: no last location — skipping")
            return
        }

        KtmIdleWeatherFetcher.fetch(loc.latitude, loc.longitude, onResult = { data: IdleWeatherData? ->
            if (data == null || idleFetchVersion != myVersion || navActive) return@fetch
            val g = bluetoothGatt ?: return@fetch
            val full = KtmNativeProtocol.Visibility.FULL
            writeNavChar(g, KtmNativeProtocol.TURN_DISTANCE,
                KtmNativeProtocol.buildTurnDistancePayload("${data.tempC}°C", full), "Idle: temp")
            writeNavChar(g, KtmNativeProtocol.TURN_INFO,
                KtmNativeProtocol.buildTurnInfoPayload(data.condition, full), "Idle: condition")
            writeNavChar(g, KtmNativeProtocol.REMAINING_DISTANCE,
                KtmNativeProtocol.buildRemainingDistancePayload("AQI ${data.usAqi}", full), "Idle: aqi")
            Log.d(TAG, "Idle weather written: ${data.tempC}°C / ${data.condition} / AQI ${data.usAqi}")
            KTMLinkServiceModule.emitWeather(data.tempC, data.condition, data.usAqi)
        })

        // Refresh every 5 minutes while connected and idle
        val r = object : Runnable {
            override fun run() {
                val g = bluetoothGatt ?: return
                if (navActive) { mainHandler.postDelayed(this, 5 * 60 * 1000L); return }
                triggerIdleSlotRefresh(g)
            }
        }
        weatherRefreshRunnable = r
        mainHandler.postDelayed(r, 5 * 60 * 1000L)
    }

    private fun streamLiveNavigation(gatt: BluetoothGatt, parsed: KtmNativeNavParser.ParsedNavData) {
        gattHandler.post {
            if (!navActive) return@post
            if (notifTakeover) return@post  // Notification owns the dash for a few seconds; skip nav writes
            writeQueue.removeAll { it.label.startsWith("Welcome:") }

            val vis = KtmNativeProtocol.Visibility.FULL
            val off = KtmNativeProtocol.Visibility.OFF

            addToQueueDirect(gatt, KtmNativeProtocol.TURN_ICON,
                KtmNativeProtocol.buildTurnIconPayload(parsed.turnIcon, vis), "Nav: icon")

            val distText = parsed.distance.trim()
            addToQueueDirect(gatt, KtmNativeProtocol.TURN_DISTANCE,
                KtmNativeProtocol.buildTurnDistancePayload(distText, if (distText.isNotEmpty()) vis else off),
                "Nav: distance")

            val infoText = parsed.maneuver.trim().let { if (it.isNotEmpty()) "· $it" else "" }
            addToQueueDirect(gatt, KtmNativeProtocol.TURN_INFO,
                KtmNativeProtocol.buildTurnInfoPayload(infoText, if (infoText.isNotEmpty()) vis else off),
                "Nav: info")

            val roadText = parsed.road.trim()
            addToQueueDirect(gatt, KtmNativeProtocol.TURN_ROAD,
                KtmNativeProtocol.buildTurnRoadPayload(roadText, if (roadText.isNotEmpty()) vis else off),
                "Nav: road")

            val etaText = parsed.eta.trim()
            addToQueueDirect(gatt, KtmNativeProtocol.ETA,
                KtmNativeProtocol.buildEtaPayload(etaText, if (etaText.isNotEmpty()) vis else off),
                "Nav: ETA")

            startSlot6Direct(parsed.remainingDistance.trim(), parsed.timeRemaining.trim(), gatt)

            if (!writeInFlight) drainQueue()
        }
    }

    // ── Phone/WhatsApp notification mirroring ─────────────────────────────────

    // Timing constants
    private val NOTIF_WELCOME_MS = 10_000L  // welcome screen: show for 10s then restore welcome
    private val NOTIF_NAV_MS     =  5_000L  // nav active: show for 5s then clear
    private val NOTIF_CHUNK_MS   =  1_500L  // time per 2-word chunk

    // When true, streamLiveNavigation() skips writes so the notification owns the dash.
    @Volatile private var notifTakeover = false

    private var notifClearRunnable:  Runnable? = null
    private var notifTickerRunnable: Runnable? = null

    // Truncate whole words to 15 max, drop the rest silently.
    private fun wordLimit15(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.take(15).joinToString(" ")
    }

    // prefixFrames: ordered header frames shown before the body (e.g. ["[SASA]", "Anushka:"])
    // body: full message text (words, up to 15-word limit)
    private data class NotifParts(val prefixFrames: List<String>, val body: String)

    private fun parseNotif(sender: String, body: String, type: String, isGroup: Boolean): NotifParts {
        if (type == "call") return NotifParts(listOf("Call: ${sender.take(13)}".take(15)), "")

        val MEDIA_PATTERNS = listOf(
            "image" to "Photo", "photo" to "Photo", "video" to "Video",
            "audio" to "Audio", "document" to "Doc", "sticker" to "Sticker",
            "gif" to "GIF", "contact card" to "Contact",
            "voice message" to "Voice", "missed" to "Missed"
        )
        val bodyLower = body.trim().lowercase()
        val isMedia = bodyLower.isEmpty() || MEDIA_PATTERNS.any { (pat, _) -> bodyLower.contains(pat) }
        if (isMedia) {
            val label = MEDIA_PATTERNS.firstOrNull { (pat, _) -> bodyLower.contains(pat) }?.second ?: "Media"
            return NotifParts(listOf("${sender.take(8)}: $label".take(15)), "")
        }

        return if (isGroup) {
            // body = "SenderName: actual message"
            val colonIdx = body.indexOf(':')
            val (msgSender, msgBody) = if (colonIdx > 0) {
                body.substring(0, colonIdx).trim() to body.substring(colonIdx + 1).trim()
            } else {
                sender to body
            }
            // Three header frames: group name, then sender name, then body chunks
            NotifParts(
                listOf("[${sender.take(13)}]".take(15), "${msgSender.take(14)}:".take(15)),
                wordLimit15(msgBody)
            )
        } else {
            NotifParts(listOf("${sender.take(14)}:".take(15)), wordLimit15(body))
        }
    }

    // Build ticker frames:
    //   - All prefixFrames first (one per entry, shown as-is)
    //   - Then body words: 2 per frame; if 2 words exceed 15 chars, show 1 word instead
    // e.g. group "[SASA]" + "Anushka:" + "Where are you?" →
    //   "[SASA]"  →  "Anushka:"  →  "Where are"  →  "you?"
    private fun buildChunks(parts: NotifParts): List<String> {
        val frames = parts.prefixFrames.toMutableList()
        if (parts.body.isEmpty()) return frames

        val bodyWords = parts.body.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        var i = 0
        while (i < bodyWords.size) {
            if (i + 1 < bodyWords.size) {
                val twoWords = "${bodyWords[i]} ${bodyWords[i + 1]}"
                if (twoWords.length <= 15) {
                    frames.add(twoWords)
                    i += 2
                } else {
                    frames.add(bodyWords[i].take(15))
                    i += 1
                }
            } else {
                frames.add(bodyWords[i].take(15))
                i += 1
            }
        }
        return frames
    }

    private fun sendNotifPayload(gatt: BluetoothGatt, text: String) {
        val payload = KtmNativeProtocol.buildNotificationPayload(
            text, KtmNativeProtocol.NotificationIcon.REROUTING, KtmNativeProtocol.Visibility.FULL
        )
        // coalesce = true: if a previous ticker frame is still queued, replace it so frames
        // never pile up behind nav writes and appear as rapid-fire glitches.
        writeNavChar(gatt, KtmNativeProtocol.NOTIFICATION, payload, "Notif: $text", coalesce = true)
    }

    private fun clearNotifOnDash(gatt: BluetoothGatt) {
        val payload = KtmNativeProtocol.buildNotificationPayload(
            "", KtmNativeProtocol.NotificationIcon.REROUTING, KtmNativeProtocol.Visibility.OFF
        )
        writeNavChar(gatt, KtmNativeProtocol.NOTIFICATION, payload, "Notif: clear", coalesce = true)
    }

    // Flush all queued nav writes so the notification frame is next in line.
    private fun flushNavWritesFromQueue() {
        gattHandler.post {
            writeQueue.removeAll {
                it.label.startsWith("Nav:") || it.label.startsWith("Slot6:")
            }
        }
    }

    private fun cancelNotifTimers() {
        notifClearRunnable?.let  { mainHandler.removeCallbacks(it) }; notifClearRunnable  = null
        notifTickerRunnable?.let { mainHandler.removeCallbacks(it) }; notifTickerRunnable = null
    }

    private fun sendDashNotification(sender: String, body: String, type: String, isGroup: Boolean = false) {
        val gatt = bluetoothGatt ?: return
        if (activeSessionKey == null) return

        val parts  = parseNotif(sender, body, type, isGroup)
        val chunks = buildChunks(parts)
        val displayMs = if (navActive) NOTIF_NAV_MS else NOTIF_WELCOME_MS

        Log.d(TAG, "Notification: ${chunks.size} chunks, displayMs=$displayMs, nav=$navActive, chunks=$chunks")
        emitLog("NOTIF (${chunks.size} chunks): ${chunks.firstOrNull()}")
        KTMLinkServiceModule.emitNotification(sender, body, type)

        // Cancel previous notification and steal the dash.
        // Flush any queued nav writes so the first notification frame is next in line.
        cancelNotifTimers()
        notifTakeover = true
        flushNavWritesFromQueue()

        // Send first frame immediately
        sendNotifPayload(gatt, chunks[0])

        // Ticker: cycle through remaining chunks on mainHandler (same thread as clear)
        if (chunks.size > 1) {
            var idx = 0
            val tick = object : Runnable {
                override fun run() {
                    val g = bluetoothGatt ?: return
                    if (activeSessionKey == null) return
                    idx = (idx + 1) % chunks.size
                    sendNotifPayload(g, chunks[idx])
                    notifTickerRunnable = this
                    mainHandler.postDelayed(this, NOTIF_CHUNK_MS)
                }
            }
            notifTickerRunnable = tick
            mainHandler.postDelayed(tick, NOTIF_CHUNK_MS)
        }

        // After displayMs: stop ticker, clear dash, restore nav/welcome
        val clear = Runnable {
            notifClearRunnable  = null
            notifTickerRunnable?.let { mainHandler.removeCallbacks(it) }; notifTickerRunnable = null
            notifTakeover = false
            val g = bluetoothGatt ?: return@Runnable
            if (activeSessionKey == null) return@Runnable
            clearNotifOnDash(g)
            // On welcome screen: restore welcome after clearing the notification.
            // On nav: normal nav writes resume automatically (notifTakeover = false).
            if (!navActive) showWelcomeScreen(g)
        }
        notifClearRunnable = clear
        mainHandler.postDelayed(clear, displayMs)
    }

    fun clearActiveNotification(gatt: BluetoothGatt) {
        cancelNotifTimers()
        notifTakeover = false
        clearNotifOnDash(gatt)
    }

    private var mapsReceiverRegistered = false
    private val mapsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MapScraperService.ACTION_NOTIFICATION_REMOVED -> {
                    // Phone notification dismissed — if we're still mid-display, cancel immediately.
                    if (notifTakeover) {
                        val g = bluetoothGatt ?: return
                        if (activeSessionKey == null) return
                        clearActiveNotification(g)
                        if (!navActive) showWelcomeScreen(g)
                    }
                }
                MapScraperService.ACTION_NOTIFICATION -> {
                    val sender  = intent.getStringExtra(MapScraperService.EXTRA_NOTIF_SENDER)   ?: return
                    val body    = intent.getStringExtra(MapScraperService.EXTRA_NOTIF_BODY)     ?: ""
                    val type    = intent.getStringExtra(MapScraperService.EXTRA_NOTIF_TYPE)     ?: "message"
                    val isGroup = intent.getBooleanExtra(MapScraperService.EXTRA_NOTIF_IS_GROUP, false)
                    sendDashNotification(sender, body, type, isGroup)
                }
                MapScraperService.ACTION_MAPS_UPDATE -> {
                    navEndDebounceHandler?.removeCallbacksAndMessages(null)
                    navEndDebounceHandler = null

                    val title   = intent.getStringExtra(MapScraperService.EXTRA_TITLE)   ?: ""
                    val text    = intent.getStringExtra(MapScraperService.EXTRA_TEXT)    ?: ""
                    val subText = intent.getStringExtra(MapScraperService.EXTRA_SUB_TEXT)?: ""
                    val bigText = intent.getStringExtra(MapScraperService.EXTRA_BIG_TEXT)?: ""

                    if (!KtmNativeNavParser.isUsefulNavData(title, text)) return

                    val parsed = KtmNativeNavParser.parse(title, text, subText, bigText)
                    val gatt = bluetoothGatt ?: return
                    if (activeSessionKey == null) return

                    navActive = true
                    streamLiveNavigation(gatt, parsed)
                }

                MapScraperService.ACTION_MAPS_REMOVED -> {
                    navActive = false
                    navEndDebounceHandler?.removeCallbacksAndMessages(null)
                    val h = Handler(Looper.getMainLooper())
                    navEndDebounceHandler = h
                    h.postDelayed({
                        val gatt = bluetoothGatt ?: return@postDelayed
                        if (activeSessionKey == null) return@postDelayed
                        if (navActive) return@postDelayed  // route restarted during debounce
                        Log.d(TAG, "Nav end confirmed — showing welcome screen")
                        // If a notification was mid-display on the nav takeover path, cancel it
                        // so welcome screen appears immediately.
                        if (notifTakeover) clearActiveNotification(gatt)
                        // showWelcomeScreen handles stopSlot6 atomically inside gattHandler.
                        showWelcomeScreen(gatt)
                    }, NAV_END_DEBOUNCE_MS)
                }
            }
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        registerAdapterStateReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        val address = intent?.getStringExtra("EXTRA_DEVICE_ADDRESS")
        if (address != null) {
            prefs().edit().putString(PREFS_DEVICE_MAC, address).commit()
            Log.d(TAG, "Saved Classic BT MAC address from ACL intent: $address")
        }

        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification("KTMLink — Starting..."))
        registerMapsReceiver()

        if (bluetoothGatt != null) {
            Log.d(TAG, "onStartCommand: already have GATT — skipping")
            return START_STICKY
        }

        if (intent?.action == ACTION_TEST_WEATHER) {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            if (loc == null) {
                KTMLinkServiceModule.emitEvent("WEATHER_ERROR", "No location available")
                return START_STICKY
            }
            KtmIdleWeatherFetcher.fetch(loc.latitude, loc.longitude, onResult = { data: IdleWeatherData? ->
                if (data == null) {
                    KTMLinkServiceModule.emitEvent("WEATHER_ERROR", "Fetch failed")
                } else {
                    KTMLinkServiceModule.emitWeather(data.tempC, data.condition, data.usAqi)
                    Log.d(TAG, "Manual weather update: ${data.tempC}°C / ${data.condition} / AQI ${data.usAqi}")
                    // Also push to bike dash if connected and idle
                    val g = bluetoothGatt
                    if (g != null && activeSessionKey != null && !navActive) {
                        val full = KtmNativeProtocol.Visibility.FULL
                        writeNavChar(g, KtmNativeProtocol.TURN_DISTANCE,
                            KtmNativeProtocol.buildTurnDistancePayload("${data.tempC}°C", full), "Update: temp")
                        writeNavChar(g, KtmNativeProtocol.TURN_INFO,
                            KtmNativeProtocol.buildTurnInfoPayload(data.condition, full), "Update: condition")
                        writeNavChar(g, KtmNativeProtocol.REMAINING_DISTANCE,
                            KtmNativeProtocol.buildRemainingDistancePayload("AQI ${data.usAqi}", full), "Update: aqi")
                    }
                }
            })
            return START_STICKY
        }

        if (intent?.action == ACTION_CONNECT_DIRECTLY) {
            val mac = prefs().getString(PREFS_DEVICE_MAC, null)
            if (mac != null) {
                Log.d(TAG, "onStartCommand: ACTION_CONNECT_DIRECTLY → scanning for $mac")
                updateNotification("KTMLink — Connecting...")
                startBleScan()
            } else {
                Log.w(TAG, "onStartCommand: ACTION_CONNECT_DIRECTLY but no saved MAC — falling back to scan")
                updateNotification("KTMLink — Searching for KTM...")
                startBleScan()
            }
            return START_STICKY
        }

        updateNotification("KTMLink — Searching for KTM...")
        startBleScan()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        cancelPendingRunnables()
        notifClearRunnable?.let { mainHandler.removeCallbacks(it) }; notifClearRunnable = null
        notifTickerRunnable?.let { mainHandler.removeCallbacks(it) }; notifTickerRunnable = null
        navEndDebounceHandler?.removeCallbacksAndMessages(null); navEndDebounceHandler = null
        idleFetchVersion++
        weatherRefreshRunnable?.let { mainHandler.removeCallbacks(it) }; weatherRefreshRunnable = null
        stopSlot6()
        stopBleScan()
        adapterStateReceiver?.let { runCatching { unregisterReceiver(it) } }; adapterStateReceiver = null
        try { unregisterReceiver(mapsReceiver); mapsReceiverRegistered = false } catch (_: Exception) {}
        try { bluetoothGatt?.close(); bluetoothGatt = null } catch (_: Exception) {}
        gattThread.quitSafely()
        // Arm background BLE scan so the system wakes us when KTM advertises,
        // even if the service process is fully killed before it reconnects.
        val savedMac = prefs().getString(PREFS_DEVICE_MAC, null)
        KtmBleScanReceiver.scheduleBackgroundBleScan(this, savedMac)
        Log.d(TAG, "onDestroy: background BLE scan armed for auto-relaunch")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification helpers ──────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "KTMLink Service", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "KTMLink BLE nav bridge"
                setShowBadge(false); setSound(null, null); enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("KTMLink")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(null)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
    }

    fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun registerMapsReceiver() {
        if (mapsReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(MapScraperService.ACTION_MAPS_UPDATE)
            addAction(MapScraperService.ACTION_MAPS_REMOVED)
            addAction(MapScraperService.ACTION_NOTIFICATION)
            addAction(MapScraperService.ACTION_NOTIFICATION_REMOVED)
        }
        ContextCompat.registerReceiver(this, mapsReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        mapsReceiverRegistered = true
    }
}
