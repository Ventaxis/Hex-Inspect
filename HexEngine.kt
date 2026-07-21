package com.ventaxiscorp.hexinspect

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.math.pow

// ================================================================================
//  ДАННЫЕ
// ================================================================================

data class DeviceInfo(
    val manufacturer: String, val model: String, val device: String,
    val board: String, val hardware: String, val brand: String,
    val androidVersion: String, val sdkVersion: Int, val codename: String,
    val buildId: String, val buildTime: Long, val fingerprint: String,
    val kernelVersion: String, val uptimeFormatted: String,
    val isEmulator: Boolean, val rootAccess: Boolean, val bootloaderUnlocked: Boolean
)

data class CoreInfo(val index: Int, val curMhz: Int, val minMhz: Int, val maxMhz: Int, val online: Boolean)

data class CpuInfo(
    val logicalCores: Int, val architecture: String, val abis: List<String>,
    val cores: List<CoreInfo>, val loadPercent: Float, val avgTemperature: Float,
    val throttling: Boolean, val governor: String, val isBigLittle: Boolean,
    val clusterCount: Int, val cacheL2Kb: Int, val hardwareName: String,
    val features: List<String>
)

data class GpuInfo(
    val renderer: String, val vendor: String, val glVersion: String,
    val eglVersion: String, val vulkanSupported: Boolean, val vulkanVersion: String,
    val extensionsCount: Int, val extensions: List<String>
)

data class MemoryInfo(
    val totalRam: Long, val availableRam: Long, val usedRam: Long,
    val threshold: Long, val lowMemory: Boolean,
    val totalSwap: Long, val freeSwap: Long, val memType: String
)

data class StorageInfo(
    val internalTotal: Long, val internalAvailable: Long, val internalUsed: Long,
    val cacheTotal: Long, val cacheAvailable: Long,
    val filesystem: String
)

data class BatteryInfo(
    val levelPercent: Int, val capacityPercent: Int, val temperature: Float,
    val voltage: Float, val currentNowMa: Float, val instantPowerW: Float,
    val energyCounterUwh: Long, val health: String, val status: String,
    val plugged: String, val technology: String, val cycleCount: Int
)

data class NetworkInfo(
    val type: String, val subtype: String, val downKbps: Int, val upKbps: Int,
    val carrier: String, val ipAddress: String, val macAddress: String,
    val isVpnActive: Boolean, val isMetered: Boolean
)

data class DisplayModeInfo(val widthPx: Int, val heightPx: Int, val refreshRate: Float)

data class DisplayInfo(
    val widthPx: Int, val heightPx: Int, val density: Float, val densityDpi: Int,
    val diagonalInches: Double, val refreshRate: Float, val minRefreshRate: Float,
    val maxRefreshRate: Float, val hdrSupported: Boolean, val hdrTypes: List<String>,
    val wideColorGamut: Boolean, val availableModes: List<DisplayModeInfo>
)

data class CameraInfo(
    val id: String, val facing: String, val megapixels: Float, val resolution: String,
    val sensorSizeMm: String, val focalLengthsMm: List<Float>, val aperture: Float,
    val hasFlash: Boolean, val hasOis: Boolean, val hasAutofocus: Boolean,
    val isoRange: String, val hardwareLevel: String
)

data class SensorLiveInfo(
    val name: String, val type: Int, val vendor: String, val power: Float,
    val maxRange: Float, val resolution: Float, val values: List<Float>
)

data class BenchResult(val name: String, val score: Double, val unit: String)

data class ThrottleSample(val elapsedSec: Int, val avgFreqMhz: Int, val tempC: Float, val throttled: Boolean)
data class ThrottleData(val history: List<ThrottleSample>, val currentlyThrottled: Boolean)

data class FullReport(
    val device: DeviceInfo?, val cpu: CpuInfo?, val gpu: GpuInfo?, val memory: MemoryInfo?,
    val storage: StorageInfo?, val battery: BatteryInfo?, val network: NetworkInfo?,
    val display: DisplayInfo?, val cameras: List<CameraInfo>, val sensors: List<SensorLiveInfo>
)

sealed interface Res<out T> {
    object Loading : Res<Nothing>
    data class Ok<T>(val data: T) : Res<Nothing> as Res<T>
    data class Fail(val message: String) : Res<Nothing>
}

// ================================================================================
//  ENGINE — сбор данных. Всё I/O на Dispatchers.IO, кэши под Mutex.
// ================================================================================

private const val TAG = "HexEngine"

class HexEngine(private val ctx: Context) {

    private val deviceMutex = Mutex()
    private var deviceCache: DeviceInfo? = null

    private val cpuStaticMutex = Mutex()
    private var cpuStaticCache: Triple<Int, String, List<String>>? = null // cores, arch, abis

    private val gpuMutex = Mutex()
    private var gpuCache: GpuInfo? = null

    private val displayMutex = Mutex()
    private var displayCache: DisplayInfo? = null

    private val cameraMutex = Mutex()
    private var cameraCache: List<CameraInfo>? = null

    private val sensorMutex = Mutex()
    private var sensorCache: List<SensorLiveInfo>? = null

