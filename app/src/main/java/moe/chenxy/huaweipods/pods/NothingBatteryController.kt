package moe.chenxy.huaweipods.pods

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log as AndroidLog
import moe.chenxy.huaweipods.hook.Log
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.PodParams
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.ExperimentalStdlibApi
import kotlin.text.HexFormat

/**
 * Nothing Ear (open) 电量控制器。
 *
 * 传输通道与华为耳机一致：经典蓝牙 RFCOMM，但走 Nothing 私有 SPP 服务
 * UUID AEAC4A03-DFF5-498F-843A-34487CF133EB（从 Nothing X 13.5.10
 * com.nothing.ear.flaffy FlaffyProtocol/ProtocolModel 提取）。
 *
 * 线上帧格式（从 Nothing X 的 Message.obtainDataPacket / Message.<init>([B) 逆出，
 * 所有多字节字段均为小端）：
 *   [0]      SOF 0x55
 *   [1..2]   control：deviceType(TWS=0x1)<<8 | CRC标志0x20 | multiFrame 0x40 | rspCode(低5位)
 *   [3..4]   command：请求命令（bit15=请求位），响应命令去掉 bit15
 *   [5..6]   length：payload 字节数
 *   [7]      fsn：帧序号
 *   [8..]    payload（length 字节）
 *   [..]     CRC16-Modbus（init 0xFFFF，poly 0xA001），小端 2 字节，覆盖 SOF..payload，
 *            仅当 control 带 0x20 标志
 *
 * 电量查询：0xC007，响应命令 0x4007；电量变化推送：0xE001。
 * 电量 payload 为 TLV：首字节 = 键值对数量 N，随后 N 组 [key, value]。
 * 键：1=watch 2=左耳 3=右耳 4=充电仓 5=tws 6=stereo；
 * 值字节：电量 = value & 0x7f，充电中 = value & 0x80。
 *
 * 连接后先发 activate（0xC001 协议版本查询，Nothing X isNeedActivate()=true 的握手帧），
 * 再发电量查询，与 Nothing X 的连接时序保持一致。短连接：每次查询独立建链、用完即关。
 */
internal object NothingBatteryController {
    private const val TAG = "NothingBattery"

    private val SPP_UUID: UUID = UUID.fromString("AEAC4A03-DFF5-498F-843A-34487CF133EB")

    private const val FRAME_SOF = 0x55
    private const val FRAME_HEADER_SIZE = 8
    private const val CRC_SIZE = 2
    private const val DEVICE_TYPE_TWS = 0x1
    private const val CONTROL_CRC_FLAG = 0x20
    private const val MASK_RESPONSE_CMD = 0x7FFF
    private const val MASK_BATTERY_VALUE = 0x7F
    private const val MASK_BATTERY_RECHARGING = 0x80

    private const val CMD_ACTIVATE = 0xC001          // GET_PROTOCOL_VERSION
          private const val CMD_GET_BATTERY = 0xC007       // GET_REMOTE_BATTERY_LEVEL
    private const val CMD_EVENT_BATTERY_CHANGED = 0xE001
    private const val CMD_SET_LOW_LATENCY = 0xF040   // SET_LAG_MODE：payload 0x01=开 0x02=关
   private const val BATTERY_KEY_LEFT = 0x2
    private const val BATTERY_KEY_RIGHT = 0x3
    private const val BATTERY_KEY_CASE = 0x4

    private const val CONNECT_TIMEOUT_MS = 3_000L
    private const val CONNECT_ATTEMPTS = 2
    private const val CONNECT_RETRY_DELAY_MS = 300L
    private const val RESPONSE_WINDOW_MS = 1_500L
    private const val POLL_INTERVAL_MS = 20L
    private const val ACTIVATE_DELAY_MS = 200L

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NothingBatteryController").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closing = AtomicBoolean(false)

