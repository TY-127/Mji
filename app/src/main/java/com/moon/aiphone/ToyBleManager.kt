package com.moon.aiphone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 密语时刻 — 互动玩具 BLE 控制（协议与 AionsHome BleBridge/chat.js 相同）
 * 扫描 SOSEXY 设备 → GATT 连接 → 18 字节分包写入控制指令。
 * 单例持有连接，弹窗关掉也不断开。
 */
@SuppressLint("MissingPermission")
object ToyBleManager {

    private const val TAG = "ToyBle"
    private val SERVICE_UUID = UUID.fromString("0000ee01-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID = UUID.fromString("0000ee03-0000-1000-8000-00805f9b34fb")
    private val NOTIFY_UUID = UUID.fromString("0000ee02-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    interface Listener {
        fun onStateChanged(connected: Boolean)
        fun onLog(msg: String)
    }

    // 三路马达：震动 / 电流 / 吮吸（gearsSpec=强度通道，modeSpec=模式通道）
    private data class Motor(val gearsSpec: String, val modeSpec: String)
    private val MOTORS = listOf(
        Motor("0001", "0002"),
        Motor("0003", "0004"),
        Motor("0007", "0008"),
    )

    // 九档预设：每档 = 三路马达的 (开关, 模式, 强度)
    private data class MotorSet(val on: Boolean, val mode: Int, val speed: Int)
    private val PRESETS: List<List<MotorSet>> = listOf(
        listOf(MotorSet(false, 1, 10), MotorSet(false, 1, 0),  MotorSet(true, 1, 10)),
        listOf(MotorSet(false, 1, 20), MotorSet(false, 1, 10), MotorSet(true, 3, 20)),
        listOf(MotorSet(false, 2, 30), MotorSet(false, 1, 20), MotorSet(true, 2, 30)),
        listOf(MotorSet(false, 2, 45), MotorSet(false, 2, 25), MotorSet(true, 4, 40)),
        listOf(MotorSet(false, 3, 60), MotorSet(true, 2, 20),  MotorSet(true, 2, 50)),
        listOf(MotorSet(true, 3, 10),  MotorSet(true, 3, 30),  MotorSet(true, 4, 60)),
        listOf(MotorSet(true, 2, 20),  MotorSet(true, 4, 40),  MotorSet(true, 4, 80)),
        listOf(MotorSet(true, 1, 30),  MotorSet(true, 3, 80),  MotorSet(true, 3, 100)),
        listOf(MotorSet(true, 4, 40),  MotorSet(true, 3, 90),  MotorSet(true, 3, 100)),
    )

    private val CN_NUM = "一二三四五六七八九"
    fun presetName(n: Int): String = if (n in 1..9) "${CN_NUM[n - 1]}档" else "${n}档"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val writeExecutor = Executors.newSingleThreadExecutor()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile var isConnected = false
        private set
    @Volatile var activePreset = -1
        private set
    @Volatile private var scanning = false
    @Volatile private var writeLatch: CountDownLatch? = null

    private var listener: Listener? = null

    fun setListener(l: Listener?) { listener = l }

    private fun notifyState(connected: Boolean) {
        mainHandler.post { listener?.onStateChanged(connected) }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        mainHandler.post { listener?.onLog(msg) }
    }

    // ── 连接 ──

    fun connect(context: Context) {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bm?.adapter
        if (adapter == null || !adapter.isEnabled) { log("蓝牙未开启"); return }
        if (isConnected || scanning) return

        val scanner = adapter.bluetoothLeScanner ?: run { log("无法获取BLE扫描器"); return }
        val appContext = context.applicationContext

        val scanCb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!scanning) return
                val dev: BluetoothDevice = result.device
                val name = try { dev.name } catch (_: Exception) { null }
                if (name != null && name.startsWith("SOSEXY")) {
                    scanning = false
                    try { scanner.stopScan(this) } catch (_: Exception) {}
                    log("发现设备：$name")
                    connectGatt(appContext, dev)
                }
            }
        }

        scanning = true
        log("搜索设备中...")
        try {
            scanner.startScan(scanCb)
        } catch (e: Exception) {
            scanning = false
            log("扫描失败: ${e.message}")
            return
        }
        mainHandler.postDelayed({
            if (scanning) {
                scanning = false
                try { scanner.stopScan(scanCb) } catch (_: Exception) {}
                log("未找到设备")
            }
        }, 10_000)
    }

    fun disconnect() {
        scanning = false
        isConnected = false
        activePreset = -1
        writeChar = null
        gatt?.let { g -> try { g.disconnect(); g.close() } catch (_: Exception) {} }
        gatt = null
        notifyState(false)
    }

    private fun connectGatt(context: Context, dev: BluetoothDevice) {
        try {
            gatt = dev.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            log("连接失败")
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                isConnected = false
                activePreset = -1
                writeChar = null
                try { g.close() } catch (_: Exception) {}
                log("已断开")
                notifyState(false)
            }
        }

        @Suppress("DEPRECATION")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { log("服务发现失败"); return }
            val svc = g.getService(SERVICE_UUID) ?: run { log("未找到BLE服务"); return }
            val wc = svc.getCharacteristic(WRITE_UUID) ?: run { log("未找到写入特征"); return }
            wc.writeType = if ((wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            writeChar = wc
            svc.getCharacteristic(NOTIFY_UUID)?.let { nc ->
                g.setCharacteristicNotification(nc, true)
                nc.getDescriptor(CCCD_UUID)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            isConnected = true
            log("已连接 ♡")
            notifyState(true)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeLatch?.countDown()
        }
    }

    // ── 指令 ──

    /** AI / 用户档位指令：n=1..9 设档，0 或负数 = 停止 */
    fun applyPreset(n: Int) {
        if (!isConnected) { log("未连接"); return }
        if (n !in 1..9) { stopAll(); return }
        activePreset = n
        val preset = PRESETS[n - 1]
        writeExecutor.execute {
            for (i in 0..2) {
                val m = preset[i]
                val mo = MOTORS[i]
                sendDataInternal(buildDualCmd(mo.modeSpec, m.mode, mo.gearsSpec, if (m.on) m.speed else 0))
                try { Thread.sleep(80) } catch (_: InterruptedException) {}
            }
        }
        log("⚡ ${presetName(n)}")
    }

    fun stopAll() {
        if (!isConnected) return
        activePreset = -1
        writeExecutor.execute { sendDataInternal("03000111000003110000071100") }
        log("⏹ 停止")
    }

    private fun hex2(n: Int) = n.coerceIn(0, 255).toString(16).padStart(2, '0')

    private fun buildDualCmd(s1: String, v1: Int, s2: String, v2: Int) =
        "02" + s1 + "11" + hex2(v1) + s2 + "11" + hex2(v2)

    // ── 封包写入（"00" 前缀 → 18字节分块 → [random, index] 包头） ──

    @Suppress("DEPRECATION")
    private fun sendDataInternal(hexCmd: String) {
        val g = gatt ?: return
        val wc = writeChar ?: return
        try {
            val data = hexToBytes("00$hexCmd")
            val chunkSize = 18
            val numChunks = maxOf(1, (data.size + chunkSize - 1) / chunkSize)
            val rnd = (Math.random() * 255).toInt()

            for (i in 0 until numChunks) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize, data.size)
                val pkt = ByteArray(2 + (end - start))
                pkt[0] = rnd.toByte()
                pkt[1] = (i + 1).toByte()
                System.arraycopy(data, start, pkt, 2, end - start)
                if (!writeChunk(g, wc, pkt)) {
                    Log.w(TAG, "writeChunk timeout at chunk $i")
                    return
                }
            }
            // 末块恰好 18 字节时追加终止包
            if (data.isNotEmpty() && data.size % chunkSize == 0) {
                writeChunk(g, wc, byteArrayOf(rnd.toByte(), (numChunks + 1).toByte()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendData error", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChunk(g: BluetoothGatt, wc: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val latch = CountDownLatch(1)
        writeLatch = latch
        wc.value = value
        g.writeCharacteristic(wc)
        return try { latch.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in hex.indices step 2)
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        return out
    }
}
