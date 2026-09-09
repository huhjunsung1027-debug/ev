package com.minsu.evdash

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/** 출력 게이지 최대 눈금(W). 차 최고출력에 맞춰 조절. */
private const val POWER_GAUGE_MAX = 12000f

/** 출력 구간별 색상 경계(W) */
private const val PWR_GREEN_MAX = 6000f
private const val PWR_YELLOW_MAX = 8000f
private const val PWR_ORANGE_MAX = 9500f

private val GREEN = Color(0xFF4CD964)
private val YELLOW = Color(0xFFFFD60A)
private val ORANGE = Color(0xFFFF9500)
private val RED = Color(0xFFFF3B30)
private val TRACK = Color(0xFF2C2C2E)
private val LABEL_GRAY = Color(0xFF8E8E93)

/**
 * 리튬이온 셀 전압 → 잔량(%) 방전곡선.
 * 단순 비례(선형)로 하면 중간 구간이 평평해서 실제와 크게 어긋난다.
 */
private val SOC_CURVE = listOf(
    4.20f to 100f, 4.10f to 92f, 4.00f to 85f, 3.90f to 74f,
    3.85f to 68f, 3.80f to 62f, 3.75f to 56f, 3.70f to 50f,
    3.65f to 44f, 3.60f to 38f, 3.55f to 32f, 3.50f to 26f,
    3.45f to 20f, 3.40f to 15f, 3.30f to 9f, 3.20f to 5f,
    3.00f to 0f
)

/** 팩 전압 → 잔량(%) */
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

/** 출력(W) 구간별 색상. 회생제동 음수는 절댓값으로 판정. */
fun powerColorFor(watt: Float): Color = when {
    watt <= PWR_GREEN_MAX -> GREEN
    watt <= PWR_YELLOW_MAX -> YELLOW
    watt <= PWR_ORANGE_MAX -> ORANGE
    else -> RED
}

/** 배터리 잔량 색상 */
fun batteryColorFor(pct: Float): Color = when {
    pct > 50f -> GREEN
    pct > 20f -> YELLOW
    else -> RED
}

