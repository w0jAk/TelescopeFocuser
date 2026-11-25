package com.example.focussetter

import android.bluetooth.*
import android.content.Context
import android.os.Build
import java.nio.charset.Charset
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first


private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 64)
val lines: SharedFlow<String> = _lines.asSharedFlow()
private val rxTextBuf = StringBuilder()


class BleUartClient(
    private val context: Context,
    private val _position: MutableStateFlow<Long?> = MutableStateFlow<Long?>(null),
    val position: StateFlow<Long?> = _position

) {

    private val txUuid: UUID = UUID.fromString("0000FFE9-0000-1000-8000-00805F9B34FB") // WRITE
    private val rxUuid: UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB") // NOTIFY
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    sealed class ConnState {
        data object Idle : ConnState()
        data object Connecting : ConnState()
        data class Connected(val address: String) : ConnState()
        data class Error(val msg: String) : ConnState()
        data object Disconnected : ConnState()
    }

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private fun feedRxText(chunk: String) {
        val text = chunk.replace("\r", "\n")
        runCatching {
            rxTextBuf.append(text)
            while (true) {
                val idx = rxTextBuf.indexOf("\n")
                if (idx < 0) break
                val line = rxTextBuf.substring(0, idx).trim()
                rxTextBuf.delete(0, idx + 1)
                if (line.isNotEmpty()) {
                    _lines.tryEmit(line)
                    parsePositionFromLine(line)
                }
            }

            if (rxTextBuf.length > 160) {
                val line = rxTextBuf.toString().trim()
                rxTextBuf.setLength(0)
                if (line.isNotEmpty()) {
                    _lines.tryEmit(line)
                    parsePositionFromLine(line)
                }
            }
        }
    }

    suspend fun moveAndWait(dirOut: Boolean, steps: Int, timeoutMs: Long = 15000): Long? {
        val cmd = if (dirOut) "OUT $steps" else "IN $steps"
        sendCommand(cmd)

        return withTimeout(timeoutMs) {
            lines.first { it.startsWith("DONE POS") || it.startsWith("ERR STOP POS") }.let {
                _position.value
            }
        }
    }

    suspend fun queryPos(timeoutMs: Long = 3000): Long? {
        sendCommand("POS?")
        return withTimeout(timeoutMs) { lines.first { it.startsWith("POS ") } ; _position.value }
    }


    private fun parsePositionFromLine(line: String) {

        val parts = line.split(' ')
        if (parts.size >= 2 && parts[0] == "POS") {
            parts.getOrNull(1)?.toLongOrNull()?.let { _position.value = it }
        } else if (parts.size >= 3 && parts[1] == "POS") {
            parts.getOrNull(2)?.toLongOrNull()?.let { _position.value = it }
        }
    }

    fun connect(device: BluetoothDevice) {
        disconnectInternal(setState = false)

        if (!BlePerms.hasConnect(context)) {
            _state.value = ConnState.Error("Missing BLUETOOTH_CONNECT permission")
            return
        }

        _state.value = ConnState.Connecting

        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, cb)
            }
        } catch (_: SecurityException) {
            _state.value = ConnState.Error("SecurityException on connectGatt (permission)")
        } catch (t: Throwable) {
            _state.value = ConnState.Error("connectGatt failed: ${t.message}")
        }
    }

    fun disconnect() {
        disconnectInternal(setState = true)
    }

    private fun disconnectInternal(setState: Boolean) {
        val g = gatt
        gatt = null
        txChar = null
        rxChar = null

        if (g != null) {
            try { g.disconnect() } catch (_: SecurityException) {}
            catch (_: Throwable) {}

            try { g.close() } catch (_: SecurityException) {}
            catch (_: Throwable) {}
        }

        if (setState) _state.value = ConnState.Disconnected
    }


    fun sendCommand(cmd: String) {
        val g = gatt ?: run { appendLog("ERR: not connected"); return }
        val c = txChar ?: run { appendLog("ERR: TX characteristic not ready"); return }

        if (!BlePerms.hasConnect(context)) {
            appendLog("ERR: missing BLUETOOTH_CONNECT permission")
            return
        }

        val payload = cmd.trim().toByteArray(Charset.forName("UTF-8"))


        val props = c.properties
        val writeType =
            if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        try {
            val started = if (Build.VERSION.SDK_INT >= 33) {
                val status = g.writeCharacteristic(c, payload, writeType)
                status == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = writeType
                    c.value = payload
                    g.writeCharacteristic(c)
                }
            }

            if (!started) appendLog("ERR: writeCharacteristic did not start")
            else appendLog(">> $cmd")

        } catch (_: SecurityException) {
            appendLog("ERR: SecurityException during write (permission)")
        } catch (t: Throwable) {
            appendLog("ERR: write failed: ${t.message}")
        }
    }

    private val cb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnState.Error("GATT error: $status")
                appendLog("ERR: connection state change status=$status")
                disconnectInternal(setState = false)
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val addr = try {
                    if (BlePerms.hasConnect(context)) gatt.device.address else "unknown"
                } catch (_: SecurityException) {
                    "unknown"
                }
                _state.value = ConnState.Connected(addr)
                appendLog("Connected ($addr), discovering services...")

                try {
                    gatt.discoverServices()
                } catch (_: SecurityException) {
                    _state.value = ConnState.Error("SecurityException on discoverServices (permission)")
                } catch (t: Throwable) {
                    _state.value = ConnState.Error("discoverServices failed: ${t.message}")
                }

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("Disconnected")
                _state.value = ConnState.Disconnected
                disconnectInternal(setState = false)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnState.Error("Service discovery failed: $status")
                appendLog("ERR: services discovered status=$status")
                return
            }


            var foundTx: BluetoothGattCharacteristic? = null
            var foundRx: BluetoothGattCharacteristic? = null

            for (s in gatt.services) {
                for (ch in s.characteristics) {
                    if (ch.uuid == txUuid) foundTx = ch
                    if (ch.uuid == rxUuid) foundRx = ch
                }
            }

            txChar = foundTx
            rxChar = foundRx

            if (txChar == null || rxChar == null) {
                _state.value = ConnState.Error("TX/RX characteristic not found (HM-BT4502 UUIDs)")
                appendLog("ERR: TX=$txUuid found=${txChar != null}")
                appendLog("ERR: RX=$rxUuid found=${rxChar != null}")
                return
            }

            appendLog("GATT ready. TX=${txChar!!.uuid}, RX=${rxChar!!.uuid}")
            enableNotifications(gatt, rxChar!!)
        }


        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == rxChar?.uuid) {
                val chunk = value.toString(Charsets.UTF_8)
                appendLog("<< $chunk")
                feedRxText(chunk)

            }
        }


        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val v = characteristic.value ?: return
            onCharacteristicChanged(gatt, characteristic, v)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == cccdUuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) appendLog("Notifications enabled")
                else appendLog("WARN: CCCD write failed: $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                appendLog("ERR: write status=$status")
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (!BlePerms.hasConnect(context)) {
            appendLog("ERR: missing BLUETOOTH_CONNECT permission (notify)")
            return
        }

        try {
            val ok = gatt.setCharacteristicNotification(ch, true)
            if (!ok) appendLog("WARN: setCharacteristicNotification returned false")

            val cccd = ch.getDescriptor(cccdUuid)
            if (cccd == null) {
                appendLog("WARN: CCCD not found; notifications may not work")
                return
            }


            cccd.value =
                if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

            val started = if (Build.VERSION.SDK_INT >= 33) {
                val status = gatt.writeDescriptor(cccd, cccd.value)
                status == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }

            if (!started) appendLog("WARN: CCCD write did not start")

        } catch (_: SecurityException) {
            appendLog("ERR: SecurityException enabling notifications (permission)")
        } catch (t: Throwable) {
            appendLog("ERR: enableNotifications failed: ${t.message}")
        }
    }

    private fun appendLog(line: String) {
        val ts = System.currentTimeMillis()
        _log.value = (_log.value + "$ts  $line").takeLast(300)
    }
}
