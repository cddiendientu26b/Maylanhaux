package com.nagakawa.ir

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

enum class AcMode(val label: String, val bits: Int, val hasTemp: Boolean) {
    AUTO("Tự động", 0b000, true), COOL("Mát", 0b001, true),
    HEAT("Nóng", 0b100, true), DRY("Khô", 0b010, true), FAN("Quạt", 0b110, false)
}

enum class FanSpeed(val label: String, val short: String, val bits: Int) {
    AUTO("Tự động", "Tự động", 0b101), SPEED_1("Mức 1", "1", 0b011),
    SPEED_2("Mức 2", "2", 0b010), SPEED_3("Mức 3", "3", 0b001)
}

enum class SwingDir(val label: String, val bits: Int) {
    UP("Lên", 0b001), MID("Giữa", 0b010), DOWN("Xuống", 0b011),
    SWING("Đảo gió", 0b000), OFF("Đứng yên", 0b111)
}

enum class ExtraFn(val raw: String, val verified: Boolean) {
    AUX_HEAT("Aux Heat", false), BODY_FEEL("Body Feel", false),
    DISPLAY("DISPLAY", true), ECO("ECO", true),
    HEALTH("Health", true), HUMIDIFICATION("Humidification", false),
    LR_WIND_MODE("LR_WIND_MODE", true), MUTE("MUTE", true), TURBO("TURBO", true)
}

data class AcState(
    val power: Boolean = true, val temp: Int = 26, val mode: AcMode = AcMode.COOL,
    val fan: FanSpeed = FanSpeed.SPEED_3, val swing: SwingDir = SwingDir.UP,
    val sleep: Boolean = false, val timerHours: Int = 0, val eco: Boolean = false,
    val turbo: Boolean = false, val display: Boolean = true, val health: Boolean = false,
    val mute: Boolean = false, val lrWind: Boolean = false, val lastAction: String = "Mặc định"
) {
    companion object {
        fun load(p: SharedPreferences) = AcState(
            power = p.getBoolean("power", true),
            temp = p.getInt("temp", 26).coerceIn(16, 30),
            mode = runCatching { AcMode.valueOf(p.getString("mode", "COOL")!!) }.getOrDefault(AcMode.COOL),
            fan = runCatching { FanSpeed.valueOf(p.getString("fan", "SPEED_3")!!) }.getOrDefault(FanSpeed.SPEED_3),
            swing = runCatching { SwingDir.valueOf(p.getString("swing", "UP")!!) }.getOrDefault(SwingDir.UP),
            sleep = p.getBoolean("sleep", false), timerHours = p.getInt("timer", 0),
            eco = p.getBoolean("eco", false), turbo = p.getBoolean("turbo", false),
            display = p.getBoolean("display", true), health = p.getBoolean("health", false),
            mute = p.getBoolean("mute", false), lrWind = p.getBoolean("lrWind", false),
            lastAction = "Đã khôi phục trạng thái dự kiến"
        )

        fun save(p: SharedPreferences, s: AcState) = p.edit()
            .putBoolean("power", s.power).putInt("temp", s.temp).putString("mode", s.mode.name)
            .putString("fan", s.fan.name).putString("swing", s.swing.name).putBoolean("sleep", s.sleep)
            .putInt("timer", s.timerHours).putBoolean("eco", s.eco).putBoolean("turbo", s.turbo)
            .putBoolean("display", s.display).putBoolean("health", s.health).putBoolean("mute", s.mute)
            .putBoolean("lrWind", s.lrWind).apply()
    }
}

object Aux13ByteProtocol {
    const val FREQ_HZ = 38000

