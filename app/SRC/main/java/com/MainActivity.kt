package com.ventaxiscorp.hexinspect

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

// ================================================================================
//  ТЕМА
// ================================================================================

val HexCyan = Color(0xFF00E5C7)
val HexGreen = Color(0xFF39FF14)
val HexAmber = Color(0xFFFFB74D)
val HexRed = Color(0xFFFF5252)
val HexBg = Color(0xFF0A0E12)
val HexSurface = Color(0xFF12181F)
val HexSurfaceAlt = Color(0xFF1B232B)
val HexMuted = Color(0xFF7A8B99)

private val DarkScheme = darkColorScheme(
    primary = HexCyan, onPrimary = Color.Black, secondary = HexGreen, tertiary = HexAmber,
    background = HexBg, surface = HexSurface, surfaceVariant = HexSurfaceAlt,
    onBackground = Color(0xFFE3F2F1), onSurface = Color(0xFFE3F2F1), onSurfaceVariant = HexMuted, error = HexRed
)
private val LightScheme = lightColorScheme(primary = Color(0xFF00897B), secondary = Color(0xFF2E7D32), tertiary = Color(0xFFEF6C00))

private val MonoType = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp)
)

@Composable
fun HexTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = MonoType, content = content)
}

// ================================================================================
//  MAIN ACTIVITY + NAV
// ================================================================================

private object R2 {
    const val DASH = "dash"; const val DEV = "dev"; const val CPU = "cpu"; const val GPU = "gpu"
    const val MEM = "mem"; const val BAT = "bat"; const val NET = "net"; const val DISP = "disp"
    const val CAM = "cam"; const val SENS = "sens"; const val BENCH = "bench"; const val THR = "thr"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Разрешения не требуются: всё читается через Build.*, /proc, EGL, ActivityManager,
        // CameraCharacteristics и SensorManager без runtime permission.
        setContent {
            HexTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val nav = rememberNavController()
                    val app = LocalContext.current.applicationContext as HexApp
                    val vm: HexViewModel = viewModel(factory = HexViewModel.factory(app, app.engine))

                    NavHost(nav, startDestination = R2.DASH) {
                        composable(R2.DASH) { Dashboard(vm, nav) }
                        composable(R2.DEV) { DeviceScreen(vm, nav) }
                        composable(R2.CPU) { CpuScreen(vm, nav) }
                        composable(R2.GPU) { GpuScreen(vm, nav) }
                        composable(R2.MEM) { MemoryScreen(vm, nav) }
                        composable(R2.BAT) { BatteryScreen(vm, nav) }
                        composable(R2.NET) { NetworkScreen(vm, nav) }
                        composable(R2.DISP) { DisplayScreen(vm, nav) }
                        composable(R2.CAM) { CamerasScreen(vm, nav) }
                        composable(R2.SENS) { SensorsScreen(vm, nav) }
                        composable(R2.BENCH) { BenchScreen(vm, nav) }
                        composable(R2.THR) { ThrottleScreen(vm, nav) }
                    }
                }
            }
        }
    }
}

// ================================================================================
//  ОБЩИЕ КОМПОНЕНТЫ
// ================================================================================

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.58f))
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun NavCard(title: String, subtitle: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PulsingDot(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color.copy(alpha = a)))
}

