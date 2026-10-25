package com.nagakawa.ir

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

enum class AcMode(val label: String, val bits: Int, val hasTemp: Boolean) {
    AUTO("Tự động", 0b000, true), COOL("Mát", 0b001, true),
    HEAT("Nóng", 0b100, true), DRY("Khô", 0b010, true), FAN("Quạt", 0b110, false)
}

enum class FanSpeed(val label: String, val bits: Int) {
    AUTO("Tự động", 0b101), S1("1", 0b011), S2("2", 0b010), S3("3", 0b001)
}

enum class SwingDir(val label: String, val bits: Int) {
    UP("Lên", 0b001), MID("Giữa", 0b010), DOWN("Xuống", 0b011), SWING("Đảo gió", 0b000), OFF("Đứng yên", 0b111)
}

enum class ExtraFn(val raw: String, val verified: Boolean) {
    AUX_HEAT("Aux Heat", false), BODY_FEEL("Body Feel", false), DISPLAY("DISPLAY", true),
    ECO("ECO", true), HEALTH("Health", true), HUMIDIFICATION("Humidification", false),
    LR_WIND_MODE("LR_WIND_MODE", true), MUTE("MUTE", true), TURBO("TURBO", true)
}

class MainActivity : Activity() {
    private val irExecutor = Executors.newSingleThreadExecutor()
    private var irManager: ConsumerIrManager? = null

    private var power = true
    private var temp = 26
    private var mode = AcMode.COOL
    private var fan = FanSpeed.S3
    private var swing = SwingDir.UP
    private var sleep = false
    private var timerHours = 0
    private var display = true
    private var eco = false
    private var turbo = false
    private var health = false
    private var mute = false
    private var lrWind = false

    private var lastAction = "Khởi tạo"
    private var txStatus = "Chưa phát lệnh"
    private var lastHex = "--"

    private lateinit var tvTemp: TextView
    private lateinit var tvQuickInfo: TextView
    private lateinit var tvStatusBox: TextView
    private lateinit var tvActiveFlags: TextView
    private lateinit var btnPower: Button
    private lateinit var btnSwing: Button
    private val modeButtons = mutableMapOf<AcMode, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        irManager = getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        loadPrefs()

        val rootScroll = ScrollView(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
            setBackgroundColor(Color.parseColor("#F5F6F8"))
        }
        rootScroll.addView(mainLayout)

