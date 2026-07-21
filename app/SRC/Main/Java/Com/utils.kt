package com.ventaxiscorp.hexinspect

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.log10
import kotlin.math.pow

internal fun readIntFromFile(path: String): Int? = runCatching {
    BufferedReader(InputStreamReader(File(path).inputStream())).use { it.readText().trim().toIntOrNull() }
}.getOrNull()

internal fun readStringFromFile(path: String): String? = runCatching {
    BufferedReader(InputStreamReader(File(path).inputStream())).use { it.readText().trim() }
}.getOrNull()

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    val exp = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return "%.2f %s".format(bytes / 1024.0.pow(exp), units[exp])
}
