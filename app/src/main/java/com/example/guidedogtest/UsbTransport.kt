package com.example.guidedogtest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

/**
 * USB serial transport over the USB-C cable to the ESP32.
 *
 * The phone is the USB host here: it powers the ESP32 and talks to it as a CDC/ACM or
 * USB-UART bridge device at 115200 baud - the same link the Arduino IDE uses, so the
 * firmware needs no changes.
 *
 * DTR and RTS are deliberately driven low: on ESP32 boards those two lines are wired to the
 * reset/boot circuit, and the idle "running" state is both low. Toggling them would put the
 * chip into reset or into the bootloader instead of running the sketch.
 */
class UsbTransport(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onTelemetry: (String) -> Unit,
    private val onConnected: (Boolean) -> Unit,
) : Transport {

    override val label: String = "USB"

    private val appContext = context.applicationContext
    private val usbManager =
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val writeLock = Any()
    private val buffer = StringBuilder()
    private var receiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? =
                IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "permission result for $device: $granted")
            if (granted && device != null) {
                openDriver(findDriver(device))
            } else {
                onStatus("USB permission denied")
            }
        }
    }

    private val ioListener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            feed(data)
        }

        override fun onRunError(e: Exception) {
            Log.i(TAG, "usb read error", e)
            onStatus("USB read error: ${e.message}")
        }
    }

    override fun devicePresent(): Boolean = findDriver() != null

    override fun connect() {
        val driver = findDriver()
        if (driver == null) {
            onStatus("no USB serial device attached")
            return
        }

        if (!usbManager.hasPermission(driver.device)) {
            onStatus("asking for USB permission")
            requestPermission(driver.device)
            return
        }

        openDriver(driver)
    }

    override fun send(frame: String): Boolean {
        val port = port ?: return false
        return try {
            synchronized(writeLock) {
                port.write(frame.toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)
            }
            true
        } catch (e: Exception) {
            Log.i(TAG, "usb write failed", e)
            onStatus("USB write failed: ${e.message}")
            false
        }
    }

    override fun close() {
        ioManager?.stop()
        ioManager = null
        try {
            port?.close()
        } catch (e: Exception) {
            Log.i(TAG, "usb close failed", e)
        }
        port = null
        unregisterReceiver()
        onConnected(false)
    }

    private fun requestPermission(device: UsbDevice) {
        registerReceiver()
        val flags = PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pendingIntent =
            PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun openDriver(driver: UsbSerialDriver?) {
        if (driver == null) {
            onStatus("USB device vanished")
            return
        }

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            onStatus("could not open USB device")
            return
        }

        val serialPort = driver.ports.firstOrNull()
        if (serialPort == null) {
            connection.close()
            onStatus("USB device has no serial port")
            return
        }

        try {
            serialPort.open(connection)
            serialPort.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPort.dtr = false
            serialPort.rts = false
        } catch (e: Exception) {
            Log.i(TAG, "usb open failed", e)
            onStatus("USB open failed: ${e.message}")
            runCatching { serialPort.close() }
            runCatching { connection.close() }
            return
        }

        port = serialPort
        ioManager = SerialInputOutputManager(serialPort, ioListener).also { it.start() }
        unregisterReceiver()

        onStatus("ready (USB @ $BAUD)")
        onConnected(true)
    }

    private fun findDriver(device: UsbDevice? = null): UsbSerialDriver? {
        val prober = UsbSerialProber.getDefaultProber()
        return if (device == null) {
            prober.findAllDrivers(usbManager).firstOrNull()
        } else {
            prober.probeDevice(device)
        }
    }

    private fun feed(bytes: ByteArray) {
        buffer.append(String(bytes, Charsets.US_ASCII))
        while (true) {
            val end = buffer.indexOf("\n")
            if (end < 0) break
            val line = buffer.substring(0, end).trim()
            buffer.delete(0, end + 1)
            if (line.isNotEmpty()) {
                describeTelemetry(line)?.let(onTelemetry)
            }
        }
        // Guard against a stuck partial line if the stream ever goes binary.
        if (buffer.length > MAX_BUFFER) buffer.setLength(0)
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        receiverRegistered = false
    }

    companion object {
        private const val TAG = "UsbTransport"
        private const val ACTION_USB_PERMISSION = "com.example.guidedogtest.USB_PERMISSION"
        private const val BAUD = 115200
        private const val WRITE_TIMEOUT_MS = 200
        private const val MAX_BUFFER = 4096
    }
}