    fun requestBattery(
        context: Context,
        device: BluetoothDevice,
        onBattery: (BatteryParams) -> Unit,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        if (closing.get()) {
            notifyComplete(onComplete, false)
            return
        }
        val appContext = context.applicationContext ?: context
        executor.execute {
            var socket: BluetoothSocket? = null
            try {
                socket = connect(appContext, device)
                val output = socket.outputStream

                // Nothing X 连接后先发 activate（0xC001 协议版本查询），再发业务查询。
                output.write(buildPacket(CMD_ACTIVATE, ByteArray(0)))
                output.flush()
                Thread.sleep(ACTIVATE_DELAY_MS)

                output.write(buildPacket(CMD_GET_BATTERY, ByteArray(0)))
                logInfo(appContext, "Nothing battery query sent device=${device.address}")

                val response = collectResponse(socket, RESPONSE_WINDOW_MS) { buffer ->
                    parseBattery(buffer) != null
                }
                val battery = parseBattery(response)
                logInfo(
                    appContext,
                    "Nothing battery response bytes=${response.size} parsed=${battery != null} " +
                        "hex=${response.toHexString()} device=${device.address}",
                )
                if (battery == null) {
                    notifyComplete(onComplete, false)
                } else {
                    onBattery(battery)
                    notifyComplete(onComplete, true)
                }
            } catch (t: Throwable) {
                if (t !is InterruptedException) {
                    logError(appContext, "Nothing battery request failed device=${device.address}", t)
                }
                notifyComplete(onComplete, false)
            } finally {
                runCatching { socket?.close() }
                    .onFailure { Log.w(TAG, "Nothing RFCOMM close failed", it) }
            }
        }
    }

    fun setLowLatency(
        context: Context,
        device: BluetoothDevice,
        enabled: Boolean,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        if (closing.get()) {
            notifyComplete(onComplete, false)
            return
        }
        val appContext = context.applicationContext ?: context
        executor.execute {
            var socket: BluetoothSocket? = null
            try {
                socket = connect(appContext, device)
                val output = socket.outputStream

                // Nothing X 连接后先发 activate（协议版本查询 0xC001），再进行业务查询。
                output.write(buildPacket(CMD_ACTIVATE, ByteArray(0)))
                output.flush()
                Thread.sleep(ACTIVATE_DELAY_MS)

                val payload = byteArrayOf(if (enabled) 0x01 else 0x02)
                output.write(buildPacket(CMD_SET_LOW_LATENCY, payload))
                logInfo(
                    appContext,
                    "Nothing low-latency set enabled=$enabled device=${device.address}",
                )

                val acked = collectResponse(socket, RESPONSE_WINDOW_MS) { buffer ->
                    hasResponseFrame(buffer, CMD_SET_LOW_LATENCY) != null
                }
                logInfo(
                    appContext,
                    "Nothing low-latency acked=${acked != null} device=${device.address}",
                )
                if (acked != null) {
                    notifyComplete(onComplete, true)
                } else {
                    notifyComplete(onComplete, false)
                }
            } catch (t: Throwable) {
                if (t !is InterruptedException) {
                    logError(appContext, "Nothing low-latency request failed device=${device.address}", t)
                }
                notifyComplete(onComplete, false)
            } finally {
                runCatching { socket?.close() }
                    .onFailure { Log.w(TAG, "Nothing RFCOMM close failed", it) }
            }
        }
    }

    private fun connect(context: Context, device: BluetoothDevice): BluetoothSocket {
        var lastFailure: Throwable? = null
        val candidates = listOf(
            "secure-spp" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "insecure-spp" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        )
        for ((label, create) in candidates) {
            repeat(CONNECT_ATTEMPTS) { attempt ->
                if (closing.get()) throw IOException("Nothing battery controller is closing")
                val socket = runCatching { create() }.getOrElse {
                    lastFailure = it
                    return@repeat
                }
                try {
                    connectWithTimeout(socket, CONNECT_TIMEOUT_MS)
                    logInfo(context, "Nothing RFCOMM connected label=$label attempt=${attempt + 1} device=${device.address}")
                    return socket
                } catch (t: IOException) {
                    lastFailure = t
                    runCatching { socket.close() }
                    logInfo(context, "Nothing RFCOMM candidate failed label=$label attempt=${attempt + 1}: ${t.message}")
                    if (attempt + 1 < CONNECT_ATTEMPTS) Thread.sleep(CONNECT_RETRY_DELAY_MS)
                }
            }
        }
        throw lastFailure ?: IOException("No Nothing SPP candidate succeeded")
    }