        // Tiêu đề & Nhiệt độ trung tâm
        mainLayout.addView(TextView(this).apply {
            text = "Nagakawa Air Conditioner (Profile AUX)"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        tvTemp = TextView(this).apply {
            textSize = 56f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 4)
        }
        mainLayout.addView(tvTemp)

        mainLayout.addView(TextView(this).apply {
            text = "Nhiệt độ cài đặt (Trạng thái dự kiến)"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        })

        tvActiveFlags = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 12)
            setTextColor(Color.parseColor("#0052CC"))
        }
        mainLayout.addView(tvActiveFlags)

        // Bảng thông tin nhanh (Chế độ | Tốc độ quạt | Hướng)
        tvQuickInfo = TextView(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }
        mainLayout.addView(tvQuickInfo)
        addSpace(mainLayout, 16)

        // Hàng nút Nguồn & Xoay
        val rowPowerSwing = row()
        btnPower = button("Nguồn") {
            power = !power
            transmitAndRefresh(if (power) "Nguồn: Đang bật" else "Nguồn: Đang tắt")
        }
        btnSwing = button("Xoay") {
            swing = if (swing == SwingDir.SWING) SwingDir.UP else SwingDir.SWING
            transmitAndRefresh("Hướng gió: ${swing.label}")
        }
        rowPowerSwing.addView(btnPower, weightParams())
        rowPowerSwing.addView(btnSwing, weightParams())
        mainLayout.addView(rowPowerSwing)
        addSpace(mainLayout, 12)

        // Thanh điều chỉnh nhiệt độ (- | Nhiệt độ | +)
        val rowTemp = row()
        rowTemp.addView(button("− Giảm 1°C") { changeTemp(-1) }, weightParams())
        rowTemp.addView(button("+ Tăng 1°C") { changeTemp(1) }, weightParams())
        mainLayout.addView(rowTemp)
        addSpace(mainLayout, 16)

        // 5 Nút Chế độ hoạt động (Tự động, Mát, Nóng, Khô, Quạt)
        val rowModes = row()
        AcMode.entries.forEach { m ->
            val b = button(m.label) {
                mode = m
                if (m != AcMode.COOL) eco = false
                if (m != AcMode.COOL && m != AcMode.HEAT) turbo = false
                transmitAndRefresh("Chế độ: ${m.label}")
            }
            modeButtons[m] = b
            rowModes.addView(b, weightParams())
        }
        mainLayout.addView(rowModes)
        addSpace(mainLayout, 12)

        // 5 Nút chức năng bổ sung (Ngủ, Hẹn giờ, Hướng, Quạt, Thêm)
        val rowAux = row()
        rowAux.addView(button("Ngủ") {
            sleep = !sleep
            transmitAndRefresh(if (sleep) "Ngủ: Đang bật" else "Ngủ: Đang tắt")
        }, weightParams())
        rowAux.addView(button("Hẹn giờ") { showTimerDialog() }, weightParams())
        rowAux.addView(button("Hướng") {
            swing = SwingDir.entries[(swing.ordinal + 1) % SwingDir.entries.size]
            transmitAndRefresh("Hướng: ${swing.label}")
        }, weightParams())
        rowAux.addView(button("Quạt") {
            fan = FanSpeed.entries[(fan.ordinal + 1) % FanSpeed.entries.size]
            transmitAndRefresh("Tốc độ quạt: ${fan.label}")
        }, weightParams())
        rowAux.addView(button("Thêm") { showExtraFunctionsDialog() }, weightParams())
        mainLayout.addView(rowAux)
        addSpace(mainLayout, 16)

        // Bảng 3 tầng trạng thái & Đồng bộ
        tvStatusBox = TextView(this).apply {
            textSize = 12f
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }
        mainLayout.addView(tvStatusBox)

        val rowSync = row()
        rowSync.addView(button("Phát đồng bộ lại") {
            transmitAndRefresh("Đồng bộ lại trạng thái dự kiến")
        }, weightParams())
        rowSync.addView(button("Đặt lại mặc định") {
            power = true; temp = 26; mode = AcMode.COOL; fan = FanSpeed.S3; swing = SwingDir.UP
            sleep = false; timerHours = 0; display = true; eco = false; turbo = false
            health = false; mute = false; lrWind = false
            lastAction = "Đặt lại mặc định"; txStatus = "Chưa phát lệnh"
            savePrefs(); updateUi()
        }, weightParams())
        mainLayout.addView(rowSync)

        setContentView(rootScroll)
        updateUi()
    }

    private fun changeTemp(delta: Int) {
        if (!power) { toast("Máy đang ở trạng thái TẮT. Hãy bật Nguồn trước."); return }
        if (!mode.hasTemp) { toast("Chế độ ${mode.label} không hỗ trợ chỉnh nhiệt độ."); return }
        val next = temp + delta
        if (next !in 16..30) { toast("Giới hạn nhiệt độ từ 16°C đến 30°C."); return }
        temp = next
        transmitAndRefresh("Nhiệt độ: ${temp}°C")
    }

    private fun showTimerDialog() {
        val options = arrayOf("Tắt hẹn giờ (0h)", "1 giờ", "2 giờ", "4 giờ", "8 giờ", "12 giờ")
        val values = intArrayOf(0, 1, 2, 4, 8, 12)
        AlertDialog.Builder(this)
            .setTitle("Bộ hẹn giờ (Timer)")
            .setItems(options) { _, which ->
                timerHours = values[which]
                transmitAndRefresh(if (timerHours == 0) "Hẹn giờ: Đang tắt" else "Hẹn giờ: ${timerHours}h")
            }.show()
    }

    private fun showExtraFunctionsDialog() {
        val items = ExtraFn.entries.map { fn ->
            val st = when (fn) {
                ExtraFn.DISPLAY -> if (display) "Đang bật" else "Đang tắt"
                ExtraFn.ECO -> if (eco) "Đang bật" else "Đang tắt"
                ExtraFn.HEALTH -> if (health) "Đang bật" else "Đang tắt"
                ExtraFn.LR_WIND_MODE -> if (lrWind) "Đang bật" else "Đang tắt"
                ExtraFn.MUTE -> if (mute) "Đang bật" else "Đang tắt"
                ExtraFn.TURBO -> if (turbo) "Đang bật" else "Đang tắt"
                else -> "Chưa xác minh (Khóa phát IR giả)"
            }
            "${fn.raw} — [$st]"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Chức năng điều khiển khác")
            .setItems(items) { _, idx ->
                val fn = ExtraFn.entries[idx]
                if (!fn.verified) {
                    txStatus = "Đã chặn phát IR: ${fn.raw} chưa xác minh"
                    toast("${fn.raw}: Chưa xác minh mã IR trên Nagakawa")
                    updateUi()
                    return@setItems
                }
                when (fn) {
                    ExtraFn.DISPLAY -> { display = !display; transmitAndRefresh("DISPLAY: ${if (display) "Đang bật" else "Đang tắt"}", toggleDisplay = true) }
                    ExtraFn.ECO -> if (mode != AcMode.COOL) toast("ECO chỉ dùng ở chế độ Mát") else { eco = !eco; if (eco) turbo = false; transmitAndRefresh("ECO: ${if (eco) "Đang bật" else "Đang tắt"}") }
                    ExtraFn.TURBO -> if (mode != AcMode.COOL && mode != AcMode.HEAT) toast("TURBO chỉ dùng ở Mát/Nóng") else { turbo = !turbo; if (turbo) eco = false; transmitAndRefresh("TURBO: ${if (turbo) "Đang bật" else "Đang tắt"}") }
                    ExtraFn.HEALTH -> { health = !health; transmitAndRefresh("Health: ${if (health) "Đang bật" else "Đang tắt"}") }
                    ExtraFn.LR_WIND_MODE -> { lrWind = !lrWind; transmitAndRefresh("LR_WIND_MODE: ${if (lrWind) "Đang bật" else "Đang tắt"}") }
                    ExtraFn.MUTE -> { mute = !mute; transmitAndRefresh("MUTE: ${if (mute) "Đang bật" else "Đang tắt"}") }
                    else -> {}
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun transmitAndRefresh(action: String, toggleDisplay: Boolean = false) {
        lastAction = action
        savePrefs()
        val (pulses, hex) = encodeAux13Byte(toggleDisplay)
        lastHex = hex
        updateUi()

        irExecutor.execute {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val hasFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)
            val hasEmitter = runCatching { irManager?.hasIrEmitter() == true }.getOrDefault(false)
            val supports38k = runCatching {
                irManager?.carrierFrequencies?.any { 38000 in it.minFrequency..it.maxFrequency } == true
            }.getOrDefault(false)

            val resultMsg = if (!hasFeature || !hasEmitter || irManager == null) {
                "[$time] LỖI: Điện thoại không có bộ phát IR Blaster"
            } else if (!supports38k) {
                "[$time] LỖI: Phần cứng IR không hỗ trợ tần số 38kHz"
            } else {
                runCatching {
                    irManager!!.transmit(38000, pulses)
                    "[$time] Đã gửi lệnh ($action) – Chưa xác nhận từ điều hòa"
                }.getOrElse { "[$time] Lỗi phát IR: ${it.message}" }
            }

            runOnUiThread {
                txStatus = resultMsg
                updateUi()
            }
        }
    }

    private fun encodeAux13Byte(toggleDisplay: Boolean): Pair<IntArray, String> {
        val f = IntArray(13)
        f[0] = 0xC3
        f[1] = (((temp.coerceIn(16, 30) - 8) and 0x1F) shl 3) or (swing.bits and 0x07)
        f[2] = (if (lrWind) 0b000 else 0b111) shl 5
        f[4] = (fan.bits and 0x07) shl 5
        f[5] = (if (turbo && (mode == AcMode.COOL || mode == AcMode.HEAT)) 1 shl 6 else 0) or (if (mute) 1 shl 7 else 0)
        f[6] = ((mode.bits and 0x07) shl 5) or (if (sleep) 1 shl 2 else 0)
        f[7] = timerHours.coerceIn(0, 24)
        f[8] = if (f[7] > 0) (1 shl 3) else 0
        f[9] = (if (power) 1 shl 5 else 0) or (if (eco && mode == AcMode.COOL) 1 shl 4 else 0) or (if (health) 1 shl 2 else 0)
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

    private fun updateUi() {
        tvTemp.text = if (!power) "TẮT" else if (!mode.hasTemp) "--" else "${temp}°C"
        tvQuickInfo.text = "Chế độ: ${mode.label}   |   Quạt: ${fan.label}   |   Hướng: ${swing.label}"

        val flags = mutableListOf(if (power) "Nguồn: BẬT" else "Nguồn: TẮT")
        if (eco) flags.add("ECO: Đang bật")
        if (turbo) flags.add("TURBO: Đang bật")
        if (sleep) flags.add("Ngủ: Đang bật")
        if (timerHours > 0) flags.add("Hẹn giờ: ${timerHours}h")
        if (health) flags.add("Health: Đang bật")
        if (mute) flags.add("MUTE: Đang bật")
        if (lrWind) flags.add("LR_WIND: Đang bật")
        flags.add(if (display) "DISPLAY: Bật" else "DISPLAY: Tắt")
        tvActiveFlags.text = flags.joinToString(" • ")

        btnPower.text = if (power) "Nguồn (Đang bật)" else "Nguồn (Đang tắt)"
        btnPower.setBackgroundColor(if (power) Color.parseColor("#1E8E3E") else Color.parseColor("#D32F2F"))
        btnPower.setTextColor(Color.WHITE)

        val isSwinging = swing == SwingDir.SWING
        btnSwing.text = if (isSwinging) "Xoay (Đang bật)" else "Xoay (${swing.label})"
        btnSwing.setBackgroundColor(if (isSwinging) Color.parseColor("#0066FF") else Color.LTGRAY)
        btnSwing.setTextColor(if (isSwinging) Color.WHITE else Color.BLACK)

        modeButtons.forEach { (m, btn) ->
            val selected = m == mode
            btn.setBackgroundColor(if (selected) Color.parseColor("#0066FF") else Color.LTGRAY)
            btn.setTextColor(if (selected) Color.WHITE else Color.BLACK)
        }

        val hasIr = runCatching { irManager?.hasIrEmitter() == true }.getOrDefault(false)
        tvStatusBox.text =
            "1. Trạng thái giao diện (Dự kiến): $lastAction\n" +
            "2. Trạng thái lệnh IR: $txStatus\n" +
            "3. Xác nhận từ điều hòa: Chưa xác nhận (IR 1 chiều - Có thể lệch nếu dùng điều khiển vật lý)\n" +
            "• Phần cứng IR trên máy: ${if (hasIr) "Sẵn sàng (38kHz)" else "KHÔNG CÓ IR BLASTER"}\n" +
            "• Khung AUX 13-byte: $lastHex"
    }

    private fun savePrefs() = getSharedPreferences("ac", MODE_PRIVATE).edit()
        .putBoolean("p", power).putInt("t", temp).putString("m", mode.name)
        .putString("f", fan.name).putString("s", swing.name).putBoolean("sl", sleep)
        .putInt("tm", timerHours).putBoolean("d", display).putBoolean("e", eco)
        .putBoolean("tb", turbo).putBoolean("h", health).putBoolean("mt", mute).putBoolean("lr", lrWind).apply()

    private fun loadPrefs() = getSharedPreferences("ac", MODE_PRIVATE).let { p ->
        power = p.getBoolean("p", true); temp = p.getInt("t", 26).coerceIn(16, 30)
        mode = runCatching { AcMode.valueOf(p.getString("m", "COOL")!!) }.getOrDefault(AcMode.COOL)
        fan = runCatching { FanSpeed.valueOf(p.getString("f", "S3")!!) }.getOrDefault(FanSpeed.S3)
        swing = runCatching { SwingDir.valueOf(p.getString("s", "UP")!!) }.getOrDefault(SwingDir.UP)
        sleep = p.getBoolean("sl", false); timerHours = p.getInt("tm", 0); display = p.getBoolean("d", true)
        eco = p.getBoolean("e", false); turbo = p.getBoolean("tb", false); health = p.getBoolean("h", false)
        mute = p.getBoolean("mt", false); lrWind = p.getBoolean("lr", false)
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun weightParams() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(6, 6, 6, 6) }
    private fun addSpace(parent: LinearLayout, dp: Int) = parent.addView(Space(this), LinearLayout.LayoutParams(MATCH_PARENT, dp))
    private fun button(lbl: String, onClick: () -> Unit) = Button(this).apply { text = lbl; textSize = 12f; isAllCaps = false; setOnClickListener { onClick() } }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