    fun encode(s: AcState, toggleDisplay: Boolean = false): Pair<IntArray, String> {
        val f = IntArray(13)
        f[0] = 0xC3
        f[1] = (((s.temp.coerceIn(16, 30) - 8) and 0x1F) shl 3) or (s.swing.bits and 0x07)
        f[2] = (if (s.lrWind) 0b000 else 0b111) shl 5
        f[4] = (s.fan.bits and 0x07) shl 5
        f[5] = (if (s.turbo && (s.mode == AcMode.COOL || s.mode == AcMode.HEAT)) 1 shl 6 else 0) or
            (if (s.mute) 1 shl 7 else 0)
        f[6] = ((s.mode.bits and 0x07) shl 5) or (if (s.sleep) 1 shl 2 else 0)
        f[7] = s.timerHours.coerceIn(0, 24)
        f[8] = if (f[7] > 0) (1 shl 3) else 0
        f[9] = (if (s.power) 1 shl 5 else 0) or (if (s.eco && s.mode == AcMode.COOL) 1 shl 4 else 0) or
            (if (s.health) 1 shl 2 else 0)
        f[11] = if (toggleDisplay) 0x05 else 0x00
        f[12] = (0..11).sumOf { f[it] } and 0xFF

        val pulses = IntArray(2 + 13 * 16 + 2)
        var i = 0
        pulses[i++] = 9000; pulses[i++] = 4500
        for (b in f) {
            for (bit in 0 until 8) {
                pulses[i++] = 560
                pulses[i++] = if (((b shr bit) and 1) == 1) 1690 else 560
            }
        }
        pulses[i++] = 560; pulses[i] = 10000
        return pulses to f.joinToString(" ") { "%02X".format(it) }
    }
}