    // ---------- DEVICE ----------

    suspend fun getDevice(): DeviceInfo = withContext(Dispatchers.IO) {
        deviceMutex.withLock {
            deviceCache ?: run {
                val uptimeMs = android.os.SystemClock.elapsedRealtime()
                DeviceInfo(
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    device = Build.DEVICE,
                    board = Build.BOARD,
                    hardware = Build.HARDWARE,
                    brand = Build.BRAND,
                    androidVersion = Build.VERSION.RELEASE,
                    sdkVersion = Build.VERSION.SDK_INT,
                    codename = sdkToCodename(Build.VERSION.SDK_INT),
                    buildId = Build.DISPLAY,
                    buildTime = Build.TIME,
                    fingerprint = Build.FINGERPRINT,
                    kernelVersion = readKernelVersion(),
                    uptimeFormatted = formatUptime(uptimeMs),
                    isEmulator = Build.FINGERPRINT.startsWith("generic") ||
                            Build.FINGERPRINT.startsWith("unknown") ||
                            Build.MODEL.contains("google_sdk") ||
                            Build.MODEL.contains("Emulator") ||
                            Build.MODEL.contains("Android SDK built for x86"),
                    rootAccess = checkRoot(),
                    bootloaderUnlocked = readStringFromFile("/sys/class/android_usb/android0/f_rndis/manufacturer") != null
                ).also { deviceCache = it }
            }
        }
    }

    private fun sdkToCodename(sdk: Int): String = when (sdk) {
        34 -> "Android 14 (UpsideDownCake)"
        33 -> "Android 13 (Tiramisu)"
        32, 31 -> "Android 12 (Snow Cone)"
        30 -> "Android 11 (Red Velvet Cake)"
        29 -> "Android 10 (Quince Tart)"
        28 -> "Android 9 (Pie)"
        27, 26 -> "Android 8 (Oreo)"
        else -> "SDK $sdk"
    }

