package com.minsu.evdash

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.OutputStream
import java.util.*
import java.util.concurrent.Executors

/** 스캔으로 발견한 BLE 기기 하나 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    /** true면 클래식 블루투스(SPP), false면 BLE */
    val isClassic: Boolean = false
) {
    /** DL- 로 시작하면 BMS 후보 */
    val isLikelyBms: Boolean
        get() = name.startsWith("DL-", ignoreCase = true) ||
                name.contains("daly", ignoreCase = true) ||
                name.contains("bms", ignoreCase = true) ||
                name.startsWith("xiaoxiang", ignoreCase = true)
}

/**
 * BMS(BLE) 연결/파싱 매니저.
 *
 * 서비스 UUID도, 통신 프로토콜도 고정하지 않는다.
 *  - UUID: Notify + Write 를 둘 다 가진 커스텀 서비스를 자동 선택
 *  - 프로토콜: 알려진 요청 프레임을 순서대로 찔러보고, 응답이 해석되는 놈으로 고정
 *
 * 갱신 속도:
 *  고정 타이머로 1초마다 쏘는 게 아니라, 응답이 오는 즉시 다음 요청을 보낸다.
 *  BMS가 답하는 만큼 최대한 빠르게 돈다 (보통 5~8Hz).
 */
class DalyBleManager(
    private val context: Context,
    private val onData: (voltage: Float, current: Float, soc: Float) -> Unit,
    private val onTemperature: (tempC: Float) -> Unit = {},
    private val onConnectionState: (connected: Boolean) -> Unit,
    private val onLog: (String) -> Unit,
    private val onScanResults: (List<BleDevice>) -> Unit = {},
    private val onScanningChanged: (Boolean) -> Unit = {},
    private val onDeviceConnected: (name: String, address: String) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "DalyBLE"

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** SPP(Serial Port Profile) 표준 UUID. 클래식 블루투스 시리얼 통신용. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val SCAN_TIMEOUT_MS = 12000L

        /** 응답 받고 다음 요청까지 최소 간격 (ms). 너무 줄이면 BMS가 못 따라온다. */
        private const val MIN_GAP_MS = 120L

        /** 이 시간 안에 응답이 없으면 같은 요청을 다시 보낸다 (ms) */
        private const val RESPONSE_TIMEOUT_MS = 1200L

        /** 프로토콜 탐색 중 다음 후보로 넘어가는 간격 (ms) */
        private const val PROBE_INTERVAL_MS = 450L

        /**
         * 조합을 한 바퀴 다 돌았는데 한 바이트도 못 받았을 때 쉬는 시간.
         * 요청 없이 알아서 데이터를 흘리는 BMS도 있어서, 잠깐 입을 다물고 들어본다.
         */
        private const val PASSIVE_LISTEN_MS = 5000L

        /** 진단 로그 갱신 최소 간격 (ms). 매 패킷마다 갱신하면 화면이 버벅인다. */
        private const val LOG_THROTTLE_MS = 500L

        /**
         * 전류 부호 뒤집기.
         *
         * BMS는 "배터리 입장"에서 값을 보낸다. 전기가 빠져나가는 방전이 음수,
         * 들어오는 충전이 양수다. 계기판에서는 반대여야 한다.
         * 밟았을 때(방전) 양수, 회생제동/충전일 때 음수.
         *
         * 화면 부호가 반대로 나오면 이 값만 false 로 바꾸면 된다.
         */
        private const val INVERT_CURRENT = true

        /** 표준(제조사 데이터 아님) 서비스 - 자동 탐색에서 제외 */
        private val STANDARD_SERVICES = setOf(
            "1800", "1801", "1804", "180a", "180f", "1811", "fe59"
        )

        /**
         * Daly 요청 프레임 만들기.
         * 형식: A5 [주소] [커맨드] [길이=08] [데이터 8바이트] [체크섬] = 총 13바이트
         */
        private fun dalyFrame(addr: Int, cmd: Int): ByteArray {
            val body = ByteArray(12)
            body[0] = 0xA5.toByte()
            body[1] = addr.toByte()
            body[2] = cmd.toByte()
            body[3] = 0x08
            // body[4..11] 은 0x00 (데이터 8바이트)
            val sum = body.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
            return body + (sum and 0xFF).toByte()
        }

        /**
         * JK BMS(Jikong) 요청 프레임.
         * AA 55 90 EB [커맨드] 00 ... (총 20바이트, 마지막은 앞 19바이트 합의 하위 1바이트)
         */
        private fun jkFrame(cmd: Int): ByteArray {
            val f = ByteArray(20)
            f[0] = 0xAA.toByte(); f[1] = 0x55.toByte()
            f[2] = 0x90.toByte(); f[3] = 0xEB.toByte()
            f[4] = cmd.toByte()
            // f[5..18] 은 0x00
            var sum = 0
            for (i in 0..18) sum += f[i].toInt() and 0xFF
            f[19] = (sum and 0xFF).toByte()
            return f
        }

        /** 찔러볼 요청 프레임 목록 (이름, 바이트) */
        private val PROBE_FRAMES: List<Pair<String, ByteArray>> by lazy {
            listOf(
                "Daly(40)" to dalyFrame(0x40, 0x90),
                "Daly(80)" to dalyFrame(0x80, 0x90),
                "JBD" to byteArrayOf(
                    0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00,
                    0xFF.toByte(), 0xFD.toByte(), 0x77
                ),
                "JBD-cell" to byteArrayOf(
                    0xDD.toByte(), 0xA5.toByte(), 0x04, 0x00,
                    0xFF.toByte(), 0xFC.toByte(), 0x77
                ),
                "Modbus(D2)" to byteArrayOf(
                    0xD2.toByte(), 0x03, 0x00, 0x00, 0x00, 0x3E,
                    0xD7.toByte(), 0xB9.toByte()
                ),
                "Modbus(01)" to byteArrayOf(
                    0x01, 0x03, 0x00, 0x00, 0x00, 0x20,
                    0x44.toByte(), 0x12.toByte()
                ),
                "JK-cell" to jkFrame(0x96),      // 셀 정보
                "JK-info" to jkFrame(0x97),      // 기기 정보
                "JK-set" to jkFrame(0x95)        // 설정 (일부 펌웨어는 이걸로 스트림 시작)
            )
        }

        /**
         * Daly 온도 조회 프레임 (커맨드 0x92).
         * 온도는 천천히 변하므로 매번이 아니라 가끔만 섞어 보낸다.
         */
        private val DALY_TEMP_FRAMES: Map<String, ByteArray> by lazy {
            mapOf(
                "Daly(40)" to dalyFrame(0x40, 0x92),
                "Daly(80)" to dalyFrame(0x80, 0x92)
            )
        }

        /** 이 횟수마다 한 번씩 온도를 물어본다 */
        private const val TEMP_EVERY_N = 5

        /** 0000XXXX-0000-1000-8000-00805f9b34fb 형태면 4자리로 줄여서 표시 */
        fun shortUuid(uuid: UUID): String {
            val s = uuid.toString().lowercase()
            return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
                s.substring(4, 8)
            } else s
        }

        private fun hex(bytes: List<Byte>): String =
            bytes.joinToString(" ") { "%02X".format(it) }

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString(" ") { "%02X".format(it) }
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var scanning = false

    private val foundDevices = linkedMapOf<String, BleDevice>()

    /** 알림을 받을 수 있는 characteristic 전부 (어디로 올지 몰라서 다 구독한다) */
    private val notifyChars = mutableListOf<BluetoothGattCharacteristic>()

    /** 요청을 쏠 수 있는 characteristic 전부 (어디에 쏴야 하는지 몰라서 다 찔러본다) */
    private val writeChars = mutableListOf<BluetoothGattCharacteristic>()

    /** 응답이 온 뒤 확정된 쓰기 대상 */
    private var lockedWriteChar: BluetoothGattCharacteristic? = null

    /** 방금 요청을 쏜 대상. 응답이 오면 이 놈으로 확정한다. */
    private var pendingWriteChar: BluetoothGattCharacteristic? = null

    /**
     * 알림 구독을 하나씩 순서대로 걸기 위한 대기열 (GATT는 동시에 하나만 처리).
     * ArrayDeque를 쓰면 이 파일의 `import java.util.*` 때문에 java.util 쪽이 잡혀서
     * kotlin의 removeFirstOrNull()을 못 쓴다. 그냥 리스트로 간다.
     */
    private val cccdQueue = mutableListOf<BluetoothGattCharacteristic>()

    private var serviceInfo = ""
    var lastServiceDump: String = ""
        private set

    // ── 프로토콜 탐색 / 통신 상태 ──
    private var detectedProtocol: String? = null   // null이면 아직 탐색 중
    private var probeIndex = 0
    private var lastTx = ""
    private val rxLines = mutableListOf<String>()
    private var rxTotal = 0
    private var parsedCount = 0
    private var polling = false
    private var lastLogUpdate = 0L
    private var requestCounter = 0
    private var sweepJustFinished = false
    private var txTried = 0        // 쓰기 시도 횟수
    private var txAccepted = 0     // 안드로이드 스택이 받아준 횟수
    private var txConfirmed = 0    // BMS까지 전달 확인된 횟수

    /** 실제 갱신 주기 측정용 */
    private var lastParseTime = 0L
    private var updateHz = 0f

    private val rxBuffer = mutableListOf<Byte>()

    // ── SPP(클래식 블루투스) 상태 ──
    private var sppSocket: BluetoothSocket? = null
    private var sppOut: OutputStream? = null
    private var sppThread: Thread? = null
    private val sppWriter = Executors.newSingleThreadExecutor()
    private var discoveryReceiverRegistered = false

    /** SPP로 붙어 있으면 true */
    private val isSpp: Boolean get() = sppSocket != null

    // ─────────────────────────── 스캔 ───────────────────────────

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter == null || !adapter.isEnabled) {
            onLog("블루투스가 꺼져 있습니다. 켜고 다시 시도하세요.")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            onLog("블루투스 스캐너를 사용할 수 없습니다.")
            return
        }
        if (scanning) return

        foundDevices.clear()
        onScanResults(emptyList())
        scanning = true
        onScanningChanged(true)
        onLog("주변 기기 검색 중...")

        // 이미 페어링된 기기는 스캔을 기다릴 필요가 없다. 바로 목록에 올린다.
        try {
            for (d in adapter.bondedDevices.orEmpty()) {
                val n = d.name ?: continue
                foundDevices[d.address] = BleDevice(n, d.address, -50, isClassic = true)
            }
            if (foundDevices.isNotEmpty()) publishScanResults()
        } catch (e: SecurityException) {
            // 권한 없음 - 무시하고 스캔만 진행
        }

        // 클래식(SPP) 기기는 BLE 스캔에 안 잡힌다. 별도로 탐색해야 한다.
        startClassicDiscovery()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (name.isBlank()) return
                val addr = result.device.address ?: return
                val prev = foundDevices[addr]
                if (prev == null || (!prev.isClassic && result.rssi > prev.rssi)) {
                    foundDevices[addr] = BleDevice(name, addr, result.rssi)
                    publishScanResults()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                onLog("스캔 실패 (코드 $errorCode)")
            }
        }
        activeScanCallback = callback

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)

        scanTimeoutRunnable = Runnable {
            if (scanning) {
                stopScan()
                if (foundDevices.isEmpty()) {
                    onLog("주변에서 기기를 찾지 못했습니다. BMS 전원을 확인하세요.")
                } else {
                    onLog("검색 완료 (${foundDevices.size}개). 연결할 기기를 고르세요.")
                }
            }
        }
        handler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)
    }

    private fun publishScanResults() {
        val sorted = foundDevices.values.sortedWith(
            compareByDescending<BleDevice> { it.isLikelyBms }.thenByDescending { it.rssi }
        )
        onScanResults(sorted)
    }

    // ─────────────────── 클래식(SPP) 기기 탐색 ───────────────────

    /** 클래식 블루투스 기기가 발견될 때마다 시스템이 던지는 방송을 받는다 */
    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_FOUND) return
            @Suppress("DEPRECATION")
            val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                ?: return
            val name = try { d.name } catch (e: SecurityException) { null } ?: return
            if (name.isBlank()) return
            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (-100).toShort()).toInt()
            val prev = foundDevices[d.address]
            if (prev == null || rssi > prev.rssi) {
                foundDevices[d.address] = BleDevice(name, d.address, rssi, isClassic = true)
                publishScanResults()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery() {
        val a = adapter ?: return
        try {
            if (!discoveryReceiverRegistered) {
                val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        discoveryReceiver, filter, Context.RECEIVER_EXPORTED
                    )
                } else {
                    context.registerReceiver(discoveryReceiver, filter)
                }
                discoveryReceiverRegistered = true
            }
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "클래식 탐색 실패: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopClassicDiscovery() {
        try {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
        } catch (e: Exception) {
            // 무시
        }
        if (discoveryReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (e: IllegalArgumentException) {
                // 이미 해제됨
            }
            discoveryReceiverRegistered = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        stopClassicDiscovery()
        activeScanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        activeScanCallback = null
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        if (scanning) {
            scanning = false
            onScanningChanged(false)
        }
    }

    // ─────────────────────────── 연결 ───────────────────────────

    @SuppressLint("MissingPermission")
    fun connectToAddress(address: String) {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            onLog("MAC 주소 형식이 잘못되었습니다: $address")
            null
        } ?: return
        // 클래식 전용 기기면 SPP로, 나머지는 BLE로 먼저 시도한다
        val classicOnly = try {
            device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC
        } catch (e: SecurityException) {
            false
        }
        if (classicOnly) connectSpp(device) else connect(device)
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BleDevice) {
        val d = try {
            adapter?.getRemoteDevice(device.address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return
        onLog("${device.name} 연결 중...")
        if (device.isClassic) connectSpp(d) else connect(d)
    }

    // ─────────────────── SPP(클래식) 연결 ───────────────────

    /**
     * RFCOMM 소켓으로 연결한다. BLE와 달리 그냥 시리얼 포트다.
     * connect()가 블로킹이라 반드시 별도 스레드에서 돌려야 한다.
     */
    @SuppressLint("MissingPermission")
    private fun connectSpp(device: BluetoothDevice) {
        stopScan()
        onScanResults(emptyList())
        closeSpp()
        gatt?.close()
        gatt = null
        resetSession()

        // 탐색이 돌고 있으면 연결이 느려지거나 실패한다
        try { adapter?.cancelDiscovery() } catch (e: Exception) { }

        val t = Thread {
            var socket: BluetoothSocket? = null
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket.connect()
            } catch (e: Exception) {
                Log.w(TAG, "표준 RFCOMM 실패: ${e.message}")
                try { socket?.close() } catch (ignored: Exception) { }
                // 일부 중국산 모듈은 표준 방식으로 안 붙는다. 채널 1로 직접 시도.
                socket = try {
                    val m = device.javaClass.getMethod(
                        "createRfcommSocket", Int::class.javaPrimitiveType
                    )
                    (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
                } catch (e2: Exception) {
                    Log.w(TAG, "채널1 RFCOMM 실패: ${e2.message}")
                    handler.post {
                        onConnectionState(false)
                        onLog("SPP 연결 실패: ${e2.message}\n\n" +
                                "폰 설정에서 이 기기를 먼저 페어링해보세요.")
                    }
                    null
                }
            }

            val sock = socket ?: return@Thread
            sppSocket = sock
            sppOut = try { sock.outputStream } catch (e: Exception) { null }

            handler.post {
                serviceInfo = "SPP(RFCOMM) 연결됨 - 시리얼 통신"
                onConnectionState(true)
                onDeviceConnected(
                    try { device.name } catch (e: SecurityException) { null } ?: "BMS",
                    device.address ?: ""
                )
                updateStatusLog(true)
                startPolling()
            }

            // 수신 루프
            val buf = ByteArray(1024)
            try {
                val input = sock.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val chunk = buf.copyOf(n)
                    handler.post { handleRx(chunk) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SPP 수신 종료: ${e.message}")
            }

            handler.post {
                if (sppSocket === sock) {
                    onLog("SPP 연결이 끊겼습니다.")
                    onConnectionState(false)
                    stopPolling()
                    closeSpp()
                }
            }
        }
        sppThread = t
        t.isDaemon = true
        t.start()
    }

    /** SPP는 그냥 스트림에 바이트를 밀어넣으면 된다 */
    private fun sppWrite(frame: ByteArray): Boolean {
        val out = sppOut ?: return false
        sppWriter.execute {
            try {
                out.write(frame)
                out.flush()
                txConfirmed++
            } catch (e: Exception) {
                Log.w(TAG, "SPP 송신 실패: ${e.message}")
            }
        }
        return true
    }

    private fun closeSpp() {
        try { sppOut?.close() } catch (e: Exception) { }
        try { sppSocket?.close() } catch (e: Exception) { }
        sppOut = null
        sppSocket = null
        sppThread = null
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        stopScan()
        onScanResults(emptyList())   // 연결 후엔 로그가 보이도록 목록을 비운다
        closeSpp()                   // 전송방식 전환 시 이전 소켓 정리
        gatt?.close()
        resetSession()
        gatt = device.connectGatt(context, false, gattCallback)
    }

    private fun resetSession() {
        stopPolling()
        rxBuffer.clear()
        rxLines.clear()
        notifyChars.clear()
        writeChars.clear()
        cccdQueue.clear()
        lockedWriteChar = null
        detectedProtocol = null
        probeIndex = 0
        lastTx = ""
        rxTotal = 0
        parsedCount = 0
        lastParseTime = 0L
        updateHz = 0f
        sweepJustFinished = false
        txTried = 0
        txAccepted = 0
        txConfirmed = 0
        lastLogUpdate = 0L
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onLog("연결됨. 서비스 확인 중...")
                // BLE 기본 연결 간격은 50ms 안팎이다. 이걸 안 낮추면
                // 아무리 빨리 요청해도 그 이하로는 못 내려간다.
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(247)   // 긴 응답 프레임이 잘리지 않게
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onLog("연결이 끊겼습니다. (status=$status)")
                onConnectionState(false)
                stopPolling()
                rxBuffer.clear()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU = $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            lastServiceDump = buildDump(g)
            Log.d(TAG, "SERVICES:\n$lastServiceDump")

            notifyChars.clear()
            writeChars.clear()

            // 어느 서비스가 데이터용인지 미리 알 수 없다.
            // 표준 서비스만 빼고 알림/쓰기 가능한 characteristic을 전부 모은다.
            for (svc in g.services) {
                if (isStandardService(svc.uuid)) continue
                for (ch in svc.characteristics) {
                    val p = ch.properties
                    if (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ) {
                        notifyChars.add(ch)
                    }
                    if (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ) {
                        writeChars.add(ch)
                    }
                }
            }

            if (notifyChars.isEmpty() && writeChars.isEmpty()) {
                onConnectionState(false)
                onLog("통신용 characteristic이 없습니다.\n\n발견된 구조:\n$lastServiceDump")
                return
            }

            serviceInfo = "알림 ${notifyChars.size}개 / 쓰기 ${writeChars.size}개 후보\n" +
                    lastServiceDump

            // 데이터가 어느 채널로 올지 모르니 알림은 전부 구독한다.
            // GATT는 한 번에 하나씩만 처리하므로 대기열로 순서대로 건다.
            cccdQueue.clear()
            cccdQueue.addAll(notifyChars)
            enableNextNotification(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            enableNextNotification(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) txConfirmed++
            Log.d(TAG, "TX 완료 status=$status")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleRx(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleRx(value)
        }
    }

    private fun handleRx(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        Log.d(TAG, "RX: ${hex(bytes)}")
        rxTotal += bytes.size
        rxLines.add(hex(bytes))
        while (rxLines.size > 5) rxLines.removeAt(0)

        rxBuffer.addAll(bytes.toList())
        if (rxBuffer.size > 1024) rxBuffer.clear()
        tryParseBuffer()
        updateStatusLog(false)
    }

    /**
     * 알림 구독을 하나씩 순서대로 건다.
     * GATT는 요청을 한 번에 하나만 처리해서, 한꺼번에 걸면 뒤엣것이 조용히 씹힌다.
     * 대기열이 비면 그때 통신을 시작한다.
     */
    @SuppressLint("MissingPermission")
    private fun enableNextNotification(g: BluetoothGatt) {
        if (cccdQueue.isEmpty()) {
            beginCommunication(g)
            return
        }
        val ch = cccdQueue.removeAt(0)
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            enableNextNotification(g)   // 이 놈은 CCCD가 없다 - 건너뜀
            return
        }
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        @Suppress("DEPRECATION")
        cccd.value = value
        @Suppress("DEPRECATION")
        val ok = g.writeDescriptor(cccd)
        if (!ok) enableNextNotification(g)   // 실패하면 다음 놈으로
    }

    private fun beginCommunication(g: BluetoothGatt) {
        val device = g.device
        onConnectionState(true)
        onDeviceConnected(device.name ?: "BMS", device.address ?: "")
        updateStatusLog(true)
        startPolling()
    }

    /**
     * 연결 창에 뿌릴 진단 문자열.
     * 매 패킷마다 갱신하면 화면 전체가 다시 그려져서 오히려 버벅인다.
     */
    private fun updateStatusLog(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLogUpdate < LOG_THROTTLE_MS) return
        lastLogUpdate = now

        val sb = StringBuilder()
        sb.append("프로토콜: ").append(detectedProtocol ?: "탐색 중...")
        if (updateHz > 0f) sb.append("   (%.1f Hz)".format(updateHz))
        sb.append("\n보낸 프레임: ").append(lastTx)
        sb.append("\n쓰기 시도/수락/완료: ")
            .append(txTried).append(" / ").append(txAccepted).append(" / ").append(txConfirmed)
        sb.append("\n받은 바이트: ").append(rxTotal)
        sb.append(" / 해석 성공: ").append(parsedCount)
        if (rxLines.isEmpty()) {
            sb.append("\n\n수신 데이터 없음.\nBMS 순정 앱이 켜져 있으면 완전히 종료하세요.")
        } else {
            sb.append("\n\n최근 수신:\n").append(rxLines.joinToString("\n"))
        }
        // 구조는 항상 붙여둔다. 안 붙으면 이걸 보고 진단해야 한다.
        sb.append("\n\n").append(serviceInfo)
        onLog(sb.toString())
    }

    // ─────────────────── 서비스 자동 탐색 도우미 ───────────────────

    private fun isStandardService(uuid: UUID): Boolean =
        shortUuid(uuid).lowercase() in STANDARD_SERVICES

    private fun buildDump(g: BluetoothGatt): String {
        val sb = StringBuilder()
        for (svc in g.services) {
            sb.append("[${shortUuid(svc.uuid)}]")
            if (isStandardService(svc.uuid)) sb.append(" (표준)")
            sb.append("\n")
            for (ch in svc.characteristics) {
                val p = ch.properties
                val flags = buildString {
                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("w")
                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N")
                    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I")
                }
                sb.append("   ${shortUuid(ch.uuid)}  $flags\n")
            }
        }
        return sb.toString().trimEnd()
    }

    // ─────────────────────────── 송신 ───────────────────────────

    /**
     * 고정 타이머가 아니라 요청-응답 방식.
     * 보낼 때 타임아웃을 걸어두고, 응답이 먼저 오면 그 즉시 다음 요청을 앞당긴다.
     */
    private val requestRunnable = Runnable { sendRequest() }

    private fun startPolling() {
        if (!isSpp && writeChars.isEmpty()) {
            onLog("쓰기 characteristic이 없습니다.\n일부 BMS는 요청 없이 알림만 보냅니다. 잠시 기다려보세요.")
            return
        }
        polling = true
        handler.removeCallbacks(requestRunnable)
        handler.post(requestRunnable)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(requestRunnable)
    }

    private fun scheduleNext(delayMs: Long) {
        if (!polling) return
        handler.removeCallbacks(requestRunnable)
        handler.postDelayed(requestRunnable, delayMs)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun sendRequest() {
        if (!polling) return

        // ── SPP 경로: 채널이 하나뿐이라 프레임만 돌아가며 보낸다 ──
        if (isSpp) {
            val foundSpp = detectedProtocol
            var f = if (foundSpp != null) {
                PROBE_FRAMES.first { it.first == foundSpp }
            } else {
                val x = PROBE_FRAMES[probeIndex % PROBE_FRAMES.size]
                probeIndex++
                sweepJustFinished = (probeIndex % PROBE_FRAMES.size == 0)
                x
            }
            if (foundSpp != null) {
                requestCounter++
                if (requestCounter % TEMP_EVERY_N == 0) {
                    DALY_TEMP_FRAMES[foundSpp]?.let { f = "$foundSpp·온도" to it }
                }
            }
            lastTx = "${f.first} \u2192 SPP  ${hex(f.second)}"
            txTried++
            if (sppWrite(f.second)) txAccepted++
            updateStatusLog(false)
            scheduleNext(
                when {
                    foundSpp != null -> RESPONSE_TIMEOUT_MS
                    sweepJustFinished && rxTotal == 0 -> PASSIVE_LISTEN_MS
                    else -> PROBE_INTERVAL_MS
                }
            )
            return
        }

        // ── BLE 경로 ──
        val g = gatt ?: return
        if (writeChars.isEmpty()) return

        val found = detectedProtocol
        val wc: BluetoothGattCharacteristic
        var frame: ByteArray
        var name: String

        if (found != null) {
            // 확정됨 - 통하는 조합만 반복
            wc = lockedWriteChar ?: writeChars[0]
            val f = PROBE_FRAMES.first { it.first == found }
            name = f.first
            frame = f.second

            // Daly는 온도가 별도 커맨드(0x92)라 가끔 한 번씩 섞어 보낸다.
            requestCounter++
            if (requestCounter % TEMP_EVERY_N == 0) {
                DALY_TEMP_FRAMES[found]?.let {
                    frame = it
                    name = "$found·온도"
                }
            }
        } else {
            // 탐색 중 - (쓰기 대상 × 요청 프레임) 모든 조합을 돌아가며 찔러본다
            val combos = writeChars.size * PROBE_FRAMES.size
            val idx = probeIndex % combos
            probeIndex++
            sweepJustFinished = (probeIndex % combos == 0)
            wc = writeChars[idx / PROBE_FRAMES.size]
            val f = PROBE_FRAMES[idx % PROBE_FRAMES.size]
            name = f.first
            frame = f.second
        }

        val type = if (wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        lastTx = "$name \u2192 ${shortUuid(wc.uuid)}  ${hex(frame)}"
        wc.value = frame
        wc.writeType = type
        txTried++
        // 반환값을 버리면 스택이 거절해도 모른다. 이전 GATT 작업이 안 끝났으면 false가 온다.
        val accepted = g.writeCharacteristic(wc)
        if (accepted) txAccepted++
        Log.d(TAG, "TX($name \u2192 ${shortUuid(wc.uuid)}) accepted=$accepted: ${hex(frame)}")

        pendingWriteChar = wc
        updateStatusLog(false)

        val delay = when {
            found != null -> RESPONSE_TIMEOUT_MS
            // 한 바퀴 끝 + 아직 한 바이트도 못 받음 → 조용히 듣는 구간
            sweepJustFinished && rxTotal == 0 -> PASSIVE_LISTEN_MS
            else -> PROBE_INTERVAL_MS
        }
        scheduleNext(delay)
    }

    // ─────────────────────────── 파싱 ───────────────────────────

    private fun tryParseBuffer() {
        while (rxBuffer.size >= 4) {
            when (rxBuffer[0].toInt() and 0xFF) {

                // ── JBD / Xiaoxiang: DD [cmd] [status] [len] <data> [crc 2B] 77
                0xDD -> {
                    val len = rxBuffer[3].toInt() and 0xFF
                    val total = 4 + len + 3
                    if (total > 260) { rxBuffer.removeAt(0); continue }
                    if (rxBuffer.size < total) return
                    if ((rxBuffer[total - 1].toInt() and 0xFF) == 0x77) {
                        val cmd = rxBuffer[1].toInt() and 0xFF
                        val data = rxBuffer.subList(4, 4 + len).toList()
                        parseJbd(cmd, data)
                        rxBuffer.subList(0, total).clear()
                    } else {
                        rxBuffer.removeAt(0)
                    }
                }

                // ── JK BMS: 55 AA EB 90 [type] ... 총 300바이트, 리틀엔디안
                0x55 -> {
                    if ((rxBuffer[1].toInt() and 0xFF) != 0xAA ||
                        (rxBuffer[2].toInt() and 0xFF) != 0xEB ||
                        (rxBuffer[3].toInt() and 0xFF) != 0x90
                    ) {
                        rxBuffer.removeAt(0); continue
                    }
                    if (rxBuffer.size < 300) return
                    val f = rxBuffer.subList(0, 300).toList()
                    parseJk(f)
                    rxBuffer.subList(0, 300).clear()
                }

                // ── Daly: A5 [addr] [cmd] 08 <8 bytes> [cksum] = 13바이트
                0xA5 -> {
                    if (rxBuffer.size < 13) return
                    val frame = rxBuffer.subList(0, 13).toList()
                    parseDaly(frame)
                    rxBuffer.subList(0, 13).clear()
                }

                else -> rxBuffer.removeAt(0)
            }
        }
    }

    /**
     * Daly 응답 처리.
     *  0x90 - 전압 / (예약) / 전류(+30000) / SOC, 각 2바이트 0.1단위
     *  0x92 - 최고온도 / 최고온도셀 / 최저온도 / 최저온도셀, 각 1바이트 (+40 오프셋)
     */
    private fun parseDaly(frame: List<Byte>) {
        when (frame[2].toInt() and 0xFF) {

            0x90 -> {
                val v = u16(frame[4], frame[5]) / 10f
                val a = (u16(frame[8], frame[9]) - 30000) / 10f
                val s = u16(frame[10], frame[11]) / 10f
                if (v <= 0f || v > 200f) return    // 말도 안 되는 값이면 버림
                lockProtocol(if (frame[1].toInt() and 0xFF == 0x01) "Daly(40)" else "Daly(80)")
                onParsed(v, a, s)
            }

            0x92 -> {
                val maxT = (frame[4].toInt() and 0xFF) - 40
                if (maxT in -40..150) {
                    onTemperature(maxT.toFloat())
                    // 온도 응답도 하나의 왕복이므로 바로 다음 요청을 앞당긴다
                    if (detectedProtocol != null) scheduleNext(MIN_GAP_MS)
                }
            }
        }
    }

    /**
     * JBD 0x03(기본정보) 응답.
     *  0-1 총전압(10mV)  2-3 전류(10mA)  19 SOC(%)
     *  22 NTC 개수  23~ NTC 값 2바이트씩 (0.1K 단위)
     */
    private fun parseJbd(cmd: Int, d: List<Byte>) {
        if (cmd != 0x03 || d.size < 20) return
        val v = u16(d[0], d[1]) / 100f
        val a = s16(d[2], d[3]) / 100f
        val s = (d[19].toInt() and 0xFF).toFloat()
        if (v <= 0f || v > 200f) return
        lockProtocol("JBD")
        onParsed(v, a, s)

        // 온도 센서는 여러 개일 수 있다. 가장 뜨거운 놈을 쓴다.
        val ntcCount = if (d.size > 22) d[22].toInt() and 0xFF else 0
        if (ntcCount in 1..8 && d.size >= 23 + ntcCount * 2) {
            var maxKelvin = 0
            for (i in 0 until ntcCount) {
                val k = u16(d[23 + i * 2], d[24 + i * 2])
                if (k > maxKelvin) maxKelvin = k
            }
            if (maxKelvin > 0) {
                val c = (maxKelvin - 2731) / 10f   // 0.1K → ℃
                if (c > -40f && c < 150f) onTemperature(c)
            }
        }
    }

    /** 값 해석 성공 - 화면에 반영하고 즉시 다음 요청을 앞당긴다 */
    private fun onParsed(v: Float, rawCurrent: Float, s: Float) {
        parsedCount++

        // 프로토콜이 뭐든 부호는 여기 한 군데서만 뒤집는다
        val a = if (INVERT_CURRENT) -rawCurrent else rawCurrent

        val now = SystemClock.elapsedRealtime()
        if (lastParseTime > 0L) {
            val dt = (now - lastParseTime) / 1000f
            if (dt > 0.001f) {
                val hz = 1f / dt
                updateHz = if (updateHz == 0f) hz else updateHz * 0.8f + hz * 0.2f
            }
        }
        lastParseTime = now

        onData(v, a, s)

        // 여기가 핵심. 타임아웃까지 기다리지 않고 바로 다음 요청을 쏜다.
        if (detectedProtocol != null) scheduleNext(MIN_GAP_MS)
    }

    /**
     * JK BMS 셀정보(0x02) 응답 해석. 전부 리틀엔디안.
     *  118: 총전압 uint32 (mV)   126: 전류 int32 (mA)
     *  130: 온도센서1 int16 (0.1℃)   141: SOC uint8 (%)
     *
     * 오프셋이 펌웨어마다 다를 수 있어서 값이 상식 밖이면 확정하지 않는다.
     * 그래야 잘못 잠기지 않고 원본 바이트가 화면에 계속 남는다.
     */
    private fun parseJk(f: List<Byte>) {
        if ((f[4].toInt() and 0xFF) != 0x02) return   // 셀 정보 프레임만 사용

        val v = u32le(f, 118) / 1000f
        val a = s32le(f, 126) / 1000f
        val soc = f[141].toInt() and 0xFF
        val t1 = s16le(f, 130) / 10f

        if (v <= 0f || v > 200f) return
        if (soc > 100) return
        if (a < -1000f || a > 1000f) return

        lockProtocol("JK-cell")
        onParsed(v, a, soc.toFloat())
        if (t1 > -40f && t1 < 150f) onTemperature(t1)
    }

    private fun u32le(f: List<Byte>, i: Int): Long =
        (f[i].toLong() and 0xFF) or
                ((f[i + 1].toLong() and 0xFF) shl 8) or
                ((f[i + 2].toLong() and 0xFF) shl 16) or
                ((f[i + 3].toLong() and 0xFF) shl 24)

    private fun s32le(f: List<Byte>, i: Int): Int {
        val v = u32le(f, i)
        return if (v > 2147483647L) (v - 4294967296L).toInt() else v.toInt()
    }

    private fun s16le(f: List<Byte>, i: Int): Int {
        val v = (f[i].toInt() and 0xFF) or ((f[i + 1].toInt() and 0xFF) shl 8)
        return if (v > 32767) v - 65536 else v
    }

    private fun lockProtocol(name: String) {
        if (detectedProtocol == null) {
            detectedProtocol = name
            lockedWriteChar = pendingWriteChar   // 응답을 이끌어낸 그 채널로 고정
            Log.d(TAG, "프로토콜 확정: $name")
            updateStatusLog(true)
        }
    }

    private fun u16(hi: Byte, lo: Byte): Int =
        ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

    private fun s16(hi: Byte, lo: Byte): Int {
        val v = u16(hi, lo)
        return if (v > 32767) v - 65536 else v
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        stopPolling()
        closeSpp()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        resetSession()
    }
}