@Composable
fun HexScaffold(title: String, nav: NavController, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = { nav.navigateUp() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { p -> content(p) }
}

@Composable
fun <T> WithState(res: Res<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (res) {
        is Res.Loading -> Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        is Res.Ok -> content(res.data)
        is Res.Fail -> Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(res.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(onClick = onRetry) { Text("Повторить") }
        }
    }
}

private fun batteryStatusRu(s: String?) = s ?: "—"
private fun networkTypeRu(s: String?) = s ?: "—"

// ================================================================================
//  DASHBOARD
// ================================================================================

@Composable
fun Dashboard(vm: HexViewModel, nav: NavController) {
    val ctx = LocalContext.current
    val refreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val cpu by vm.cpu.collectAsStateWithLifecycle()
    val gpu by vm.gpu.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val memory by vm.memory.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val display by vm.display.collectAsStateWithLifecycle()
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val sensors by vm.sensors.collectAsStateWithLifecycle()

    val rot = rememberInfiniteTransition(label = "rot")
    val angle by rot.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")

    val deviceData = (device as? Res.Ok)?.data
    val cpuData = (cpu as? Res.Ok)?.data
    val gpuData = (gpu as? Res.Ok)?.data
    val batteryData = (battery as? Res.Ok)?.data
    val memoryData = (memory as? Res.Ok)?.data
    val networkData = (network as? Res.Ok)?.data
    val displayData = (display as? Res.Ok)?.data
    val camCount = (cameras as? Res.Ok)?.data?.size
    val sensCount = (sensors as? Res.Ok)?.data?.size

    val batColor = when {
        batteryData == null -> HexMuted
        batteryData.status == "Заряжается" -> HexGreen
        batteryData.levelPercent <= 15 -> HexRed
        else -> HexCyan
    }
    val cpuColor = when {
        cpuData == null -> HexMuted
        cpuData.throttling -> HexRed
        cpuData.loadPercent > 0.8f -> HexAmber
        else -> HexGreen
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("HexInspect", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            PulsingDot(HexGreen)
                        }
                        Text("Ventaxis Corp", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.refreshAll(); Toast.makeText(ctx, "Обновляю всё…", Toast.LENGTH_SHORT).show() },
                icon = { Icon(Icons.Filled.Refresh, contentDescription = null, modifier = if (refreshing) Modifier.rotate(angle) else Modifier) },
                text = { Text(if (refreshing) "Обновление…" else "Обновить всё") }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
            item { NavCard("Устройство", "${deviceData?.manufacturer ?: "…"} ${deviceData?.model ?: ""}", Icons.Filled.PhoneAndroid, HexCyan) { nav.navigate(R2.DEV) } }
            item { NavCard("Процессор", "${cpuData?.hardwareName ?: "…"} · ${(cpuData?.loadPercent?.times(100))?.toInt() ?: 0}%", Icons.Filled.Memory, cpuColor) { nav.navigate(R2.CPU) } }
            item { NavCard("Видеоядро", gpuData?.renderer ?: "определяется…", Icons.Filled.Speed, HexGreen) { nav.navigate(R2.GPU) } }
            item { NavCard("Память", "${formatBytes(memoryData?.usedRam ?: 0)} / ${formatBytes(memoryData?.totalRam ?: 0)}", Icons.Filled.Storage, HexCyan) { nav.navigate(R2.MEM) } }
            item { NavCard("Батарея", "${batteryData?.levelPercent ?: 0}% · ${batteryStatusRu(batteryData?.status)}", Icons.Filled.BatteryChargingFull, batColor) { nav.navigate(R2.BAT) } }
            item { NavCard("Сеть", "${networkTypeRu(networkData?.type)} · ${networkData?.carrier ?: ""}", Icons.Filled.Wifi, HexCyan) { nav.navigate(R2.NET) } }
            item { NavCard("Экран", displayData?.let { "%.1f\" · ${it.refreshRate.toInt()} Гц".format(it.diagonalInches) } ?: "…", Icons.Filled.Monitor, HexGreen) { nav.navigate(R2.DISP) } }
            item { NavCard("Камеры", "${camCount ?: 0} модулей", Icons.Filled.PhotoCamera, HexCyan) { nav.navigate(R2.CAM) } }
            item { NavCard("Датчики", "${sensCount ?: 0} датчиков", Icons.Filled.Sensors, HexGreen) { nav.navigate(R2.SENS) } }
            item { NavCard("Бенчмарки", "Тесты CPU / RAM / хранилища", Icons.Filled.Bolt, HexAmber) { nav.navigate(R2.BENCH) } }
            item { NavCard("Троттлинг", "Мониторинг перегрева CPU", Icons.Filled.Thermostat, HexRed) { nav.navigate(R2.THR) } }
            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.refreshDynamic() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Быстрое обновление")
                }
            }
        }
    }
}