    private fun formatUptime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%dч %02dм %02dс".format(h, m, s)
    }

    private fun readKernelVersion(): String = runCatching {
        File("/proc/version").readText().trim().substringBefore(" (")
    }.getOrDefault(System.getProperty("os.version") ?: "неизвестно")

    private fun checkRoot(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
            "/su/bin/su"
        )
        if (paths.any { File(it).exists() }) return true
        return runCatching {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val exit = p.waitFor()
            exit == 0
        }.getOrDefault(false)
    }

    // ---------- CPU ----------

    suspend fun getCpu(): CpuInfo = withContext(Dispatchers.IO) {
        val (staticCores, arch, abis) = cpuStaticMutex.withLock {
            cpuStaticCache ?: Triple(
                Runtime.getRuntime().availableProcessors(),
                System.getProperty("os.arch") ?: "unknown",
                Build.SUPPORTED_ABIS.toList()
            ).also { cpuStaticCache = it }
        }

        val cores = (0 until staticCores).map { i ->
            val curFreq = readIntFromFile("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
            val online = curFreq != null
            CoreInfo(
                index = i,
                curMhz = (curFreq ?: 0) / 1000,
                minMhz = (readIntFromFile("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq") ?: 0) / 1000,
                maxMhz = (readIntFromFile("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") ?: 0) / 1000,
                online = online
            )
        }

        val distinctMaxFreqs = cores.map { it.maxMhz }.filter { it > 0 }.distinct()
        val isBigLittle = distinctMaxFreqs.size > 1

        val cacheL2 = readIntFromFile("/sys/devices/system/cpu/cpu0/cache/index2/size")
            ?: parseCacheSizeKb(readStringFromFile("/sys/devices/system/cpu/cpu0/cache/index2/size"))

        CpuInfo(
            logicalCores = staticCores,
            architecture = arch,
            abis = abis,
            cores = cores,
            loadPercent = getCpuLoadInternal(),
            avgTemperature = getCpuTemperatureInternal(),
            throttling = isThrottlingInternal(),
            governor = readStringFromFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor") ?: "unknown",
            isBigLittle = isBigLittle,
            clusterCount = if (distinctMaxFreqs.isEmpty()) 1 else distinctMaxFreqs.size,
            cacheL2Kb = cacheL2 ?: 0,
            hardwareName = readCpuHardwareName(),
            features = readCpuFeatures()
        )
    }

    private fun parseCacheSizeKb(raw: String?): Int? {
        if (raw == null) return null
        return raw.trim().removeSuffix("K").toIntOrNull()
    }

    suspend fun getCpuLoadInternal(): Float {
        var idle1 = 0L; var total1 = 0L
        readProcStat()?.let { (idle, total) -> idle1 = idle; total1 = total } ?: return 0f
        delay(120)
        var idle2 = 0L; var total2 = 0L
        readProcStat()?.let { (idle, total) -> idle2 = idle; total2 = total } ?: return 0f
        val diffIdle = idle2 - idle1
        val diffTotal = total2 - total1
        return if (diffTotal > 0) (1f - diffIdle.toFloat() / diffTotal).coerceIn(0f, 1f) else 0f
    }

    private fun readProcStat(): Pair<Long, Long>? = runCatching {
        BufferedReader(InputStreamReader(File("/proc/stat").inputStream())).use { reader ->
            reader.readLine()?.takeIf { it.startsWith("cpu ") }?.let { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 8) {
                    val nums = parts.drop(1).take(7).map { it.toLong() }
                    val idle = nums[3] + nums[4]
                    val total = nums.sum()
                    idle to total
                } else null
            }
        }
    }.getOrNull()

    fun getCpuTemperatureInternal(): Float {
        val paths = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp"
        )
        for (path in paths) {
            val v = readIntFromFile(path) ?: continue
            return if (v > 1000) v / 1000f else v.toFloat()
        }
        return 0f
    }

    fun isThrottlingInternal(): Boolean {
        val dir = File("/sys/class/thermal/thermal_message")
        if (!dir.exists() || !dir.isDirectory) return false
        return dir.listFiles()?.any { f ->
            f.name.startsWith("cpu") && runCatching { f.readText().trim() }.getOrNull()
                ?.let { it == "1" || it.lowercase().contains("throttle") } ?: false
        } ?: false
    }

    suspend fun getCurrentFrequencies(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        return (0 until cores).mapNotNull { i ->
            readIntFromFile("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.div(1000)
        }
    }

    private fun readCpuHardwareName(): String = runCatching {
        BufferedReader(InputStreamReader(File("/proc/cpuinfo").inputStream())).use { r ->
            r.lineSequence().firstOrNull { it.startsWith("Hardware") }
                ?.substringAfter(":")?.trim() ?: Build.HARDWARE
        }
    }.getOrDefault(Build.HARDWARE)

    private fun readCpuFeatures(): List<String> = runCatching {
        BufferedReader(InputStreamReader(File("/proc/cpuinfo").inputStream())).use { r ->
            r.lineSequence().firstOrNull { it.startsWith("Features") || it.startsWith("flags") }
                ?.substringAfter(":")?.trim()?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        }
    }.getOrDefault(emptyList())

    private fun readIntFromFile(path: String): Int? = runCatching {
        BufferedReader(InputStreamReader(File(path).inputStream())).use { it.readText().trim().toIntOrNull() }
    }.getOrNull()

    private fun readStringFromFile(path: String): String? = runCatching {
        BufferedReader(InputStreamReader(File(path).inputStream())).use { it.readText().trim() }
    }.getOrNull()

    // ---------- GPU ----------

    suspend fun getGpu(): GpuInfo = withContext(Dispatchers.IO) {
        gpuMutex.withLock {
            gpuCache ?: queryGpu().also { gpuCache = it }
        }
    }

    private fun queryGpu(): GpuInfo {
        val fallback = GpuInfo("Неизвестно", "Неизвестно", "Неизвестно", "N/A", false, "N/A", 0, emptyList())
        var eglDisplay: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var eglSurface: EGLSurface? = null
        return try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return fallback
            val versionArr = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, versionArr, 0, versionArr, 1)) return fallback
            val eglVersionStr = "${versionArr[0]}.${versionArr[1]}"

            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) return fallback
            val eglConfig = configs[0]
            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return fallback
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return fallback
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return fallback

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown"
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unknown"
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: "Unknown"
            val extensions = (GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: "")
                .split(" ").filter { it.isNotBlank() }

            val vulkanSupported = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
            val vulkanVersion = if (vulkanSupported) {
                runCatching {
                    val fi = ctx.packageManager.getSystemAvailableFeatures().firstOrNull {
                        it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
                    }
                    val raw = fi?.version ?: 0
                    "${raw shr 22}.${(raw shr 12) and 0x3FF}.${raw and 0xFFF}"
                }.getOrDefault("поддерживается")
            } else "не поддерживается"

            GpuInfo(renderer, vendor, version, eglVersionStr, vulkanSupported, vulkanVersion, extensions.size, extensions)
        } catch (e: Exception) {
            Log.e(TAG, "GPU query failed", e)
            fallback
        } finally {
            if (eglSurface != null && eglDisplay != null) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != null && eglDisplay != null) EGL14.eglDestroyContext(eglDisplay, eglContext)
            if (eglDisplay != null) EGL14.eglTerminate(eglDisplay)
        }
    }

    // ---------- MEMORY (динамика — без кэша) ----------

    suspend fun getMemory(): MemoryInfo = withContext(Dispatchers.IO) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        var totalSwap = 0L; var freeSwap = 0L; var memType = "неизвестно"
        runCatching {
            File("/proc/meminfo").readLines().forEach { line ->
                when {
                    line.startsWith("SwapTotal:") -> totalSwap = line.filter { it.isDigit() }.toLongOrNull()?.times(1024) ?: 0L
                    line.startsWith("SwapFree:") -> freeSwap = line.filter { it.isDigit() }.toLongOrNull()?.times(1024) ?: 0L
                }
            }
        }
        // LPDDR тип напрямую из userspace недоступен без root — честно помечаем это
        memType = if (Build.VERSION.SDK_INT >= 31) "LPDDR (точный тип недоступен без root)" else "недоступно"

        MemoryInfo(
            totalRam = mi.totalMem,
            availableRam = mi.availMem,
            usedRam = mi.totalMem - mi.availMem,
            threshold = mi.threshold,
            lowMemory = mi.lowMemory,
            totalSwap = totalSwap,
            freeSwap = freeSwap,
            memType = memType
        )
    }

    // ---------- STORAGE (динамика — без кэша) ----------

    suspend fun getStorage(): StorageInfo = withContext(Dispatchers.IO) {
        val internal = StatFs(Environment.getDataDirectory().path)
        val internalTotal = internal.blockCountLong * internal.blockSizeLong
        val internalAvail = internal.availableBlocksLong * internal.blockSizeLong

        val cacheDir = ctx.cacheDir
        val cache = StatFs(cacheDir.path)
        val cacheTotal = cache.blockCountLong * cache.blockSizeLong
        val cacheAvail = cache.availableBlocksLong * cache.blockSizeLong

        val fs = runCatching {
            File("/proc/mounts").readLines().firstOrNull { it.contains(" /data ") }
                ?.split(" ")?.getOrNull(2) ?: "неизвестно"
        }.getOrDefault("неизвестно")

        StorageInfo(
            internalTotal = internalTotal,
            internalAvailable = internalAvail,
            internalUsed = internalTotal - internalAvail,
            cacheTotal = cacheTotal,
            cacheAvailable = cacheAvail,
            filesystem = fs
        )
    }

    // ---------- BATTERY ----------

    suspend fun getBattery(): BatteryInfo = withContext(Dispatchers.IO) {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (scale > 0) (level.toFloat() / scale * 100).toInt() else 0
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val voltage = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "неизвестно"

        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val current = bm.getBatteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000f
        val energy = bm.getBatteryProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val capacity = bm.getBatteryProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cycleCount = if (Build.VERSION.SDK_INT >= 34) {
            runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CYCLE_COUNT) }.getOrDefault(-1)
        } else -1

        val instantPowerW = kotlin.math.abs(voltage * current) / 1000f

        BatteryInfo(
            levelPercent = percent, capacityPercent = capacity, temperature = temp,
            voltage = voltage, currentNowMa = current, instantPowerW = instantPowerW,
            energyCounterUwh = energy, cycleCount = cycleCount,
            health = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Хорошее"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Перегрев"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Мертва"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Перенапряжение"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Сбой"
                BatteryManager.BATTERY_HEALTH_COLD -> "Холодная"
                else -> "Неизвестно"
            },
            status = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Заряжается"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Разряжается"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Не заряжается"
                BatteryManager.BATTERY_STATUS_FULL -> "Полностью заряжена"
                else -> "Неизвестно"
            },
            plugged = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "Сеть 220В"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Беспроводная зарядка"
                else -> "Не подключена"
            },
            technology = technology
        )
    }

    // ---------- NETWORK ----------

    suspend fun getNetwork(): NetworkInfo = withContext(Dispatchers.IO) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork)
        var type = "Нет соединения"; var subtype = "—"; var down = 0; var up = 0
        var carrier = "—"; var isVpn = false; var isMetered = true

        nc?.let {
            isVpn = it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            isMetered = !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            when {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> { type = "Wi-Fi"; subtype = "802.11" }
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    type = "Мобильная сеть"
                    runCatching {
                        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                        carrier = tm.networkOperatorName.ifBlank { "неизвестно" }
                        subtype = networkGenFromType(tm.networkType)
                    }.onFailure { e -> Log.w(TAG, "carrier lookup", e) }
                }
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> { type = "Ethernet"; subtype = "проводное" }
                it.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> { type = "Bluetooth" }
            }
            down = it.linkDownstreamBandwidthKbps
            up = it.linkUpstreamBandwidthKbps
        }

        val ip = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .flatMap { Collections.list(it.inetAddresses) }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress ?: "—"
        }.getOrDefault("—")

        val mac = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .firstOrNull { it.name.equals("wlan0", ignoreCase = true) }
                ?.hardwareAddress?.joinToString(":") { "%02X".format(it) } ?: "скрыт системой"
        }.getOrDefault("скрыт системой")

        NetworkInfo(type, subtype, down, up, carrier, ip, mac, isVpn, isMetered)
    }

    private fun networkGenFromType(type: Int): String = when (type) {
        android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
        android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
        android.telephony.TelephonyManager.NETWORK_TYPE_HSPA,
        android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA"
        android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
        android.telephony.TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
        android.telephony.TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
        else -> "неизвестно"
    }

    // ---------- DISPLAY ----------

    suspend fun getDisplay(): DisplayInfo = withContext(Dispatchers.IO) {
        displayMutex.withLock {
            displayCache ?: run {
                val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display?.getRealMetrics(metrics)

                val widthIn = metrics.widthPixels / metrics.xdpi
                val heightIn = metrics.heightPixels / metrics.ydpi
                val diagonal = kotlin.math.sqrt((widthIn * widthIn + heightIn * heightIn).toDouble())

                val modes = display?.supportedModes?.map {
                    DisplayModeInfo(it.physicalWidth, it.physicalHeight, it.refreshRate)
                }?.distinctBy { "${it.widthPx}x${it.heightPx}@${it.refreshRate}" } ?: emptyList()

                val refreshRates = modes.map { it.refreshRate }
                val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes?.map { hdrTypeName(it) } ?: emptyList()

                DisplayInfo(
                    widthPx = metrics.widthPixels,
                    heightPx = metrics.heightPixels,
                    density = metrics.density,
                    densityDpi = metrics.densityDpi,
                    diagonalInches = diagonal,
                    refreshRate = display?.refreshRate ?: 60f,
                    minRefreshRate = refreshRates.minOrNull() ?: 60f,
                    maxRefreshRate = refreshRates.maxOrNull() ?: 60f,
                    hdrSupported = hdrTypes.isNotEmpty(),
                    hdrTypes = hdrTypes,
                    wideColorGamut = ctx.resources.configuration.isScreenWideColorGamut,
                    availableModes = modes.sortedByDescending { it.refreshRate }
                )
            }.also { displayCache = it }
        }
    }

    private fun hdrTypeName(t: Int): String = when (t) {
        android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "HDR тип $t"
    }

    // ---------- CAMERA ----------

    suspend fun getCameras(): List<CameraInfo> = withContext(Dispatchers.IO) {
        cameraMutex.withLock {
            cameraCache ?: run {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                runCatching {
                    cm.cameraIdList.mapNotNull { id ->
                        val c = cm.getCameraCharacteristics(id)
                        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                            CameraCharacteristics.LENS_FACING_FRONT -> "Фронтальная"
                            CameraCharacteristics.LENS_FACING_BACK -> "Основная"
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "Внешняя"
                            else -> "Неизвестно"
                        }
                        val resolution = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        val mp = if (resolution != null) resolution.width() * resolution.height() / 1_000_000f else 0f
                        val sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        val capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                        val hasOis = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW).let {
                            (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.size ?: 0) > 1
                        }
                        val hardwareLevel = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                            else -> "неизвестно"
                        }
                        CameraInfo(
                            id = id, facing = facing, megapixels = mp,
                            resolution = resolution?.let { "${it.width()}×${it.height()}" } ?: "—",
                            sensorSizeMm = sensorSize?.let { "%.2f×%.2f мм".format(it.width, it.height) } ?: "—",
                            focalLengthsMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
                            aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f,
                            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
                            hasOis = hasOis,
                            hasAutofocus = (c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f,
                            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { "${it.lower}–${it.upper}" } ?: "—",
                            hardwareLevel = hardwareLevel
                        )
                    }
                }.getOrDefault(emptyList())
            }.also { cameraCache = it }
        }
    }

    // ---------- SENSORS ----------

    suspend fun getSensors(): List<SensorLiveInfo> = withContext(Dispatchers.IO) {
        sensorMutex.withLock {
            sensorCache ?: run {
                val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                sm.getSensorList(Sensor.TYPE_ALL).map {
                    SensorLiveInfo(it.name, it.type, it.vendor, it.power, it.maximumRange, it.resolution, emptyList())
                }
            }.also { sensorCache = it }
        }
    }

    fun updateSensorValues(current: List<SensorLiveInfo>, type: Int, values: List<Float>): List<SensorLiveInfo> =
        current.map { if (it.type == type) it.copy(values = values) else it }

    // ---------- BENCHMARKS ----------

    private fun median(scores: List<Double>): Double =
        scores.sorted().let { if (it.size % 2 == 0) (it[it.size / 2 - 1] + it[it.size / 2]) / 2 else it[it.size / 2] }

    suspend fun benchmarkCpu(progress: suspend (Float) -> Unit): BenchResult = withContext(Dispatchers.IO) {
        val warmup = 2; val measure = 4; val total = warmup + measure
        val scores = mutableListOf<Double>()
        for (i in 0 until total) {
            progress(i.toFloat() / total)
            val start = System.nanoTime()
            var acc = 0L
            for (j in 0 until 10_000_000) {
                acc += (j * 7 + 13) % 1024
                if (j % 1000 == 0) acc = acc xor (j.toLong() shl 3)
            }
            val elapsed = System.nanoTime() - start
            if (i >= warmup) scores.add(10_000_000.0 / (elapsed / 1e9) / 1_000_000.0)
            coroutineContext.ensureActive()
        }
        BenchResult("CPU", median(scores), "Моп/с")
    }

    suspend fun benchmarkMemory(progress: suspend (Float) -> Unit): BenchResult = withContext(Dispatchers.IO) {
        val warmup = 2; val measure = 4; val total = warmup + measure
        val scores = mutableListOf<Double>()
        val size = 50 * 1024 * 1024
        for (i in 0 until total) {
            progress(i.toFloat() / total)
            val start = System.nanoTime()
            val buf = ByteBuffer.allocateDirect(size)
            buf.put(ByteArray(size) { (it % 256).toByte() })
            buf.flip()
            var sum = 0L
            while (buf.hasRemaining()) sum += buf.get()
            val elapsed = System.nanoTime() - start
            if (i >= warmup) scores.add(size / (elapsed / 1e9) / (1024.0 * 1024.0))
            coroutineContext.ensureActive()
        }
        BenchResult("Память", median(scores), "МБ/с")
    }

    suspend fun benchmarkStorage(progress: suspend (Float) -> Unit): BenchResult = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "hex_bench.tmp")
        val size = 150 * 1024 * 1024
        val bufferSize = 4 * 1024 * 1024
        val warmup = 1; val measure = 3; val total = warmup + measure
        val scores = mutableListOf<Double>()
        try {
            for (i in 0 until total) {
                progress(i.toFloat() / total)
                val startWrite = System.nanoTime()
                FileOutputStream(file).use { fos ->
                    val channel = fos.channel
                    val bb = ByteBuffer.allocateDirect(bufferSize)
                    repeat(size / bufferSize) {
                        bb.position(0); bb.limit(bufferSize)
                        channel.write(bb)
                    }
                }
                val writeTime = System.nanoTime() - startWrite
                val startRead = System.nanoTime()
                file.inputStream().use { input ->
                    val buffer = ByteArray(bufferSize)
                    while (input.read(buffer) != -1) { /* прочитано */ }
                }
                val readTime = System.nanoTime() - startRead
                val totalTime = writeTime + readTime
                if (i >= warmup) scores.add((size.toDouble() * 2) / (totalTime / 1e9) / (1024.0 * 1024.0))
                coroutineContext.ensureActive()
            }
        } finally {
            file.delete()
        }
        BenchResult("Хранилище", median(scores), "МБ/с")
    }

    // ---------- EXPORT ----------

    suspend fun exportReport(report: FullReport, format: String): File? = withContext(Dispatchers.IO) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "hexinspect_$ts.$format"
            val file = File(ctx.getExternalFilesDir(null), fileName)
            val content = when (format) {
                "txt" -> buildTxtReport(report, ts)
                "json" -> buildJsonReport(report)
                else -> return@withContext null
            }
            file.writeBytes(content.toByteArray())
            file
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            null
        }
    }

    private fun buildTxtReport(r: FullReport, ts: String): String = buildString {
        appendLine("═══════════════════════════════")
        appendLine("   HEXINSPECT — ОТЧЁТ О СИСТЕМЕ")
        appendLine("═══════════════════════════════")
        appendLine("Сгенерировано: $ts")
        appendLine()
        r.device?.let {
            appendLine("── УСТРОЙСТВО ──")
            appendLine("Производитель: ${it.manufacturer}")
            appendLine("Модель: ${it.model} (${it.device})")
            appendLine("Плата: ${it.board} / ${it.hardware}")
            appendLine("Android: ${it.androidVersion} — ${it.codename}, SDK ${it.sdkVersion}")
            appendLine("Ядро: ${it.kernelVersion}")
            appendLine("Аптайм: ${it.uptimeFormatted}")
            appendLine("Root: ${if (it.rootAccess) "да" else "нет"}")
            appendLine()
        }
        r.cpu?.let {
            appendLine("── ПРОЦЕССОР ──")
            appendLine("${it.hardwareName}, ${it.logicalCores} ядер (${it.clusterCount} кластера${if (it.isBigLittle) ", big.LITTLE" else ""})")
            appendLine("Архитектура: ${it.architecture}, ABI: ${it.abis.joinToString()}")
            appendLine("Загрузка: ${(it.loadPercent * 100).toInt()}%, температура: ${it.avgTemperature}°C")
            appendLine("Governor: ${it.governor}, троттлинг: ${if (it.throttling) "да" else "нет"}")
            it.cores.forEach { c -> appendLine("  Ядро ${c.index}: ${c.curMhz} МГц (${c.minMhz}–${c.maxMhz})") }
            appendLine()
        }
        r.gpu?.let {
            appendLine("── ВИДЕОЯДРО ──")
            appendLine("${it.renderer} (${it.vendor})")
            appendLine("OpenGL ES: ${it.glVersion}, EGL: ${it.eglVersion}")
            appendLine("Vulkan: ${if (it.vulkanSupported) it.vulkanVersion else "нет"}")
            appendLine()
        }
        r.memory?.let {
            appendLine("── ПАМЯТЬ ──")
            appendLine("Всего: ${formatBytes(it.totalRam)}, занято: ${formatBytes(it.usedRam)}")
            appendLine("Swap: ${formatBytes(it.totalSwap)} (свободно ${formatBytes(it.freeSwap)})")
            appendLine()
        }
        r.storage?.let {
            appendLine("── ХРАНИЛИЩЕ ──")
            appendLine("Всего: ${formatBytes(it.internalTotal)}, занято: ${formatBytes(it.internalUsed)}")
            appendLine("Файловая система: ${it.filesystem}")
            appendLine()
        }
        r.battery?.let {
            appendLine("── БАТАРЕЯ ──")
            appendLine("Заряд: ${it.levelPercent}%, статус: ${it.status}")
            appendLine("Технология: ${it.technology}, здоровье: ${it.health}")
            appendLine("Напряжение: ${it.voltage} В, ток: ${it.currentNowMa} мА, мощность: %.2f Вт".format(it.instantPowerW))
            appendLine()
        }
        r.network?.let {
            appendLine("── СЕТЬ ──")
            appendLine("${it.type} (${it.subtype}), IP: ${it.ipAddress}")
            appendLine("Оператор: ${it.carrier}, VPN: ${if (it.isVpnActive) "активен" else "нет"}")
            appendLine()
        }
        r.display?.let {
            appendLine("── ЭКРАН ──")
            appendLine("${it.widthPx}×${it.heightPx}, %.1f\" диагональ".format(it.diagonalInches))
            appendLine("Частота: ${it.refreshRate} Гц (диапазон ${it.minRefreshRate}–${it.maxRefreshRate})")
            appendLine("HDR: ${if (it.hdrSupported) it.hdrTypes.joinToString() else "нет"}")
            appendLine()
        }
        appendLine("── КАМЕРЫ (${r.cameras.size}) ──")
        r.cameras.forEach { appendLine("  ${it.id} [${it.facing}]: ${it.resolution}, %.1f МП, f/${it.aperture}".format(it.megapixels)) }
        appendLine()
        appendLine("── ДАТЧИКИ (${r.sensors.size}) ──")
        r.sensors.forEach { appendLine("  ${it.name} (${it.vendor})") }
    }

    private fun buildJsonReport(r: FullReport): String = buildString {
        append("{")
        append("\"generatedAt\":\"${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())}\",")
        r.device?.let {
            append("\"device\":{\"manufacturer\":\"${it.manufacturer}\",\"model\":\"${it.model}\",")
            append("\"android\":\"${it.androidVersion}\",\"sdk\":${it.sdkVersion},\"root\":${it.rootAccess}},")
        }
        r.cpu?.let {
            append("\"cpu\":{\"cores\":${it.logicalCores},\"arch\":\"${it.architecture}\",")
            append("\"load\":${it.loadPercent},\"temp\":${it.avgTemperature},\"throttling\":${it.throttling}},")
        }
        r.gpu?.let { append("\"gpu\":{\"renderer\":\"${it.renderer}\",\"vulkan\":${it.vulkanSupported}},") }
        r.memory?.let { append("\"memory\":{\"total\":${it.totalRam},\"used\":${it.usedRam}},") }
        r.storage?.let { append("\"storage\":{\"total\":${it.internalTotal},\"used\":${it.internalUsed}},") }
        r.battery?.let { append("\"battery\":{\"level\":${it.levelPercent},\"status\":\"${it.status}\",\"health\":\"${it.health}\"},") }
        append("\"camerasCount\":${r.cameras.size},")
        append("\"sensorsCount\":${r.sensors.size}")
        append("}")
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    val exp = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return "%.2f %s".format(bytes / 1024.0.pow(exp), units[exp])
}

