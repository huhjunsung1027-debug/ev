package com.minsu.evdash

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

// ═════════════════════════════════════════════════════════════
//  설정값 - 여기 숫자만 바꾸면 전체가 따라감
// ═════════════════════════════════════════════════════════════

/** 배터리 직렬 셀 수. 52V 팩 = 14S (3.7V × 14 = 51.8V) */
private const val CELL_COUNT = 14

/** 출력 게이지(상단 LED 바) 최대 눈금(W). 차 최고출력에 맞춰 조절. */
private const val POWER_GAUGE_MAX = 12000f

/** 출력 구간별 색상 경계(W) */
private const val PWR_GREEN_MAX = 6000f
private const val PWR_YELLOW_MAX = 8000f
private const val PWR_ORANGE_MAX = 9500f

/** 이보다 큰 음수 전류가 흐르면 충전/회생제동으로 본다 (A) */
private const val CHARGING_THRESHOLD_A = 0.5f

/**
 * 배터리 온도 경계(℃).
 * 리튬이온은 방전 0~60℃, 충전 0~45℃가 일반적인 사용 범위다.
 */
private const val TEMP_WARN_C = 45f
private const val TEMP_ALERT_C = 55f
private const val TEMP_COLD_C = 0f

/** 상단 LED 바 개수 */
private const val LED_COUNT = 20

private val GREEN = Color(0xFF34D058)
private val YELLOW = Color(0xFFFFD60A)
private val ORANGE = Color(0xFFFF9500)
private val RED = Color(0xFFFF3B30)
private val CYAN = Color(0xFF32ADE6)

private val PANEL = Color(0xFF17171A)        // 패널 배경
private val CELL_LABEL = Color(0xFF3A3A3E)   // 표 라벨칸
private val CELL_VALUE = Color(0xFF0E0E10)   // 표 값칸
private val LED_OFF = Color(0xFF232326)
private val LABEL_GRAY = Color(0xFF9A9AA0)
private val CARD_BG = Color(0xFF2C2C2E)

/** 리튬이온 셀 전압 → 잔량(%) 방전곡선 */
private val SOC_CURVE = listOf(
    4.20f to 100f, 4.10f to 92f, 4.00f to 85f, 3.90f to 74f,
    3.85f to 68f, 3.80f to 62f, 3.75f to 56f, 3.70f to 50f,
    3.65f to 44f, 3.60f to 38f, 3.55f to 32f, 3.50f to 26f,
    3.45f to 20f, 3.40f to 15f, 3.30f to 9f, 3.20f to 5f,
    3.00f to 0f
)

fun voltageToPercent(packVoltage: Float): Float {
    if (packVoltage <= 0f) return 0f
    val cell = packVoltage / CELL_COUNT
    if (cell >= SOC_CURVE.first().first) return 100f
    if (cell <= SOC_CURVE.last().first) return 0f
    for (i in 0 until SOC_CURVE.size - 1) {
        val (vHi, pHi) = SOC_CURVE[i]
        val (vLo, pLo) = SOC_CURVE[i + 1]
        if (cell <= vHi && cell >= vLo) {
            val t = (cell - vLo) / (vHi - vLo)
            return pLo + t * (pHi - pLo)
        }
    }
    return 0f
}

fun powerColorFor(watt: Float): Color = when {
    watt <= PWR_GREEN_MAX -> GREEN
    watt <= PWR_YELLOW_MAX -> YELLOW
    watt <= PWR_ORANGE_MAX -> ORANGE
    else -> RED
}

fun batteryColorFor(pct: Float): Color = when {
    pct > 50f -> GREEN
    pct > 30f -> YELLOW
    pct > 15f -> ORANGE
    else -> RED
}

fun tempColorFor(c: Float): Color = when {
    c >= TEMP_ALERT_C -> RED
    c >= TEMP_WARN_C -> YELLOW
    c < TEMP_COLD_C -> YELLOW
    else -> GREEN
}

fun tempLabelFor(c: Float): String = when {
    c >= TEMP_ALERT_C -> "경보"
    c >= TEMP_WARN_C -> "주의"
    c < TEMP_COLD_C -> "저온"
    else -> "정상"
}