// ================================================================================
//  ДЕТАЛЬНЫЕ ЭКРАНЫ
// ================================================================================

@Composable
fun DeviceScreen(vm: HexViewModel, nav: NavController) {
    val s by vm.device.collectAsStateWithLifecycle()
    HexScaffold("Устройство", nav) { p ->
        LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {
            item {
                WithState(s, { vm.refreshAll() }) { d ->
                    Section("Общее") {
                        InfoRow("Производитель", d.manufacturer)
                        InfoRow("Модель", d.model)
                        InfoRow("Кодовое имя", d.device)
                        InfoRow("Бренд", d.brand)
                    }
                    Section("Платформа") {
                        InfoRow("Плата", d.board)
                        InfoRow("Железо", d.hardware)
                        InfoRow("Android", "${d.androidVersion} (SDK ${d.sdkVersion})")
                        InfoRow("Кодовое имя ОС", d.codename)
                        InfoRow("Build ID", d.buildId)
                        InfoRow("Ядро Linux", d.kernelVersion)
                    }
                    Section("Состояние") {
                        InfoRow("Аптайм", d.uptimeFormatted)
                        InfoRow("Root", if (d.rootAccess) "Есть" else "Нет", valueColor = if (d.rootAccess) HexGreen else Color.Unspecified)
                        InfoRow("Эмулятор", if (d.isEmulator) "Да" else "Нет")
                        InfoRow("Fingerprint", d.fingerprint)
                    }
                }
            }
        }
    }
}

@Composable
fun CpuScreen(vm: HexViewModel, nav: NavController) {
    val s by vm.cpu.collectAsStateWithLifecycle()
    HexScaffold("Процессор", nav) { p ->
        LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {
            item {
                WithState(s, { vm.refreshAll() }) { d ->
                    Section("Состояние сейчас") {
                        InfoRow("Загрузка", "${(d.loadPercent * 100).toInt()}%", valueColor = if (d.loadPercent > 0.8f) HexRed else HexGreen)
                        InfoRow("Температура", "${d.avgTemperature}°C", valueColor = if (d.avgTemperature > 45f) HexRed else Color.Unspecified)
                        InfoRow("Троттлинг", if (d.throttling) "Да" else "Нет", valueColor = if (d.throttling) HexRed else HexGreen)
                        InfoRow("Governor", d.governor)
                    }
                    Section("Архитектура") {
                        InfoRow("Название", d.hardwareName)
                        InfoRow("Ядер", "${d.logicalCores}")
                        InfoRow("Кластеры", "${d.clusterCount}${if (d.isBigLittle) " (big.LITTLE)" else " (однородный)"}")
                        InfoRow("Архитектура", d.architecture)
                        InfoRow("ABI", d.abis.joinToString(", "))
                        if (d.cacheL2Kb > 0) InfoRow("Кэш L2", "${d.cacheL2Kb} КБ")
                    }
                    Section("По ядрам") {
                        d.cores.forEach { c ->
                            InfoRow("Ядро ${c.index}", "${c.curMhz} МГц (${c.minMhz}–${c.maxMhz})", valueColor = if (!c.online) HexMuted else Color.Unspecified)
                        }
                    }
                    Section("Наборы инструкций") {
                        InfoRow("Флаги", if (d.features.isEmpty()) "недоступно" else d.features.take(20).joinToString(" "))
                    }
                }
            }
        }
    }
}