// ================================================================================
//  VIEWMODEL
// ================================================================================

class HexViewModel(app: android.app.Application, private val engine: HexEngine) : AndroidViewModel(app) {

    private val exHandler = CoroutineExceptionHandler { _, e -> Log.e("HexViewModel", "unhandled", e) }

    val device = MutableStateFlow<Res<DeviceInfo>>(Res.Loading)
    val cpu = MutableStateFlow<Res<CpuInfo>>(Res.Loading)
    val gpu = MutableStateFlow<Res<GpuInfo>>(Res.Loading)
    val memory = MutableStateFlow<Res<MemoryInfo>>(Res.Loading)
    val storage = MutableStateFlow<Res<StorageInfo>>(Res.Loading)
    val battery = MutableStateFlow<Res<BatteryInfo>>(Res.Loading)
    val network = MutableStateFlow<Res<NetworkInfo>>(Res.Loading)
    val display = MutableStateFlow<Res<DisplayInfo>>(Res.Loading)
    val cameras = MutableStateFlow<Res<List<CameraInfo>>>(Res.Loading)
    val sensors = MutableStateFlow<Res<List<SensorLiveInfo>>>(Res.Loading)

    val isRefreshing = MutableStateFlow(false)

    val benchCpu = MutableStateFlow<BenchResult?>(null)
    val benchMemory = MutableStateFlow<BenchResult?>(null)
    val benchStorage = MutableStateFlow<BenchResult?>(null)
    val benchProgress = MutableStateFlow(0f)
    val isBenchRunning = MutableStateFlow(false)

