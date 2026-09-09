package com.minsu.evdash

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            GaugePanel(
                label = "전압",
                value = "%.1f".format(voltage),
                unit = "V",
                accentColor = Color(0xFF4CD964),
                modifier = Modifier.weight(1f)
            )
            GaugeDivider()
            GaugePanel(
                label = "전류",
                value = "%.1f".format(current),
                unit = "A",
                accentColor = Color(0xFFFF9500),
                modifier = Modifier.weight(1f)
            )
            GaugeDivider()
            GaugePanel(
                label = "GPS 속도",
                value = "%.0f".format(speedKmh),
                unit = "km/h",
                accentColor = Color(0xFF32ADE6),
                modifier = Modifier.weight(1f)
            )
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
                        if (bleConnected) Color(0xFF4CD964) else Color(0xFFFF3B30),
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (bleConnected) "BMS 연결됨 (SOC ${"%.0f".format(soc)}%)" else "BMS 미연결",
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

@Composable
fun GaugePanel(
    label: String,
    value: String,
    unit: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = accentColor,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = unit,
                color = accentColor.copy(alpha = 0.7f),
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
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
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
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