// ═════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: DalyBleManager
    private lateinit var gpsTracker: GpsSpeedTracker

    private var voltage by mutableStateOf(0f)
    private var current by mutableStateOf(0f)
    private var soc by mutableStateOf(0f)
    private var tempC by mutableStateOf(Float.NaN)
    private var speedKmh by mutableStateOf(0f)
    private var maxSpeed by mutableStateOf(0f)
    private var bleConnected by mutableStateOf(false)
    private var bleLog by mutableStateOf("연결 버튼을 눌러 BMS를 찾으세요.")
    private var scanResults by mutableStateOf<List<BleDevice>>(emptyList())
    private var isScanning by mutableStateOf(false)
    private var connectedName by mutableStateOf("")
    private var gpsActive by mutableStateOf(false)

    private val prefs by lazy { getSharedPreferences("evdash", Context.MODE_PRIVATE) }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            gpsTracker.start()
            gpsActive = true
            autoReconnect()
        } else {
            bleLog = "권한이 거부되어 BLE/GPS를 사용할 수 없습니다."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        bleManager = DalyBleManager(
            context = this,
            onData = { v, c, s -> voltage = v; current = c; soc = s },
            onTemperature = { t -> tempC = t },
            onConnectionState = { connected ->
                bleConnected = connected
                if (!connected) connectedName = ""
            },
            onLog = { msg -> bleLog = msg },
            onScanResults = { list -> scanResults = list },
            onScanningChanged = { s -> isScanning = s },
            onDeviceConnected = { name, address ->
                connectedName = name
                prefs.edit().putString("last_address", address)
                    .putString("last_name", name).apply()
            }
        )
        gpsTracker = GpsSpeedTracker(this) { v ->
            speedKmh = v
            if (v > maxSpeed) maxSpeed = v
        }

        setContent {
            DashboardScreen(
                voltage = voltage,
                current = current,
                soc = soc,
                tempC = tempC,
                speedKmh = speedKmh,
                maxSpeed = maxSpeed,
                bleConnected = bleConnected,
                gpsActive = gpsActive,
                bleLog = bleLog,
                scanResults = scanResults,
                isScanning = isScanning,
                connectedName = connectedName,
                savedName = prefs.getString("last_name", null),
                onStartScan = { bleManager.startScan() },
                onStopScan = { bleManager.stopScan() },
                onPickDevice = { dev -> bleManager.connectToDevice(dev) },
                onConnectByAddress = { addr -> bleManager.connectToAddress(addr) },
                onForgetDevice = {
                    prefs.edit().remove("last_address").remove("last_name").apply()
                    bleLog = "기억된 기기를 지웠습니다."
                },
                onResetMax = { maxSpeed = 0f }
            )
        }

        requestNeededPermissions()
    }

    private fun autoReconnect() {
        val addr = prefs.getString("last_address", null) ?: return
        val name = prefs.getString("last_name", "저장된 기기")
        bleLog = "$name 에 자동 연결 중..."
        bleManager.connectToAddress(addr)
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(android.Manifest.permission.BLUETOOTH_SCAN)
            perms.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        requestPermissions.launch(perms.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.disconnect()
        gpsTracker.stop()
    }
}