    val throttleData = MutableStateFlow<ThrottleData?>(null)
    private val throttleActive = AtomicBoolean(false)

    private var lastDynamicRefresh = 0L

    init { refreshAll() }

    private suspend inline fun <T> load(flow: MutableStateFlow<Res<T>>, crossinline block: suspend () -> T) {
        try {
            flow.value = Res.Ok(block())
        } catch (e: Exception) {
            Log.e("HexViewModel", "секция упала", e)
            flow.value = Res.Fail(e.message ?: "неизвестная ошибка")
        }
    }

    fun refreshAll() {
        if (isRefreshing.value) return
        viewModelScope.launch(exHandler) {
            isRefreshing.value = true
            supervisorScope {
                listOf(
                    async { load(device) { engine.getDevice() } },
                    async { load(cpu) { engine.getCpu() } },
                    async { load(gpu) { engine.getGpu() } },
                    async { load(memory) { engine.getMemory() } },
                    async { load(storage) { engine.getStorage() } },
                    async { load(battery) { engine.getBattery() } },
                    async { load(network) { engine.getNetwork() } },
                    async { load(display) { engine.getDisplay() } },
                    async { load(cameras) { engine.getCameras() } },
                    async { load(sensors) { engine.getSensors() } }
                ).awaitAll()
            }
            isRefreshing.value = false
        }
    }