// ═════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: DalyBleManager
    private lateinit var gpsTracker: GpsSpeedTracker

    private var voltage by mutableStateOf(0f)
    private var current by mutableStateOf(0f)
    private var soc by mutableStateOf(0f)
    private var speedKmh by mutableStateOf(0f)
    private var bleConnected by mutableStateOf(false)
    private var bleLog by mutableStateOf("대기 중")

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            gpsTracker.start()
        } else {
            bleLog = "권한이 거부되어 BLE/GPS를 사용할 수 없습니다."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 화면 항상 켜짐 (계기판이므로)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 완전 몰입형 전체화면
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        bleManager = DalyBleManager(
            context = this,
            onData = { v, c, s -> voltage = v; current = c; soc = s },
            onConnectionState = { connected -> bleConnected = connected },
            onLog = { msg -> bleLog = msg }
        )
        gpsTracker = GpsSpeedTracker(this) { speedKmh = it }

        setContent {
            DashboardScreen(
                voltage = voltage,
                current = current,
                soc = soc,
                speedKmh = speedKmh,
                bleConnected = bleConnected,
                bleLog = bleLog,
                onRequestPermissions = { requestNeededPermissions() },
                onScanConnect = { bleManager.startScanAndConnect() },
                onConnectByAddress = { addr -> bleManager.connectToAddress(addr) }
            )
        }

        requestNeededPermissions()
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
    speedKmh: Float,
    bleConnected: Boolean,
    bleLog: String,
    onRequestPermissions: () -> Unit,
    onScanConnect: () -> Unit,
    onConnectByAddress: (String) -> Unit
) {
    var showConnectDialog by remember { mutableStateOf(false) }

    val power = voltage * current
    val powerAbs = abs(power)
    val powerAccent = powerColorFor(powerAbs)

    val batteryPct = voltageToPercent(voltage)
    val batteryAccent = batteryColorFor(batteryPct)

    // 게이지가 뚝뚝 끊기지 않게 부드럽게 이동
    val animBattery by animateFloatAsState(targetValue = batteryPct, label = "battery")
    val animPower by animateFloatAsState(targetValue = powerAbs, label = "power")

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 화면 높이에 맞춰 글자 크기 자동 조절 (폰/태블릿/에뮬 전부 대응)
        val h = maxHeight.value

        val pctSize = (h * 0.25f).coerceIn(56f, 170f)
        val speedSize = (h * 0.40f).coerceIn(80f, 260f)
        val powerSize = (h * 0.20f).coerceIn(46f, 130f)
        val labelSize = (h * 0.055f).coerceIn(14f, 30f)
        val unitSize = (h * 0.050f).coerceIn(13f, 28f)
        val smallSize = (h * 0.070f).coerceIn(16f, 38f)
        val barH = (h * 0.045f).coerceIn(8f, 26f)
        val barW = (h * 0.050f).coerceIn(10f, 30f)

        Row(modifier = Modifier.fillMaxSize()) {

            // ══ 왼쪽: 배터리 잔량 (메인) + 전압/전류 ══
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "배터리",
                    color = LABEL_GRAY,
                    fontSize = labelSize.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "%.0f".format(batteryPct),
                        color = batteryAccent,
                        fontSize = pctSize.sp,
                        lineHeight = (pctSize * 1.15f).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "%",
                        color = batteryAccent.copy(alpha = 0.75f),
                        fontSize = unitSize.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(
                            start = 3.dp,
                            bottom = (pctSize * 0.10f).dp
                        )
                    )
                }

                Spacer(modifier = Modifier.height((h * 0.025f).dp))

                // 배터리 게이지
                HorizontalBar(
                    fraction = animBattery / 100f,
                    color = batteryAccent,
                    thickness = barH,
                    modifier = Modifier.fillMaxWidth(0.88f)
                )

                Spacer(modifier = Modifier.height((h * 0.05f).dp))

                // 전압 / 전류 (작게 아래에)
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    SmallReading("%.1f".format(voltage), "V", GREEN, smallSize, unitSize * 0.8f)
                    Spacer(modifier = Modifier.width((h * 0.045f).dp))
                    SmallReading("%.1f".format(current), "A", ORANGE, smallSize, unitSize * 0.8f)
                }
            }

            GaugeDivider()

            // ══ 가운데: 속도 (하얀색, 제일 크게) ══
            Column(
                modifier = Modifier
                    .weight(1.4f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "속도",
                    color = LABEL_GRAY,
                    fontSize = (labelSize * 1.2f).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = "%.0f".format(speedKmh),
                    color = Color.White,
                    fontSize = speedSize.sp,
                    lineHeight = (speedSize * 1.15f).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "km/h",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = (unitSize * 1.3f).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }

            GaugeDivider()

            // ══ 오른쪽: 출력(W) + 세로 게이지 ══
            Row(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "출력",
                        color = LABEL_GRAY,
                        fontSize = labelSize.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        text = "%.0f".format(power),
                        color = powerAccent,
                        fontSize = powerSize.sp,
                        lineHeight = (powerSize * 1.15f).sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = "W",
                        color = powerAccent.copy(alpha = 0.75f),
                        fontSize = unitSize.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width((h * 0.035f).dp))

                // 출력 게이지 (세로)
                VerticalBar(
                    fraction = animPower / POWER_GAUGE_MAX,
                    color = powerAccent,
                    thickness = barW,
                    modifier = Modifier.fillMaxHeight(0.62f)
                )
            }
        }

        // 좌상단 상태 표시 + 연결 버튼 (평소엔 작고 눈에 안 띄게)
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (bleConnected) GREEN else RED,
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (bleConnected) "BMS 연결됨 (BMS SOC ${"%.0f".format(soc)}%)" else "BMS 미연결",
                color = Color.Gray,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            TextButton(onClick = { showConnectDialog = true }) {
                Text("연결", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }

    if (showConnectDialog) {
        ConnectDialog(
            bleLog = bleLog,
            onDismiss = { showConnectDialog = false },
            onScan = { onRequestPermissions(); onScanConnect(); showConnectDialog = false },
            onConnectAddress = { addr -> onConnectByAddress(addr); showConnectDialog = false }
        )
    }
}

/** 작은 숫자 + 단위 (전압/전류용) */
@Composable
fun SmallReading(
    value: String,
    unit: String,
    accentColor: Color,
    valueSize: Float,
    unitSize: Float
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            color = accentColor,
            fontSize = valueSize.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = unit,
            color = accentColor.copy(alpha = 0.7f),
            fontSize = unitSize.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(start = 2.dp, bottom = (valueSize * 0.08f).dp)
        )
    }
}

/** 가로 막대 게이지 (너비는 modifier로 지정) */
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
            .background(TRACK)
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

/** 세로 막대 게이지 (높이는 modifier로 지정) */
@Composable
fun VerticalBar(
    fraction: Float,
    color: Color,
    thickness: Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape((thickness / 2f).dp)
    Box(
        modifier = modifier
            .width(thickness.dp)
            .clip(shape)
            .background(TRACK)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceIn(0f, 1f))
                .clip(shape)
                .background(color)
        )
    }
}

/**
 * 게이지 사이 세로 구분선.
 * (Material3에도 VerticalDivider가 있어서 이름 충돌을 피하려고 GaugeDivider로 둠)
 */
@Composable
fun GaugeDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .padding(vertical = 24.dp)
            .background(Color(0xFF333333))
    )
}

@Composable
fun ConnectDialog(
    bleLog: String,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onConnectAddress: (String) -> Unit
) {
    var address by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF1C1C1E),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("BMS 연결", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Text(bleLog, color = Color.Gray, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Text("주변 기기 스캔해서 자동 연결")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("또는 MAC 주소 직접 입력", color = Color.Gray, fontSize = 12.sp)
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text("XX:XX:XX:XX:XX:XX") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { if (address.isNotBlank()) onConnectAddress(address) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("주소로 연결")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("닫기")
                }
            }
        }
    }
}