    private fun connectWithTimeout(socket: BluetoothSocket, timeoutMs: Long) {
        var connectFailure: Throwable? = null
        val latch = CountDownLatch(1)
        val thread = Thread(
            {
                try {
                    socket.connect()
                } catch (t: Throwable) {
                    connectFailure = t
                } finally {
                    latch.countDown()
                }
            },
            "nothing-spp-connect",
        ).apply { isDaemon = true }
        thread.start()
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            runCatching { socket.close() }
            thread.join(500L)
            throw SocketTimeoutException("Nothing RFCOMM connect timeout")
        }
        connectFailure?.let {
            throw it as? IOException ?: IOException(it.message.orEmpty(), it)
        }
    }

    /**
     * 出站帧：SOF + control(LE) + command(LE) + length(LE) + fsn + payload + CRC16(LE)。
     * control = deviceType(TWS=0x1)<<8 | CRC标志0x20；请求命令 bit15 已含在命令值里。
     */
    private fun buildPacket(command: Int, payload: ByteArray, fsn: Int = 1): ByteArray {
        val control = (DEVICE_TYPE_TWS shl 8) or CONTROL_CRC_FLAG
        val packet = ByteArray(FRAME_HEADER_SIZE + payload.size + CRC_SIZE)
        var offset = 0
        packet[offset++] = FRAME_SOF.toByte()
        packet[offset++] = (control and 0xFF).toByte()
        packet[offset++] = (control shr 8 and 0xFF).toByte()
        packet[offset++] = (command and 0xFF).toByte()
        packet[offset++] = (command shr 8 and 0xFF).toByte()
        packet[offset++] = (payload.size and 0xFF).toByte()
        packet[offset++] = (payload.size shr 8 and 0xFF).toByte()
        packet[offset++] = fsn.toByte()
        payload.copyInto(packet, offset)
        val crc = crc16Modbus(packet, 0, FRAME_HEADER_SIZE + payload.size)
        packet[FRAME_HEADER_SIZE + payload.size] = (crc and 0xFF).toByte()
        packet[FRAME_HEADER_SIZE + payload.size + 1] = (crc shr 8 and 0xFF).toByte()
        return packet
    }

    private fun parseBattery(stream: ByteArray): BatteryParams? {
        var latest: BatteryParams? = null
        var index = 0
        while (index + FRAME_HEADER_SIZE <= stream.size) {
            if (stream[index] != FRAME_SOF.toByte()) {
                index++
                continue
            }
            val control = readLe16(stream, index + 1)
            if (control and CONTROL_CRC_FLAG == 0) {
                index++
                continue
            }
            val command = readLe16(stream, index + 3)
            val length = readLe16(stream, index + 5)
            val frameSize = FRAME_HEADER_SIZE + length + CRC_SIZE
            if (index + frameSize > stream.size) break
            val payloadStart = index + FRAME_HEADER_SIZE
            val payloadEnd = payloadStart + length
            if (verifyCrc(stream, index, payloadEnd) &&
                isBatteryCommand(command) &&
                length > 0
            ) {
                val payload = stream.copyOfRange(payloadStart, payloadEnd)
                parseBatteryPayload(payload)?.let { latest = it }
            }
            index += frameSize
        }
        return latest
    }

    /**
     * 流中是否出现指定请求命令的响应帧（响应命令 = 请求命令去掉 0x8000 请求位）。
     * 用于 SET 命令的 ACK 判定，校验方式与电池帧相同（CRC16-Modbus）。
     */
    private fun hasResponseFrame(stream: ByteArray, requestCommand: Int): ByteArray? {
        var ack: ByteArray? = null
        var index = 0
        while (index + FRAME_HEADER_SIZE <= stream.size) {
            if (stream[index] != FRAME_SOF.toByte()) {
                index++
                continue
            }
            val command = readLe16(stream, index + 3)
            val length = readLe16(stream, index + 5)
            val frameSize = FRAME_HEADER_SIZE + length + CRC_SIZE
            if (index + frameSize > stream.size) break
            val payloadStart = index + FRAME_HEADER_SIZE
            val payloadEnd = payloadStart + length
            if (length > 0 &&
                command and MASK_RESPONSE_CMD == requestCommand and MASK_RESPONSE_CMD &&
                verifyCrc(stream, index, payloadEnd)
            ) {
                ack = stream.copyOfRange(payloadStart, payloadEnd)
            }
            index += frameSize
        }
        return ack
    }

    private fun isBatteryCommand(command: Int): Boolean {
        val responseCommand = command and MASK_RESPONSE_CMD
        return responseCommand == (CMD_GET_BATTERY and MASK_RESPONSE_CMD) ||
            responseCommand == (CMD_EVENT_BATTERY_CHANGED and MASK_RESPONSE_CMD)
    }

    // Nothing 电池 payload：[N][key value][key value]…，value 高位=充电中、低 7 位=电量。
    private fun parseBatteryPayload(payload: ByteArray): BatteryParams? {
        if (payload.isEmpty()) return null
        val pairCount = payload[0].toInt() and 0xFF
        var left: PodParams? = null
        var right: PodParams? = null
        var case: PodParams? = null
        var pairs = 0
        var offset = 1
        while (offset + 1 < payload.size && pairs < pairCount) {
            val key = payload[offset].toInt() and 0xFF
            val value = payload[offset + 1].toInt() and 0xFF
            val pod = PodParams(
                battery = value and MASK_BATTERY_VALUE,
                isCharging = value and MASK_BATTERY_RECHARGING != 0,
                isConnected = true,
                rawStatus = value,
            )
            when (key) {
                BATTERY_KEY_LEFT -> left = pod
                BATTERY_KEY_RIGHT -> right = pod
                BATTERY_KEY_CASE -> case = pod
            }
            pairs++
            offset += 2
        }
        if (left == null && right == null && case == null) return null
        return BatteryParams(left = left, right = right, case = case)
    }

    private fun collectResponse(
        socket: BluetoothSocket,
        timeoutMs: Long,
        responseComplete: ((ByteArray) -> Boolean)?,
    ): ByteArray {
        val input = socket.inputStream
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val available = runCatching { input.available() }.getOrDefault(0)
            if (available > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, available))
                if (read > 0) {
                    output.write(buffer, 0, read)
                    if (responseComplete?.invoke(output.toByteArray()) == true) break
                }
                continue
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return output.toByteArray()
    }

    private fun readLe16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    @OptIn(ExperimentalStdlibApi::class)
    private fun ByteArray.toHexString(): String = toHexString(HexFormat.UpperCase)

    private fun verifyCrc(source: ByteArray, from: Int, toExclusive: Int): Boolean {
        if (toExclusive + CRC_SIZE > source.size) return false
        val expected = readLe16(source, toExclusive)
        return crc16Modbus(source, from, toExclusive) == expected
    }

    // CRC16-Modbus：init 0xFFFF，poly 0xA001（反射），无输出异或。
    private fun crc16Modbus(source: ByteArray, from: Int, toExclusive: Int): Int {
        var crc = 0xFFFF
        for (i in from until toExclusive) {
            crc = crc xor (source[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x0001 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    private fun notifyComplete(
        callback: ((Boolean) -> Unit)?,
        success: Boolean,
    ) {
        callback ?: return
        mainHandler.post { callback(success) }
    }

    private fun logInfo(context: Context, message: String) {
        Log.w(TAG, message)
        AndroidLog.i(TAG, message)
        RfcommLog.i(context, TAG, message)
    }

    private fun logError(context: Context, message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
        AndroidLog.e(TAG, message, throwable)
        RfcommLog.e(context, TAG, "$message: ${throwable.message.orEmpty()}")
    }
}