    fun refreshDynamic() {
        val now = System.currentTimeMillis()
        if (now - lastDynamicRefresh < 1200) return
        lastDynamicRefresh = now
        viewModelScope.launch(exHandler) {
            supervisorScope {
                listOf(
                    async { load(cpu) { engine.getCpu() } },
                    async { load(battery) { engine.getBattery() } },
                    async { load(memory) { engine.getMemory() } },
                    async { load(storage) { engine.getStorage() } },
                    async { load(network) { engine.getNetwork() } }
                ).awaitAll()
            }
        }
    }

    fun runCpuBenchmark() = runBenchmark { engine.benchmarkCpu { p -> benchProgress.value = p } }.also { benchCpu.value = it }
    private inline fun runBenchmark(crossinline block: suspend () -> BenchResult): BenchResult? = null // заглушка типа не используется — см. ниже реальные функции

    fun startCpuBenchmark() {
        if (isBenchRunning.value) return
        viewModelScope.launch(exHandler) {
            isBenchRunning.value = true; benchProgress.value = 0f
            try { benchCpu.value = engine.benchmarkCpu { benchProgress.value = it } }
            finally { isBenchRunning.value = false; benchProgress.value = 0f }
        }
    }

    fun startMemoryBenchmark() {
        if (isBenchRunning.value) return
        viewModelScope.launch(exHandler) {
            isBenchRunning.value = true; benchProgress.value = 0f
            try { benchMemory.value = engine.benchmarkMemory { benchProgress.value = it } }
            finally { isBenchRunning.value = false; benchProgress.value = 0f }
        }
    }