class IrController(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val ir = appCtx.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private val mutex = Mutex()

    fun checkHardware(): Pair<Boolean, String> {
        val hasFeature = appCtx.packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)
        val hasEmitter = runCatching { ir?.hasIrEmitter() == true }.getOrDefault(false)
        if (!hasFeature || !hasEmitter || ir == null) {
            return false to "Thiết bị KHÔNG có mắt phát hồng ngoại (IR Blaster)"
        }
        val okFreq = runCatching {
            ir.carrierFrequencies?.any { Aux13ByteProtocol.FREQ_HZ in it.minFrequency..it.maxFrequency } == true
        }.getOrDefault(false)
        return if (okFreq) true to "Sẵn sàng phát IR (38 kHz - Profile AUX 13-Byte)"
        else false to "Phần cứng IR không hỗ trợ tần số 38 kHz"
    }

    suspend fun transmit(pulses: IntArray): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (ready, msg) = checkHardware()
            if (!ready || ir == null) return@withLock Result.failure(IllegalStateException(msg))
            runCatching { ir.transmit(Aux13ByteProtocol.FREQ_HZ, pulses) }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("nagakawa_ir", Context.MODE_PRIVATE)
        val irController = IrController(this)

        setContent {
            val dark = isSystemInDarkTheme()
            val blue = Color(0xFF0066FF)
            val scheme = if (dark) darkColorScheme(primary = blue, background = Color(0xFF101318), surface = Color(0xFF1B2028))
            else lightColorScheme(primary = blue, background = Color(0xFFF5F6F8), surface = Color.White)

            MaterialTheme(colorScheme = scheme) {
                var state by remember { mutableStateOf(AcState.load(prefs)) }
                var hwInfo by remember { mutableStateOf(irController.checkHardware()) }
                var txStatus by remember { mutableStateOf("Chưa phát lệnh trong phiên này") }
                var lastHex by remember { mutableStateOf("Chưa có gói IR") }
                var showMore by remember { mutableStateOf(false) }
                var showTimer by remember { mutableStateOf(false) }
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                fun notify(msg: String) = scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(msg, duration = SnackbarDuration.Short)
                }

                fun sendState(newState: AcState, action: String, toggleDisplay: Boolean = false) {
                    val updated = newState.copy(lastAction = action)
                    state = updated
                    AcState.save(prefs, updated)
                    val (pulses, hex) = Aux13ByteProtocol.encode(updated, toggleDisplay)
                    lastHex = hex
                    scope.launch {
                        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        irController.transmit(pulses).onSuccess {
                            txStatus = "[$time] Đã gửi lệnh ($action) – Chưa xác nhận từ điều hòa"
                            notify("Đã gửi lệnh: $action (Chưa xác nhận)")
                        }.onFailure { e ->
                            txStatus = "[$time] Lỗi phát IR: ${e.message}"
                            notify(e.message ?: "Không thể phát IR")
                        }
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = {
                                Column {
                                    Text("Nagakawa Air Conditioner", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Text(hwInfo.second, fontSize = 11.sp, color = if (hwInfo.first) Color(0xFF1E8E3E) else Color(0xFFD32F2F))
                                }
                            },
                            actions = {
                                IconButton(onClick = { hwInfo = irController.checkHardware(); notify(hwInfo.second) }) {
                                    Icon(Icons.Default.Sensors, "Kiểm tra IR")
                                }
                            }
                        )
                    }
                ) { pad ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Bảng 3 tầng trạng thái
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("1. Trạng thái dự kiến (UI): ${state.lastAction}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text("2. Lệnh đã phát: $txStatus", fontSize = 12.sp)
                                Text("3. Thiết bị xác nhận: Chưa xác nhận (IR 1 chiều - Có thể lệch nếu dùng điều khiển rời)", fontSize = 11.sp, color = Color(0xFFE65100))
                                Text("HEX: $lastHex", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { sendState(state, "Phát đồng bộ lại") }, modifier = Modifier.weight(1f)) {
                                        Text("Phát đồng bộ lại", fontSize = 12.sp)
                                    }
                                    OutlinedButton(onClick = {
                                        state = AcState(); AcState.save(prefs, state)
                                        txStatus = "Đã đặt lại mặc định"; notify("Đã đặt lại mặc định")
                                    }, modifier = Modifier.weight(1f)) {
                                        Text("Đặt lại mặc định", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (!state.power) "TẮT" else if (!state.mode.hasTemp) "--" else "${state.temp}°C",
                            fontSize = 68.sp, fontWeight = FontWeight.Light
                        )
                        Text("Nhiệt độ cài đặt", fontSize = 14.sp, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))

                        // Chip hiển thị rõ trạng thái bật/tắt
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = {}, label = { Text(if (state.power) "Nguồn: Đang bật" else "Nguồn: Đang tắt", fontSize = 11.sp) })
                            if (state.eco) AssistChip(onClick = {}, label = { Text("ECO: Đang bật", fontSize = 11.sp) })
                            if (state.turbo) AssistChip(onClick = {}, label = { Text("TURBO: Đang bật", fontSize = 11.sp) })
                            if (state.sleep) AssistChip(onClick = {}, label = { Text("Ngủ: Đang bật", fontSize = 11.sp) })
                            if (state.timerHours > 0) AssistChip(onClick = {}, label = { Text("Hẹn giờ: ${state.timerHours}h", fontSize = 11.sp) })
                            if (state.health) AssistChip(onClick = {}, label = { Text("Health: Đang bật", fontSize = 11.sp) })
                            if (state.mute) AssistChip(onClick = {}, label = { Text("MUTE: Đang bật", fontSize = 11.sp) })
                            if (state.lrWind) AssistChip(onClick = {}, label = { Text("LR_WIND: Đang bật", fontSize = 11.sp) })
                            AssistChip(onClick = {}, label = { Text(if (state.display) "DISPLAY: Đang bật" else "DISPLAY: Đang tắt", fontSize = 11.sp) })
                        }

                        Spacer(Modifier.height(14.dp))

                        // Bảng thông tin nhanh 3 cột
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                InfoCol(state.mode.label, "Chế độ")
                                InfoCol(state.fan.short, "Tốc độ quạt")
                                InfoCol(state.swing.label, "Hướng")
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Hai nút Nguồn & Xoay
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BigPillButton(
                                title = "Nguồn", sub = if (state.power) "Đang bật" else "Đang tắt",
                                icon = Icons.Default.PowerSettingsNew, active = state.power,
                                activeColor = Color(0xFF1E8E3E), modifier = Modifier.weight(1f)
                            ) { sendState(state.copy(power = !state.power), if (!state.power) "Nguồn: Đang bật" else "Nguồn: Đang tắt") }

                            val isSwing = state.swing == SwingDir.SWING
                            BigPillButton(
                                title = "Xoay", sub = if (isSwing) "Đảo gió: Bật" else state.swing.label,
                                icon = Icons.Default.SwapVert, active = isSwing,
                                activeColor = blue, modifier = Modifier.weight(1f)
                            ) {
                                val next = if (isSwing) SwingDir.UP else SwingDir.SWING
                                sendState(state.copy(swing = next), "Hướng gió: ${next.label}")
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Thanh tăng giảm nhiệt độ
                        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                IconButton(onClick = {
                                    if (!state.power) notify("Hãy bật Nguồn trước")
                                    else if (!state.mode.hasTemp) notify("Chế độ Quạt không chỉnh được nhiệt độ")
                                    else if (state.temp > 16) sendState(state.copy(temp = state.temp - 1), "Nhiệt độ: ${state.temp - 1}°C")
                                    else notify("Đã đạt mức thấp nhất 16°C")
                                }) { Icon(Icons.Default.Remove, "Giảm") }

                                Text("Nhiệt độ (${state.temp}°C)", fontSize = 17.sp, fontWeight = FontWeight.Medium)

                                IconButton(onClick = {
                                    if (!state.power) notify("Hãy bật Nguồn trước")
                                    else if (!state.mode.hasTemp) notify("Chế độ Quạt không chỉnh được nhiệt độ")
                                    else if (state.temp < 30) sendState(state.copy(temp = state.temp + 1), "Nhiệt độ: ${state.temp + 1}°C")
                                    else notify("Đã đạt mức cao nhất 30°C")
                                }) { Icon(Icons.Default.Add, "Tăng") }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // 5 Nút chế độ hoạt động
                        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                val icons = listOf(Icons.Default.HdrAuto, Icons.Default.AcUnit, Icons.Default.WbSunny, Icons.Default.WaterDrop, Icons.Default.Air)
                                AcMode.entries.forEachIndexed { idx, m ->
                                    CircleIconBtn(m.label, null, icons[idx], state.mode == m) {
                                        sendState(
                                            state.copy(
                                                mode = m,
                                                eco = if (m == AcMode.COOL) state.eco else false,
                                                turbo = if (m == AcMode.COOL || m == AcMode.HEAT) state.turbo else false
                                            ),
                                            "Chế độ: ${m.label}"
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // 5 Nút chức năng bổ sung
                        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                              CircleIconBtn("Ngủ", if (state.sleep) "Bật" else "Tắt", Icons.Default.Bedtime, state.sleep) {
                                    sendState(state.copy(sleep = !state.sleep), if (!state.sleep) "Ngủ: Đang bật" else "Ngủ: Đang tắt")
                                }
                                CircleIconBtn("Bộ hẹn giờ", if (state.timerHours > 0) "${state.timerHours}h" else "Tắt", Icons.Default.Timer, state.timerHours > 0) {
                                    showTimer = true
                                }
                                CircleIconBtn("Hướng", state.swing.label, Icons.Default.SwapVert, state.swing == SwingDir.SWING) {
                                    val next = SwingDir.entries[(state.swing.ordinal + 1) % SwingDir.entries.size]
                                    sendState(state.copy(swing = next), "Hướng: ${next.label}")
                                }
                                CircleIconBtn("Tốc độ quạt", state.fan.short, Icons.Default.Speed, state.fan != FanSpeed.AUTO) {
                                    val next = FanSpeed.entries[(state.fan.ordinal + 1) % FanSpeed.entries.size]
                                    sendState(state.copy(fan = next), "Tốc độ quạt: ${next.label}")
                                }
                                CircleIconBtn("Thêm", "Mở rộng", Icons.Default.GridView, state.eco || state.turbo || state.health || state.mute || state.lrWind) {
                                    showMore = true
                                }
                            }
                        }
                    }
                }

                // Bảng "Chức năng điều khiển khác" (9 nút)
                if (showMore) {
                    @OptIn(ExperimentalMaterial3Api::class)
                    ModalBottomSheet(onDismissRequest = { showMore = false }) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Chức năng điều khiển khác", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(14.dp))
                            ExtraFn.entries.chunked(2).forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { fn ->
                                        val isOn = when (fn) {
                                            ExtraFn.DISPLAY -> state.display; ExtraFn.ECO -> state.eco
                                            ExtraFn.HEALTH -> state.health; ExtraFn.LR_WIND_MODE -> state.lrWind
                                            ExtraFn.MUTE -> state.mute; ExtraFn.TURBO -> state.turbo
                                            else -> false
                                        }
                                        val sub = if (!fn.verified) "Chưa xác minh" else if (isOn) "${fn.raw}: Đang bật" else "${fn.raw}: Đang tắt"
                                        Surface(
                                            modifier = Modifier.weight(1f).height(62.dp).clip(RoundedCornerShape(31.dp)).clickable {
                                                if (!fn.verified) {
                                                    notify("${fn.raw}: Chưa xác minh mã IR trên Nagakawa. Đã khóa phát lệnh giả.")
                                                    return@clickable
                                                }
                                                when (fn) {
                                                    ExtraFn.DISPLAY -> sendState(state.copy(display = !state.display), if (!state.display) "DISPLAY: Đang bật" else "DISPLAY: Đang tắt", toggleDisplay = true)
                                                    ExtraFn.ECO -> if (state.mode != AcMode.COOL) notify("ECO chỉ hỗ trợ ở chế độ Mát") else sendState(state.copy(eco = !state.eco, turbo = false), if (!state.eco) "ECO: Đang bật" else "ECO: Đang tắt")
                                                    ExtraFn.TURBO -> if (state.mode != AcMode.COOL && state.mode != AcMode.HEAT) notify("TURBO chỉ hỗ trợ ở Mát/Nóng") else sendState(state.copy(turbo = !state.turbo, eco = false), if (!state.turbo) "TURBO: Đang bật" else "TURBO: Đang tắt")
                                                    ExtraFn.HEALTH -> sendState(state.copy(health = !state.health), if (!state.health) "Health: Đang bật" else "Health: Đang tắt")
                                                    ExtraFn.LR_WIND_MODE -> sendState(state.copy(lrWind = !state.lrWind), if (!state.lrWind) "LR_WIND_MODE: Đang bật" else "LR_WIND_MODE: Đang tắt")
                                                    ExtraFn.MUTE -> sendState(state.copy(mute = !state.mute), if (!state.mute) "MUTE: Đang bật" else "MUTE: Đang tắt")
                                                    else -> {}
                                                }
                                            },
                                            color = if (!fn.verified) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else if (isOn) blue else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(fn.raw, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (!fn.verified) Color.Gray else if (isOn) Color.White else MaterialTheme.colorScheme.onSurface)
                                                Text(sub, fontSize = 10.sp, color = if (!fn.verified) Color(0xFFD84315) else if (isOn) Color.White.copy(alpha = 0.9f) else Color.Gray)
                                            }
                                        }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }

                // Hộp thoại Hẹn giờ
                if (showTimer) {
                    AlertDialog(
                        onDismissRequest = { showTimer = false },
                        title = { Text("Bộ hẹn giờ (Timer)") },
                        text = {
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0, 1, 2, 4, 6, 8, 12).forEach { h ->
                                    Button(onClick = { showTimer = false; sendState(state.copy(timerHours = h), if (h == 0) "Hẹn giờ: Đang tắt" else "Hẹn giờ: ${h}h") }) {
                                        Text(if (h == 0) "Tắt" else "${h}h")
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showTimer = false }) { Text("Đóng") } }
                    )
                }
            }
        }
    }
}

@Composable
fun InfoCol(v: String, l: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(v, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Text(l, fontSize = 12.sp, color = Color.Gray)
}

@Composable
fun BigPillButton(title: String, sub: String, icon: ImageVector, active: Boolean, activeColor: Color, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) activeColor else MaterialTheme.colorScheme.surface, label = "bg")
    val fg = if (active) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(modifier = modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).clickable(onClick = onClick), color = bg, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, title, tint = fg)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
                Text(sub, fontSize = 11.sp, color = fg.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
fun CircleIconBtn(title: String, sub: String?, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, label = "c")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, title, tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
        Text(title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        if (sub != null) Text(sub, fontSize = 9.sp, color = Color.Gray, maxLines = 1)
    }
}
         
