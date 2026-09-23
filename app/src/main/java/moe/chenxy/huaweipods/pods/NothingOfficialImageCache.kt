package moe.chenxy.huaweipods.pods

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import moe.chenxy.huaweipods.HuaweiPodsApp
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.config.PodImageChangeNotifier
import moe.chenxy.huaweipods.config.PodImagePrefs
import moe.chenxy.huaweipods.config.PodImageResource

/**
 * Nothing 官方设备图下载器。
 *
 * 图源是 Nothing X 内置设备配置（devices_info_list_cn.json）里的公开 CDN 直链
 * （AWS S3 中国区，无需鉴权）。下载后写入华为云图同一存储槽位
 * （PodImagePrefs.saveCloudImagesIfLatest），UI 经 preferredImagePath 自动消费。
 */
internal object NothingOfficialImageCache {
    private const val TAG = "HuaweiPods-NothingImage"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val MAX_IMAGE_BYTES = 2_097_152L

    private const val MODEL_ID = "B174"
    private const val DEFAULT_COLOR_ID = "02"
    private const val IMAGE_BASE = "https://platform-watch-china.s3.cn-north-1.amazonaws.com.cn/device_sku/"

    // MAC OUI → colorId；Nothing fastPairId 就是配色的 MAC 前缀。
    private val macPrefixToColorId = mapOf(
        "FC3AAF" to "02",
        "CC3444" to "03",
    )

    // Nothing X devices_info_list_cn.json 里两套配色的官方产品图。
    private val remotePaths = mapOf(
        "02" to mapOf(
            PodImageResource.BOX to "1744270281503_Model=ear (open), Status=Group.png",
            PodImageResource.LEFT to "1744270281692_1730719703745_left.png",
            PodImageResource.RIGHT to "1744270281748_1730719704231_right.png",
        ),
        "03" to mapOf(
            PodImageResource.BOX to "1777349031622_Ear open blue - group.png",
            PodImageResource.LEFT to "1777349031709_Bule left.png",
            PodImageResource.RIGHT to "1777349031790_Bule right.png",
        ),
    )

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NothingOfficialImage").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    // 连接事件触发；已就绪则跳过，失败静默保留内置图兜底。
    fun ensureAsync(context: Context, address: String) {
        if (address.isBlank()) return
        if (!inFlight.compareAndSet(false, true)) return
        executor.execute {
            try {
                ensure(context.applicationContext ?: context, address)
            } catch (error: Throwable) {
                Log.w(TAG, "Nothing official image fetch failed: ${error.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    private fun ensure(context: Context, address: String) {
        val colorId = resolveColorId(address)
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = PodImagePrefs.find(prefs, address)
        if (existing?.cloudModelId == MODEL_ID && existing.cloudSubModelId == colorId) {
            val boxReady = existing.cloudImagePath(PodImageResource.BOX)?.let(::File)?.isFile == true
            if (boxReady) return
        }
        val paths = remotePaths[colorId] ?: return
        val installed = mutableMapOf<PodImageResource, String>()
        paths.forEach { (resource, remotePath) ->
            val bytes = downloadBytes(openConnection(remotePath)) ?: return@forEach
            val destination = File(
                PodImagePrefs.imageDir(context),
                "${prefix(address, colorId)}_${resource.fileSuffix}.png",
            )
            val temporary = File(
                PodImagePrefs.imageDir(context),
                ".${destination.name}.tmp",
            )
            temporary.outputStream().use { output -> output.write(bytes) }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            installed[resource] = destination.absolutePath
        }
        if (installed[PodImageResource.BOX].isNullOrBlank()) {
            Log.w(TAG, "Nothing official images unavailable")
            return
        }
        // 先登记身份再写云图，与华为云图的 IfLatest 语义保持一致。
        PodImagePrefs.recordLatestCloudIdentity(
            prefs = prefs,
            address = address,
            modelId = MODEL_ID,
            subModelId = colorId,
        )
        val committed = PodImagePrefs.saveCloudImagesIfLatest(
            prefs = prefs,
            service = HuaweiPodsApp.xposedService,
            address = address,
            modelId = MODEL_ID,
            subModelId = colorId,
            imagePaths = installed,
        )
        if (committed) {
            PodImageChangeNotifier.notify(context, address)
            Log.i(TAG, "Nothing official images installed for $address")
        }
    }

    // MAC OUI → colorId；未知前缀默认白色。
    private fun resolveColorId(address: String): String {
        val compact = address.replace(":", "").replace("-", "").uppercase()
        val macPrefix = compact.take(6)
        return macPrefixToColorId[macPrefix] ?: DEFAULT_COLOR_ID
    }

    private fun prefix(address: String, colorId: String): String =
        "${address}_${MODEL_ID}_$colorId".replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun openConnection(remotePath: String): HttpURLConnection {
        val encoded = URLEncoder.encode(remotePath, "UTF-8").replace("+", "%20")
        val connection = URL(IMAGE_BASE + encoded).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        return connection
    }

    private fun downloadBytes(connection: HttpURLConnection): ByteArray? = runCatching {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                output.write(chunk, 0, read)
                if (output.size() > MAX_IMAGE_BYTES) return@runCatching null
            }
            output.toByteArray()
        }
    }.onFailure {
        Log.w(TAG, "Nothing image download error: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()
}