    fun startStorageBenchmark() {
        if (isBenchRunning.value) return
        viewModelScope.launch(exHandler) {
            isBenchRunning.value = true; benchProgress.value = 0f
            try { benchStorage.value = engine.benchmarkStorage { benchProgress.value = it } }
            finally { isBenchRunning.value = false; benchProgress.value = 0f }
        }
    }

    fun startThrottleTest() {
        if (!throttleActive.compareAndSet(false, true)) return
        viewModelScope.launch(exHandler) {
            val history = mutableListOf<ThrottleSample>()
            var elapsed = 0
            while (throttleActive.get() && coroutineContext.isActive) {
                delay(1000)
                elapsed++
                val freqs = engine.getCurrentFrequencies()
                val avg = if (freqs.isNotEmpty()) freqs.average().toInt() else 0
                val temp = engine.getCpuTemperatureInternal()
                val throttled = engine.isThrottlingInternal()
                history.add(ThrottleSample(elapsed, avg, temp, throttled))
                throttleData.value = ThrottleData(history.toList(), throttled)
            }
        }
    }

    fun stopThrottleTest() { throttleActive.set(false) }

    suspend fun export(format: String): File? = withContext(Dispatchers.IO) {
        engine.exportReport(
            FullReport(
                device = (device.value as? Res.Ok)?.data,
                cpu = (cpu.value as? Res.Ok)?.data,
                gpu = (gpu.value as? Res.Ok)?.data,
                memory = (memory.value as? Res.Ok)?.data,
                storage = (storage.value as? Res.Ok)?.data,
                battery = (battery.value as? Res.Ok)?.data,
                network = (network.value as? Res.Ok)?.data,
                display = (display.value as? Res.Ok)?.data,
                cameras = (cameras.value as? Res.Ok)?.data ?: emptyList(),
                sensors = (sensors.value as? Res.Ok)?.data ?: emptyList()
            ),
            format
        )
    }

    // ---------- Живые датчики ----------

    private var listener: SensorEventListener? = null
    private var registered = false
    private val sensorManager by lazy { getApplication<android.app.Application>().getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    private val monitoredTypes = listOf(
        Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY, Sensor.TYPE_PRESSURE,
        Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY
    )

    fun startSensorUpdates() {
        if (registered) return
        val l = object : SensorEventListener {
            private var last = 0L
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val now = System.currentTimeMillis()
                if (now - last < 150) return
                last = now
                sensors.update { st ->
                    if (st is Res.Ok) Res.Ok(engine.updateSensorValues(st.data, event.sensor.type, event.values.toList()))
                    else st
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        listener = l
        monitoredTypes.forEach { t -> sensorManager.getDefaultSensor(t)?.let { sensorManager.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) } }
        registered = true
    }

    fun stopSensorUpdates() {
        if (!registered) return
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null; registered = false
    }

    override fun onCleared() {
        super.onCleared()
        stopSensorUpdates()
        stopThrottleTest()
    }

    companion object {
        fun factory(app: android.app.Application, engine: HexEngine): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = HexViewModel(app, engine) as T
            }
    }
}
