package com.ventaxiscorp.hexinspect

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

object HexBenchmarks {

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

    suspend fun benchmarkStorage(ctx: Context, progress: suspend (Float) -> Unit): BenchResult = withContext(Dispatchers.IO) {
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
}