@Composable
fun GpuScreen(vm: HexViewModel, nav: NavController) {
    val s by vm.gpu.collectAsStateWithLifecycle()
    HexScaffold("Видеоядро", nav) { p ->
        LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {
            item {
                WithState(s, { vm.refreshAll() }) { d ->
                    Section("GPU") {
                        InfoRow("Renderer", d.renderer)
                        InfoRow("Vendor", d.vendor)
                        InfoRow("OpenGL ES", d.glVersion)
                        InfoRow("EGL", d.eglVersion)
                    }
                    Section("Vulkan") {
                        InfoRow("Поддержка", if (d.vulkanSupported) "Да" else "Нет", valueColor = if (d.vulkanSupported) HexGreen else HexMuted)
                        if (d.vulkanSupported) InfoRow("Версия", d.vulkanVersion)
                    }
                    Section("Расширения (${d.extensionsCount})") {
                        InfoRow("Список", d.extensions.take(15).joinToString(", ") + if (d.extensions.size > 15) " …" else "")
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryScreen(vm: HexViewModel, nav: NavController) {
    val mem by vm.memory.collectAsStateWithLifecycle()
    val stor by vm.storage.collectAsStateWithLifecycle()
    HexScaffold("Память и хранилище", nav) { p ->
        LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {
            item {
                WithState(mem, { vm.refreshDynamic() }) { d ->
                    Section("Оперативная память") {
                        val frac = if (d.totalRam > 0) d.usedRam.toFloat() / d.totalRam else 0f
                        LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = if (frac > 0.85f) HexRed else HexCyan)
                        InfoRow("Всего", formatBytes(d.totalRam))
                        InfoRow("Занято", formatBytes(d.usedRam))
                        InfoRow("Свободно", formatBytes(d.availableRam))
                        InfoRow("Порог", formatBytes(d.threshold))
                        InfoRow("Мало памяти", if (d.lowMemory) "Да" else "Нет", valueColor = if (d.lowMemory) HexRed else HexGreen)
                        InfoRow("Swap", "${formatBytes(d.freeSwap)} своб. из ${formatBytes(d.totalSwap)}")
                        InfoRow("Тип памяти", d.memType)
                    }
                }
            }
            item {
                WithState(stor, { vm.refreshDynamic() }) { d ->
                    Section("Хранилище") {
                        val frac = if (d.internalTotal > 0) d.internalUsed.toFloat() / d.internalTotal else 0f
                        LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = if (frac > 0.9f) HexRed else HexGreen)
                        InfoRow("Всего", formatBytes(d.internalTotal))
                        InfoRow("Занято", formatBytes(d.internalUsed))
                        InfoRow("Свободно", formatBytes(d.internalAvailable))
                        InfoRow("Файловая система", d.filesystem)
                        InfoRow("Кэш приложения", "${formatBytes(d.cacheAvailable)} своб.")
                    }
                }
            }
        }
    }
}

@Composable
fun BatteryScreen(vm: HexViewModel, nav: NavController) {
    val s by vm.battery.collectAsStateWithLifecycle()
    HexScaffold("Батарея", nav) { p ->
        LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {
            item {
                WithState(s, { vm.refreshDynamic() }) { d ->
                    Section("Заряд") {
                        InfoRow("Уровень", "${d.levelPercent}%", valueColor = if (d.levelPercent <= 15) HexRed else HexGreen)
                        InfoRow("Ёмкость", "${d.capacityPercent}%")
                        InfoRow("Статус", d.status)
                        InfoRow("Подключение", d.plugged)
                        if (d.cycleCount >= 0) InfoRow("Циклов заряда", "${d.cycleCount}")
                    }
                    Section("Электрические параметры") {
                        InfoRow("Напряжение", "${d.voltage} В")
                        InfoRow("Ток", "${d.currentNowMa} мА")
                        InfoRow("Мгновенная мощность", "%.2f Вт".format(d.instantPowerW))
                        InfoRow("Энергосчётчик", "${d.energyCounterUwh} мкВтч")
                    }
                    Section("Состояние") {
                        InfoRow("Температура", "${d.temperature}°C", valueColor = if (d.temperature > 40f) HexRed else Color.Unspecified)
                        InfoRow("Здоровье", d.health)
                        InfoRow("Технология", d.technology)
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkScreen(vm: HexViewModel, nav: NavController) {
    val s by vm.network.collectAsStateWithLifecycle()
    HexScaffold("Сеть", nav) { p ->
        LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {
            item {
                WithState(s, { vm.refreshDynamic() }) { d ->
                    Section("Подключение") {
                        InfoRow("Тип", d.type)
                        InfoRow("Подтип", d.subtype)
                        InfoRow("Оператор", d.carrier)
                        InfoRow("Метрический (лимит)", if (d.isMetered) "Да" else "Нет")
                        InfoRow("VPN", if (d.isVpnActive) "Активен" else "Не активен", valueColor = if (d.isVpnActive) HexGreen else Color.Unspecified)
                    }
                    Section("Скорость канала") {
                        InfoRow("Приём", "${d.downKbps} Кбит/с")
                        InfoRow("Передача", "${d.upKbps} Кбит/с")
                    }
                    Section("Адресация") {
                        InfoRow("IP-адрес", d.ipAddress)
                        InfoRow("MAC-адрес", d.macAddress)
                    }
                }
            }
        }
    }
}

@Composable
fun DisplayScreen(vm: HexViewModel, nav: NavController) {
    val s by vm.display.collectAsStateWithLifecycle()
    HexScaffold("Экран", nav) { p ->
        LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {
            item {
                WithState(s, { vm.refreshAll() }) { d ->
                    Section("Разрешение") {
                        InfoRow("Пиксели", "${d.widthPx}×${d.heightPx}")
                        InfoRow("Диагональ", "%.2f\"".format(d.diagonalInches))
                        InfoRow("Плотность", "${d.density} (${d.densityDpi} dpi)")
                    }
                    Section("Частота обновления") {
                        InfoRow("Текущая", "${d.refreshRate} Гц")
                        InfoRow("Диапазон", "${d.minRefreshRate}–${d.maxRefreshRate} Гц")
                    }
                    Section("Цвет и HDR") {
                        InfoRow("HDR", if (d.hdrSupported) d.hdrTypes.joinToString() else "Не поддерживается", valueColor = if (d.hdrSupported) HexGreen else HexMuted)
                        InfoRow("Широкий цветовой охват", if (d.wideColorGamut) "Да" else "Нет")
                    }
                    Section("Доступные режимы (${d.availableModes.size})") {
                        d.availableModes.take(10).forEach { m ->
                            InfoRow("${m.widthPx}×${m.heightPx}", "${m.refreshRate} Гц")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CamerasScreen(vm: HexViewModel, nav: NavController) {
    val s by vm.cameras.collectAsStateWithLifecycle()
    HexScaffold("Камеры", nav) { p ->
        when (val st = s) {
            is Res.Loading, is Res.Fail -> LazyColumn(modifier = Modifier.padding(p)) { item { WithState(st, { vm.refreshAll() }) {} } }
            is Res.Ok -> LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {
                if (st.data.isEmpty()) item { Section("Нет данных") { InfoRow("Камеры", "не обнаружены") } }
                items(st.data) { c ->
                    Section("Камера ${c.id} · ${c.facing}") {
                        InfoRow("Разрешение", c.resolution)
                        InfoRow("Мегапиксели", "%.1f МП".format(c.megapixels))
                        InfoRow("Сенсор", c.sensorSizeMm)
                        InfoRow("Диафрагма", if (c.aperture > 0) "f/${c.aperture}" else "—")
                        InfoRow("Фокусные расстояния", c.focalLengthsMm.joinToString(", ") { "${it} мм" }.ifBlank { "—" })
                        InfoRow("ISO", c.isoRange)
                        InfoRow("Вспышка", if (c.hasFlash) "Есть" else "Нет")
                        InfoRow("OIS", if (c.hasOis) "Есть" else "Нет")
                        InfoRow("Автофокус", if (c.hasAutofocus) "Есть" else "Нет")
                        InfoRow("Уровень API", c.hardwareLevel)
                    }
                }
            }
        }
    }
}

@Composable
fun SensorsScreen(vm: HexViewModel, nav: NavController) {
    val s by vm.sensors.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { vm.startSensorUpdates(); onDispose { vm.stopSensorUpdates() } }
    HexScaffold("Датчики", nav) { p ->
        when (val st = s) {
            is Res.Loading, is Res.Fail -> LazyColumn(modifier = Modifier.padding(p)) { item { WithState(st, { vm.refreshAll() }) {} } }
            is Res.Ok -> LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {
                items(st.data) { sensor ->
                    Section(sensor.name) {
                        InfoRow("Вендор", sensor.vendor)
                        InfoRow("Макс. диапазон", "${sensor.maxRange}")
                        InfoRow("Точность", "${sensor.resolution}")
                        InfoRow("Мощность", "${sensor.power} мА")
                        InfoRow("Значения", if (sensor.values.isEmpty()) "нет данных" else sensor.values.joinToString(" · ") { "%.2f".format(it) })
                    }
                }
            }
        }
    }
}

@Composable
fun BenchScreen(vm: HexViewModel, nav: NavController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cpuR by vm.benchCpu.collectAsStateWithLifecycle()
    val memR by vm.benchMemory.collectAsStateWithLifecycle()
    val storR by vm.benchStorage.collectAsStateWithLifecycle()
    val progress by vm.benchProgress.collectAsStateWithLifecycle()
    val running by vm.isBenchRunning.collectAsStateWithLifecycle()

    HexScaffold("Бенчмарки", nav) { p ->
        Column(modifier = Modifier.padding(p).padding(16.dp)) {
            if (running) {
                Section("Выполняется…") {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = HexCyan)
                    Text("${(progress * 100).toInt()}%")
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            Section("CPU") {
                Button(onClick = { vm.startCpuBenchmark() }, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text("Запустить тест CPU") }
                cpuR?.let { Spacer(modifier = Modifier.height(6.dp)); InfoRow("Результат", "%.2f ${it.unit}".format(it.score), valueColor = HexGreen) }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Section("Память") {
                Button(onClick = { vm.startMemoryBenchmark() }, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text("Запустить тест памяти") }
                memR?.let { Spacer(modifier = Modifier.height(6.dp)); InfoRow("Результат", "%.1f ${it.unit}".format(it.score), valueColor = HexGreen) }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Section("Хранилище") {
                Button(onClick = { vm.startStorageBenchmark() }, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text("Запустить тест хранилища") }
                storR?.let { Spacer(modifier = Modifier.height(6.dp)); InfoRow("Результат", "%.1f ${it.unit}".format(it.score), valueColor = HexGreen) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Section("Экспорт отчёта") {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { scope.launch { val f = vm.export("txt"); Toast.makeText(ctx, if (f != null) "Сохранено: ${f.name}" else "Ошибка экспорта", Toast.LENGTH_SHORT).show() } },
                        modifier = Modifier.weight(1f)
                    ) { Text("TXT") }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { scope.launch { val f = vm.export("json"); Toast.makeText(ctx, if (f != null) "Сохранено: ${f.name}" else "Ошибка экспорта", Toast.LENGTH_SHORT).show() } },
                        modifier = Modifier.weight(1f)
                    ) { Text("JSON") }
                }
            }
        }
    }
}

@Composable
fun ThrottleScreen(vm: HexViewModel, nav: NavController) {
    val data by vm.throttleData.collectAsStateWithLifecycle()
    HexScaffold("Троттлинг CPU", nav) { p ->
        Column(modifier = Modifier.padding(p).padding(16.dp)) {
            Row {
                Button(onClick = { vm.startThrottleTest() }, modifier = Modifier.weight(1f)) { Text("Старт") }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.stopThrottleTest() }, modifier = Modifier.weight(1f)) { Text("Стоп") }
            }
            Spacer(modifier = Modifier.height(16.dp))
            data?.let { info ->
                Section("Статус") {
                    InfoRow("Сейчас троттлит", if (info.currentlyThrottled) "Да" else "Нет", valueColor = if (info.currentlyThrottled) HexRed else HexGreen)
                    InfoRow("Замеров", "${info.history.size}")
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn {
                    items(info.history.asReversed()) { sample ->
                        Section("${sample.elapsedSec} с") {
                            InfoRow("Частота", "${sample.avgFreqMhz} МГц")
                            InfoRow("Темп.", "${sample.tempC}°C", valueColor = if (sample.throttled) HexRed else Color.Unspecified)
                        }
                    }
                }
            } ?: Text("Нажми «Старт» для начала мониторинга.")
        }
    }
}
