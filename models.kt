package com.ventaxiscorp.hexinspect

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
    data object Loading : Res<Nothing>
    data class Ok<T>(val data: T) : Res<Nothing> as Res<T>
    data class Fail(val message: String) : Res<Nothing>
}