@Composable
fun DashboardScreen(
    voltage: Float,
    current: Float,
    soc: Float,
    tempC: Float,
    speedKmh: Float,
    maxSpeed: Float,
    bleConnected: Boolean,
    gpsActive: Boolean,
    bleLog: String,
    scanResults: List<BleDevice>,
    isScanning: Boolean,
    connectedName: String,
    savedName: String?,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPickDevice: (BleDevice) -> Unit,
    onConnectByAddress: (String) -> Unit,
    onForgetDevice: () -> Unit,
    onResetMax: () -> Unit
) {
    var showConnectDialog by remember { mutableStateOf(false) }

    val power = voltage * current
    val powerAbs = abs(power)
    val powerAccent = powerColorFor(powerAbs)
    val batteryPct = voltageToPercent(voltage)
    val batteryAccent = batteryColorFor(batteryPct)
    val isCharging = current < -CHARGING_THRESHOLD_A
    val powerDisplayColor = if (isCharging) GREEN else powerAccent

    val animBattery by animateFloatAsState(targetValue = batteryPct, label = "battery")
    val animPower by animateFloatAsState(targetValue = powerAbs, label = "power")

    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), repeatMode = RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val h = maxHeight.value
        val w = maxWidth.value

        val ledSize = (h * 0.055f).coerceIn(10f, 34f)   // 상단 출력 바 높이
        val logoH = (h * 0.105f).coerceIn(22f, 70f)
        val rowH = (h * 0.082f).coerceIn(20f, 54f)
        val chipText = (h * 0.038f).coerceIn(9f, 22f)
        val cellText = (h * 0.048f).coerceIn(11f, 28f)
        val speedSize = (h * 0.30f).coerceIn(64f, 190f)
        val pctSize = (h * 0.155f).coerceIn(34f, 100f)
        val midSize = (h * 0.105f).coerceIn(24f, 68f)
        val tinyText = (h * 0.034f).coerceIn(8f, 20f)
        val gap = (h * 0.018f).coerceIn(3f, 12f)

        Column(modifier = Modifier.fillMaxSize()) {

            // ══════ 상단 출력 바 ══════
            // 패딩 밖에 둬야 화면 좌우 끝까지 꽉 찬다
            PowerBar(
                fraction = animPower / POWER_GAUGE_MAX,
                barHeight = ledSize,
                overLimit = powerAbs > PWR_ORANGE_MAX,
                pulseAlpha = pulseAlpha
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)          // 출력 바가 차지한 높이를 뺀 나머지
                    .padding(
                        start = (w * 0.014f).dp,
                        end = (w * 0.014f).dp,
                        top = (h * 0.018f).dp,
                        bottom = (h * 0.022f).dp
                    )
            ) {

            // ══════ 팀 로고 ══════
            Image(
                painter = painterResource(id = R.drawable.team_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(logoH.dp)
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(gap.dp))

            // ══════ 본체 3분할 ══════
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {

                // ───── 왼쪽: 상태칩 + 데이터 표 ─────
                Row(modifier = Modifier.weight(1.08f)) {

                    Column(modifier = Modifier.width((w * 0.062f).coerceIn(38f, 96f).dp)) {
                        StatusChip(
                            "BMS",
                            if (bleConnected) "ON" else "OFF",
                            if (bleConnected) GREEN else RED,
                            chipText, rowH,
                            onClick = { showConnectDialog = true }
                        )
                        Spacer(modifier = Modifier.height((gap * 0.5f).dp))
                        StatusChip(
                            "GPS",
                            if (gpsActive) "ON" else "OFF",
                            if (gpsActive) GREEN else LABEL_GRAY,
                            chipText, rowH
                        )
                        Spacer(modifier = Modifier.height((gap * 0.5f).dp))
                        StatusChip(
                            "MODE",
                            if (isCharging) "CHG" else "RUN",
                            if (isCharging) GREEN else CYAN,
                            chipText, rowH
                        )
                    }

                    Spacer(modifier = Modifier.width((gap * 0.6f).dp))

                    Column(modifier = Modifier.weight(1f)) {
                        DataRow("전압", "%.1f".format(voltage), "V", Color.White, cellText, rowH)
                        Spacer(modifier = Modifier.height(2.dp))
                        DataRow(
                            "전류", "%.1f".format(current), "A",
                            if (isCharging) GREEN else Color.White, cellText, rowH
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        DataRow("SOC", "%.0f".format(soc), "%", Color.White, cellText, rowH)
                        Spacer(modifier = Modifier.height(2.dp))
                        DataRow(
                            "온도",
                            if (tempC.isNaN()) "--" else "%.0f".format(tempC),
                            "\u2103",
                            if (tempC.isNaN()) LABEL_GRAY else tempColorFor(tempC),
                            cellText, rowH
                        )
                    }
                }

                Spacer(modifier = Modifier.width((gap * 0.8f).dp))

                // ───── 가운데: 속도 / 출력 ─────
                Column(
                    modifier = Modifier.weight(0.92f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 속도 (히어로)
                    Panel(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "%.0f".format(speedKmh),
                                color = Color.White,
                                fontSize = speedSize.sp,
                                lineHeight = (speedSize * 1.05f).sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                "km/h",
                                color = LABEL_GRAY,
                                fontSize = tinyText.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height((gap * 0.5f).dp))

                    // 출력 (레퍼런스의 TYRE PRESS 자리 - 테두리 강조)
                    TitledBox(
                        title = "출력",
                        accent = powerDisplayColor,
                        titleSize = tinyText,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.padding(vertical = (gap * 0.3f).dp)
                        ) {
                            Text(
                                "%.0f".format(power),
                                color = powerDisplayColor,
                                fontSize = midSize.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                "W",
                                color = powerDisplayColor.copy(alpha = 0.7f),
                                fontSize = tinyText.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 3.dp, bottom = 3.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width((gap * 0.8f).dp))

                // ───── 오른쪽: 배터리 게이지 / 온도 / 최고속도 ─────
                Column(modifier = Modifier.weight(1.08f)) {

                    // 배터리 잔량 블록
                    Panel(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            HeaderBar(
                                if (isCharging) "배터리 충전 중" else "배터리 잔량",
                                tinyText, rowH * 0.62f
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    if (isCharging) {
                                        BoltIcon(
                                            sizeDp = pctSize * 0.55f,
                                            color = GREEN.copy(alpha = pulseAlpha),
                                            modifier = Modifier.padding(
                                                end = 5.dp,
                                                bottom = (pctSize * 0.10f).dp
                                            )
                                        )
                                    }
                                    Text(
                                        "%.0f".format(batteryPct),
                                        color = batteryAccent,
                                        fontSize = pctSize.sp,
                                        lineHeight = (pctSize * 1.05f).sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        "%",
                                        color = batteryAccent.copy(alpha = 0.7f),
                                        fontSize = (tinyText * 1.15f).sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 3.dp, bottom = 5.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height((gap * 0.5f).dp))
                                HorizontalBar(
                                    fraction = animBattery / 100f,
                                    color = batteryAccent,
                                    thickness = (rowH * 0.30f).coerceIn(6f, 18f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height((gap * 0.5f).dp))

                    // 온도 상태 + 최고속도
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TitledBox(
                            title = "온도",
                            accent = if (tempC.isNaN()) LABEL_GRAY else tempColorFor(tempC),
                            titleSize = tinyText,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (tempC.isNaN()) "--" else tempLabelFor(tempC),
                                color = if (tempC.isNaN()) LABEL_GRAY else tempColorFor(tempC),
                                fontSize = (tinyText * 1.35f).sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.padding(vertical = (gap * 0.35f).dp)
                            )
                        }

                        Spacer(modifier = Modifier.width((gap * 0.5f).dp))

                        TitledBox(
                            title = "최고속도",
                            accent = CYAN,
                            titleSize = tinyText,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onResetMax() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                modifier = Modifier.padding(vertical = (gap * 0.35f).dp)
                            ) {
                                Text(
                                    "%.0f".format(maxSpeed),
                                    color = CYAN,
                                    fontSize = (tinyText * 1.35f).sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    "km/h",
                                    color = CYAN.copy(alpha = 0.6f),
                                    fontSize = (tinyText * 0.8f).sp,
                                    modifier = Modifier.padding(start = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (showConnectDialog) {
        ConnectDialog(
            bleLog = bleLog,
            scanResults = scanResults,
            isScanning = isScanning,
            savedName = savedName,
            onDismiss = { onStopScan(); showConnectDialog = false },
            onStartScan = onStartScan,
            onPickDevice = { dev -> onPickDevice(dev); showConnectDialog = false },
            onConnectAddress = { addr -> onConnectByAddress(addr); showConnectDialog = false },
            onForgetDevice = onForgetDevice
        )
    }
}

// ═══════════════════ 계기판 구성요소 ═══════════════════

/**
 * 상단 출력 바. 화면 좌우 끝까지 꽉 차게 그린다.
 * 왼쪽부터 차오르고 구간(초록-노랑-주황-빨강)별로 색이 바뀐다.
 * 마지막 칸은 부분적으로 차오르게 해서 눈금 사이에서도 변화가 보인다.
 */
@Composable
fun PowerBar(
    fraction: Float,
    barHeight: Float,
    overLimit: Boolean,
    pulseAlpha: Float
) {
    val lit = fraction.coerceIn(0f, 1f) * LED_COUNT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight.dp)
            .background(Color.Black)
    ) {
        for (i in 0 until LED_COUNT) {
            val zoneColor = when {
                i < LED_COUNT * 0.50f -> GREEN
                i < LED_COUNT * 0.70f -> YELLOW
                i < LED_COUNT * 0.87f -> ORANGE
                else -> RED
            }
            // 이 칸이 얼마나 차 있나 (0~1). 마지막 칸이 부분적으로 찬다.
            val cellFill = (lit - i).coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 0.7.dp)
                    .background(LED_OFF)
            ) {
                if (overLimit) {
                    // 한계 초과 - 전체가 빨갛게 깜빡인다
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(RED.copy(alpha = pulseAlpha))
                    )
                } else if (cellFill > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(cellFill)
                            .fillMaxHeight()
                            .background(zoneColor)
                    )
                }
            }
        }
    }
}

/** 테두리만 있는 작은 상태 표시칩 */
@Composable
fun StatusChip(
    label: String,
    value: String,
    accent: Color,
    textSize: Float,
    height: Float,
    onClick: (() -> Unit)? = null
) {
    val base = Modifier
        .fillMaxWidth()
        .height(height.dp)
        .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
    Box(
        modifier = if (onClick != null) base.clickable { onClick() } else base,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = LABEL_GRAY,
                fontSize = (textSize * 0.8f).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                value,
                color = accent,
                fontSize = textSize.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/** 회색 라벨칸 + 어두운 값칸으로 된 표 한 줄 */
@Composable
fun DataRow(
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
    textSize: Float,
    height: Float
) {
    Row(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                .background(CELL_LABEL)
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                label,
                color = Color.White,
                fontSize = (textSize * 0.88f).sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        Row(
            modifier = Modifier
                .weight(1.15f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(CELL_VALUE)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                value,
                color = valueColor,
                fontSize = textSize.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )
            Text(
                unit,
                color = valueColor.copy(alpha = 0.6f),
                fontSize = (textSize * 0.7f).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

/** 기본 패널 (모서리 둥근 어두운 박스) */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(PANEL),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** 제목줄이 색으로 강조된 박스 */
@Composable
fun TitledBox(
    title: String,
    accent: Color,
    titleSize: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .background(PANEL),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(accent.copy(alpha = 0.18f))
                .padding(vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                title,
                color = accent,
                fontSize = (titleSize * 0.92f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        content()
    }
}

/** 패널 상단 회색 제목줄 */
@Composable
fun HeaderBar(title: String, textSize: Float, height: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(CELL_LABEL),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = textSize.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** 충전 표시용 번개 아이콘 */
@Composable
fun BoltIcon(sizeDp: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = (sizeDp * 0.62f).dp, height = sizeDp.dp)) {
        val w = size.width
        val hh = size.height
        val path = Path().apply {
            moveTo(w * 0.62f, 0f)
            lineTo(w * 0.08f, hh * 0.58f)
            lineTo(w * 0.45f, hh * 0.58f)
            lineTo(w * 0.34f, hh)
            lineTo(w * 0.94f, hh * 0.40f)
            lineTo(w * 0.55f, hh * 0.40f)
            close()
        }
        drawPath(path, color)
    }
}

/** 가로 막대 게이지 */
@Composable
fun HorizontalBar(
    fraction: Float,
    color: Color,
    thickness: Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape((thickness / 2f).dp)
    Box(
        modifier = modifier
            .height(thickness.dp)
            .clip(shape)
            .background(LED_OFF)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(shape)
                .background(color)
        )
    }
}

// ═══════════════════ 연결 다이얼로그 ═══════════════════

@Composable
fun ConnectDialog(
    bleLog: String,
    scanResults: List<BleDevice>,
    isScanning: Boolean,
    savedName: String?,
    onDismiss: () -> Unit,
    onStartScan: () -> Unit,
    onPickDevice: (BleDevice) -> Unit,
    onConnectAddress: (String) -> Unit,
    onForgetDevice: () -> Unit
) {
    var address by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .widthIn(max = 420.dp)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "BMS 연결",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = GREEN
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(onClick = onStartScan, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isScanning) "검색 중..." else "주변 기기 검색")
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (scanResults.isEmpty()) {
                    Text(
                        bleLog,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Text(
                        "기기를 눌러 연결 (DL- 로 시작하는 게 BMS)",
                        color = LABEL_GRAY,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    scanResults.forEach { dev ->
                        DeviceRow(dev) { onPickDevice(dev) }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(bleLog)) }) {
                        Text("로그 복사", fontSize = 12.sp, color = LABEL_GRAY)
                    }
                    TextButton(onClick = { showManual = !showManual }) {
                        Text("주소 직접 입력", fontSize = 12.sp, color = LABEL_GRAY)
                    }
                    if (savedName != null) {
                        TextButton(onClick = onForgetDevice) {
                            Text("기기 잊기", fontSize = 12.sp, color = LABEL_GRAY)
                        }
                    }
                }

                if (showManual) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        placeholder = { Text("XX:XX:XX:XX:XX:XX") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { if (address.isNotBlank()) onConnectAddress(address.trim()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("주소로 연결")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("닫기")
                }
            }
        }
    }
}

@Composable
fun DeviceRow(device: BleDevice, onClick: () -> Unit) {
    Surface(
        color = CARD_BG,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.name,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    if (device.isLikelyBms) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(color = GREEN, shape = RoundedCornerShape(4.dp)) {
                            Text(
                                "BMS",
                                color = Color.Black,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    device.address,
                    color = LABEL_GRAY,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
            Text("${device.rssi} dBm", color = LABEL_GRAY, fontSize = 11.sp, maxLines = 1)
        }
    }
}
