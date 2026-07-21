package com.ventaxiscorp.hexinspect

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HexExport {
    private const val TAG = "HexExport"

    suspend fun exportReport(ctx: Context, report: FullReport, format: String): File? = withContext(Dispatchers.IO) {
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
            Log.e(TAG, "Export failed", e)
            null
        }
    }

    private fun buildTxtReport(r: FullReport, ts: String): String = buildString {
        appendLine("═══════════════════════════════")
        appendLine("   HEXINSPECT — ОТЧЁТ О СИСТЕМЕ")
        appendLine("═══════════════════════════════")
        appendLine("Сгенерировано: $ts\n")
        r.device?.let {
            appendLine("── УСТРОЙСТВО ──")
            appendLine("Производитель: ${it.manufacturer}\nМодель: ${it.model} (${it.device})")
            appendLine("Плата: ${it.board} / ${it.hardware}")
            appendLine("Android: ${it.androidVersion} — ${it.codename}, SDK ${it.sdkVersion}")
            appendLine("Ядро: ${it.kernelVersion}\nАптайм: ${it.uptimeFormatted}")
            appendLine("Root: ${if (it.rootAccess) "да" else "нет"}\n")
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
            appendLine("Vulkan: ${if (it.vulkanSupported) it.vulkanVersion else "нет"}\n")
        }
        r.memory?.let {
            appendLine("── ПАМЯТЬ ──")
            appendLine("Всего: ${formatBytes(it.totalRam)}, занято: ${formatBytes(it.usedRam)}")
            appendLine("Swap: ${formatBytes(it.totalSwap)} (свободно ${formatBytes(it.freeSwap)})\n")
        }
        r.storage?.let {
            appendLine("── ХРАНИЛИЩЕ ──")
            appendLine("Всего: ${formatBytes(it.internalTotal)}, занято: ${formatBytes(it.internalUsed)}")
            appendLine("Файловая система: ${it.filesystem}\n")
        }
        r.battery?.let {
            appendLine("── БАТАРЕЯ ──")
            appendLine("Заряд: ${it.levelPercent}%, статус: ${it.status}")
            appendLine("Технология: ${it.technology}, здоровье: ${it.health}")
            appendLine("Напряжение: ${it.voltage} В, ток: ${it.currentNowMa} мА, мощность: %.2f Вт\n".format(it.instantPowerW))
        }
        r.network?.let {
            appendLine("── СЕТЬ ──")
            appendLine("${it.type} (${it.subtype}), IP: ${it.ipAddress}")
            appendLine("Оператор: ${it.carrier}, VPN: ${if (it.isVpnActive) "активен" else "нет"}\n")
        }
        r.display?.let {
            appendLine("── ЭКРАН ──")
            appendLine("${it.widthPx}×${it.heightPx}, %.1f\" диагональ".format(it.diagonalInches))
            appendLine("Частота: ${it.refreshRate} Гц (диапазон ${it.minRefreshRate}–${it.maxRefreshRate})")
            appendLine("HDR: ${if (it.hdrSupported) it.hdrTypes.joinToString() else "нет"}\n")
        }
        appendLine("── КАМЕРЫ (${r.cameras.size}) ──")
        r.cameras.forEach { appendLine("  ${it.id} [${it.facing}]: ${it.resolution}, %.1f МП, f/${it.aperture}".format(it.megapixels)) }
        appendLine("\n── ДАТЧИКИ (${r.sensors.size}) ──")
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
