package com.example.guidedogtest

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Bluetooth LE transport, used as the fallback when the USB cable is not attached.
 *
 * The ESP32 advertises as "OpenBot: DIY_ESP32" and exposes the OpenBot UART service:
 * one characteristic for commands (write without response) and one for telemetry (notify).
 */
class BleTransport(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onTelemetry: (String) -> Unit,
    private val onConnected: (Boolean) -> Unit,
) : Transport {

    override val label: String = "BLE"

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            val advertisesOurService =
                result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
            if (name.startsWith(DEVICE_NAME_PREFIX) || advertisesOurService) {
                stopScan()
                connectTo(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            onStatus("BLE scan failed ($errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        /** A superseded connection's callbacks must not touch the state of the live one. */
        private fun isCurrent(connection: BluetoothGatt) = connection === gatt

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent(gatt)) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus("BLE connected, discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onStatus("BLE disconnected")
                commandChar = null
                // A dropped link has to give the client back: Android holds the connection's
                // resources until close(), and the next CONNECT press starts a fresh scan anyway.
                gatt.close()
                this@BleTransport.gatt = null
                main.post { onConnected(false) }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrent(gatt)) return
            val service = gatt.getService(SERVICE_UUID)
            val rx = service?.getCharacteristic(RX_UUID)
            val tx = service?.getCharacteristic(TX_UUID)
            if (rx == null || tx == null) {
                onStatus("BLE: robot service not found - ESP32 firmware not running?")
                return
            }
            commandChar = rx

            gatt.setCharacteristicNotification(tx, true)
            tx.getDescriptor(CCCD_UUID)?.let { descriptor ->
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            onStatus("ready (BLE)")
            main.post { onConnected(true) }
        }

        // The API 33+ three-argument overload delegates to this one, so this covers every
        // version we support.
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (!isCurrent(gatt)) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            feed(value)
        }
    }

    @SuppressLint("MissingPermission")
    override fun devicePresent(): Boolean = manager?.adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    override fun connect() {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Bluetooth is off")
            return
        }
        onStatus("BLE scanning for $DEVICE_NAME_PREFIX")
        adapter.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    override fun send(frame: String): Boolean {
        val gatt = gatt ?: return false
        val characteristic = commandChar ?: return false
        val bytes = frame.toByteArray(Charsets.US_ASCII)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        stopScan()
        commandChar = null
        gatt?.close()
        gatt = null
        onConnected(false)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        onStatus("BLE connecting to ${device.name}")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun feed(bytes: ByteArray) {
        val text = String(bytes, Charsets.US_ASCII)
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            describeTelemetry(trimmed)?.let(onTelemetry)
        }
    }

    companion object {
        private const val DEVICE_NAME_PREFIX = "OpenBot"

        val SERVICE_UUID: UUID = UUID.fromString("61653dc3-4021-4d1e-ba83-8b4eec61d613")
        val RX_UUID: UUID = UUID.fromString("06386c14-86ea-4d71-811c-48f97c58f8c9")
        val TX_UUID: UUID = UUID.fromString("9bf1103b-834c-47cf-b149-c9e4bcf778a7")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
