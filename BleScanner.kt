package com.example.focussetter

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ScanDevice(val name: String?, val address: String, val rssi: Int)

class BleScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter
) {
    private val scanner: BluetoothLeScanner? get() = adapter.bluetoothLeScanner

    private val seen = LinkedHashMap<String, ScanDevice>()

    private val _devices = MutableStateFlow<List<ScanDevice>>(emptyList())
    val devices: StateFlow<List<ScanDevice>> = _devices

    private val _hits = MutableStateFlow(0)

    private val _lastError = MutableStateFlow<String?>(null)

    private var cb: ScanCallback? = null

    fun start() {
        stop()

        _lastError.value = null
        _hits.value = 0

        if (!adapter.isEnabled) {
            _lastError.value = "Bluetooth is OFF"
            _devices.value = emptyList()
            return
        }

        if (!BlePerms.hasScan(context)) {
            _lastError.value = "Missing BLUETOOTH_SCAN permission"
            _devices.value = emptyList()
            return
        }

        val s = scanner
        if (s == null) {
            _lastError.value = "bluetoothLeScanner is null"
            _devices.value = emptyList()
            return
        }

        seen.clear()
        _devices.value = emptyList()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                _hits.value = _hits.value + 1

                val advName = result.scanRecord?.deviceName
                val item = ScanDevice(
                    name = advName,
                    address = result.device.address,
                    rssi = result.rssi
                )

                seen[item.address] = item
                _devices.value = seen.values.sortedByDescending { it.rssi }
            }

            override fun onScanFailed(errorCode: Int) {
                _lastError.value = "onScanFailed=$errorCode"
            }
        }

        try {
            s.startScan(null, settings, cb)
        } catch (se: SecurityException) {
            _lastError.value = "SecurityException (scan permission?)"
            _devices.value = emptyList()
            stop()
        } catch (t: Throwable) {
            _lastError.value = "startScan error: ${t.message}"
            _devices.value = emptyList()
            stop()
        }
    }

    fun stop() {
        val c = cb ?: return
        try { scanner?.stopScan(c) } catch (_: SecurityException) {}
        cb = null
    }
}
