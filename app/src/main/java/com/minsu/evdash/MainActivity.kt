package com.minsu.evdash

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalClipboardManager
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
private val CARD_BG = Color(0xFF2C2C2E)

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
    private var bleLog by mutableStateOf("연결 버튼을 눌러 BMS를 찾으세요.")
    private var scanResults by mutableStateOf<List<BleDevice>>(emptyList())
    private var isScanning by mutableStateOf(false)
    private var connectedName by mutableStateOf("")

    private val prefs by lazy { getSharedPreferences("evdash", Context.MODE_PRIVATE) }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            gpsTracker.start()
            autoReconnect()
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
            onConnectionState = { connected ->
                bleConnected = connected
                if (!connected) connectedName = ""
            },
            onLog = { msg -> bleLog = msg },
            onScanResults = { list -> scanResults = list },
            onScanningChanged = { s -> isScanning = s },
            onDeviceConnected = { name, address ->
                connectedName = name
                // 다음에 앱 켜면 자동으로 붙게 기억해둔다
                prefs.edit().putString("last_address", address)
                    .putString("last_name", name).apply()
            }
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
                }
            )
        }

        requestNeededPermissions()
    }

    /** 저장된 기기가 있으면 앱 켜자마자 자동 연결 */
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
    speedKmh: Float,
    bleConnected: Boolean,
    bleLog: String,
    scanResults: List<BleDevice>,
    isScanning: Boolean,
    connectedName: String,
    savedName: String?,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPickDevice: (BleDevice) -> Unit,
    onConnectByAddress: (String) -> Unit,
    onForgetDevice: () -> Unit
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

                HorizontalBar(
                    fraction = animBattery / 100f,
                    color = batteryAccent,
                    thickness = barH,
                    modifier = Modifier.fillMaxWidth(0.88f)
                )

                Spacer(modifier = Modifier.height((h * 0.05f).dp))

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

                VerticalBar(
                    fraction = animPower / POWER_GAUGE_MAX,
                    color = powerAccent,
                    thickness = barW,
                    modifier = Modifier.fillMaxHeight(0.62f)
                )
            }
        }

        // 좌상단 상태 표시 + 연결 버튼
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (bleConnected) GREEN else RED, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (bleConnected)
                    "$connectedName (BMS SOC ${"%.0f".format(soc)}%)"
                else "BMS 미연결",
                color = Color.Gray,
                fontSize = 12.sp,
                maxLines = 1
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
        Surface(
            color = Color(0xFF1C1C1E),
            shape = RoundedCornerShape(16.dp)
        ) {
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
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(bleLog))
                    }) {
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

/** 스캔 목록의 기기 한 줄 */
@Composable
fun DeviceRow(device: BleDevice, onClick: () -> Unit) {
    Surface(
        color = CARD_BG,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
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
            Text(
                "${device.rssi} dBm",
                color = LABEL_GRAY,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
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
