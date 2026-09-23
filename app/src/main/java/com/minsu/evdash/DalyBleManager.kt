package com.minsu.evdash

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.*

/** 스캔으로 발견한 BLE 기기 하나 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
) {
    /** BMS 후보로 보이는 이름인지 */
    val isLikelyBms: Boolean
        get() = name.startsWith("DL-", ignoreCase = true) ||
                name.contains("daly", ignoreCase = true) ||
                name.contains("bms", ignoreCase = true) ||
                name.startsWith("BT", ignoreCase = true)
}

/**
 * BMS(BLE) 연결/파싱 매니저.
 *
 * 지원 프로토콜
 *  1) LWS  - 순정 앱(SmartBMS-Lion1) APK를 디컴파일해서 실측한 규격.
 *            프레임: 3A 16 [cmd] [len] <data> [체크섬 2B LE] 0D 0A
 *            0x2A(realtime) 응답 하나에 전압·전류·온도·SOC가 전부 들어있다.
 *  2) Daly - A5 [addr] [cmd] 08 <8B> [체크섬]
 *  3) JBD  - DD A5 [cmd] 00 <...> 77
 *
 * 연결 후 어느 프로토콜인지 모르면 순서대로 찔러보고, 값이 해석되는 놈으로 고정한다.
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

        private const val SCAN_TIMEOUT_MS = 12000L

        /** 응답 받고 다음 요청까지 최소 간격 (ms) */
        private const val MIN_GAP_MS = 120L

        /** 이 시간 안에 응답이 없으면 같은 요청을 다시 보낸다 (ms) */
        private const val RESPONSE_TIMEOUT_MS = 1200L

        /** 프로토콜 탐색 중 다음 후보로 넘어가는 간격 (ms) */
        private const val PROBE_INTERVAL_MS = 700L

        /** 진단 로그 갱신 최소 간격 (ms) */
        private const val LOG_THROTTLE_MS = 500L

        /** Daly 전용 - 이 횟수마다 한 번씩 온도를 따로 물어본다 */
        private const val TEMP_EVERY_N = 5

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

        // ── LWS 프레임 ──
        // 3A 16 [cmd] [len=00] [체크섬 lo] [체크섬 hi] 0D 0A
        // 체크섬 = e[1]..e[n-5] 합 = (0x16 + cmd), 16비트 리틀엔디안
        private fun lwsFrame(cmd: Int): ByteArray {
            val sum = (0x16 + cmd) and 0xFFFF
            return byteArrayOf(
                0x3A, 0x16, cmd.toByte(), 0x00,
                (sum and 0xFF).toByte(), ((sum shr 8) and 0xFF).toByte(),
                0x0D, 0x0A
            )
        }

        /** Daly 요청: A5 [addr] [cmd] 08 <8바이트> [체크섬] = 13바이트 */
        private fun dalyFrame(addr: Int, cmd: Int): ByteArray {
            val body = ByteArray(12)
            body[0] = 0xA5.toByte()
            body[1] = addr.toByte()
            body[2] = cmd.toByte()
            body[3] = 0x08
            val sum = body.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
            return body + (sum and 0xFF).toByte()
        }

        /**
         * 찔러볼 요청 프레임. 전부 조회(read) 명령만 쓴다.
         * LWS를 맨 앞에 두는 이유: 0x2A 응답 하나에 필요한 값이 다 들어있어서
         * 확정되면 그 뒤로는 한 종류만 반복하면 된다.
         */
        private val PROBE_FRAMES: List<Pair<String, ByteArray>> by lazy {
            listOf(
                "LWS" to lwsFrame(0x2A),          // 실시간 데이터
                "Daly(40)" to dalyFrame(0x40, 0x90),
                "Daly(80)" to dalyFrame(0x80, 0x90),
                "JBD" to byteArrayOf(
                    0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00,
                    0xFF.toByte(), 0xFD.toByte(), 0x77
                )
            )
        }

        /** Daly는 온도가 별도 커맨드다. LWS/JBD는 기본 응답에 이미 들어있다. */
        private val DALY_TEMP_FRAMES: Map<String, ByteArray> by lazy {
            mapOf(
                "Daly(40)" to dalyFrame(0x40, 0x92),
                "Daly(80)" to dalyFrame(0x80, 0x92)
            )
        }

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

    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    private var serviceInfo = ""
    var lastServiceDump: String = ""
        private set

    // ── 프로토콜 탐색 / 통신 상태 ──
    private var detectedProtocol: String? = null
    private var probeIndex = 0
    private var requestCounter = 0
    private var lastTx = ""
    private val rxLines = mutableListOf<String>()
    private var rxTotal = 0
    private var parsedCount = 0
    private var polling = false
    private var lastLogUpdate = 0L

    private var lastParseTime = 0L
    private var updateHz = 0f

    private val rxBuffer = mutableListOf<Byte>()

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

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                if (name.isBlank()) return
                val addr = result.device.address ?: return
                val prev = foundDevices[addr]
                if (prev == null || result.rssi > prev.rssi) {
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

    @SuppressLint("MissingPermission")
    fun stopScan() {
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
        connect(device)
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BleDevice) {
        val d = try {
            adapter?.getRemoteDevice(device.address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return
        onLog("${device.name} 연결 중...")
        connect(d)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        stopScan()
        onScanResults(emptyList())
        gatt?.close()
        resetSession()
        gatt = device.connectGatt(context, false, gattCallback)
    }

    private fun resetSession() {
        stopPolling()
        rxBuffer.clear()
        rxLines.clear()
        notifyChar = null
        writeChar = null
        detectedProtocol = null
        probeIndex = 0
        requestCounter = 0
        lastTx = ""
        rxTotal = 0
        parsedCount = 0
        lastParseTime = 0L
        updateHz = 0f
        lastLogUpdate = 0L
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onLog("연결됨. 서비스 확인 중...")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(247)
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

            val target = pickService(g)
            if (target == null) {
                onConnectionState(false)
                onLog("통신용 서비스를 찾지 못했습니다.\n\n발견된 구조:\n$lastServiceDump")
                return
            }

            // ── 여기가 핵심 ──
            // 순정 앱은 characteristics[0] 을 알림, [1] 을 쓰기로 쓴다.
            // 속성(property)만 보고 첫 번째를 고르면 알림용을 쓰기용으로 잡는다.
            // ffe2 처럼 쓰기+알림을 둘 다 가진 놈이 앞에 오면 그대로 틀린다.
            val chars = target.characteristics
            notifyChar = chars.getOrNull(0)?.takeIf { hasNotify(it) } ?: chars.firstOrNull { hasNotify(it) }
            writeChar = chars.getOrNull(1)?.takeIf { hasWrite(it) }
                ?: chars.firstOrNull { hasWrite(it) && it !== notifyChar }
                ?: chars.firstOrNull { hasWrite(it) }

            if (notifyChar == null || writeChar == null) {
                onConnectionState(false)
                onLog("알림/쓰기 characteristic을 찾지 못했습니다.\n\n$lastServiceDump")
                return
            }

            writeType = if (writeChar!!.properties and
                BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            ) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            serviceInfo = "서비스 ${shortUuid(target.uuid)} / " +
                    "알림 ${shortUuid(notifyChar!!.uuid)} / " +
                    "쓰기 ${shortUuid(writeChar!!.uuid)}"

            g.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar!!.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            } else {
                beginCommunication(g)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            beginCommunication(g)
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

    private fun hasNotify(c: BluetoothGattCharacteristic) =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    private fun hasWrite(c: BluetoothGattCharacteristic) =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    /** ffe0 가 있으면 그쪽을 우선한다 (순정 앱이 그렇게 고른다) */
    private fun pickService(g: BluetoothGatt): BluetoothGattService? {
        val usable = g.services.filter {
            !isStandardService(it.uuid) &&
                    it.characteristics.any { c -> hasNotify(c) } &&
                    it.characteristics.any { c -> hasWrite(c) }
        }
        return usable.firstOrNull { shortUuid(it.uuid).equals("ffe0", true) }
            ?: usable.firstOrNull { shortUuid(it.uuid).equals("fff0", true) }
            ?: usable.firstOrNull()
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

    private fun beginCommunication(g: BluetoothGatt) {
        val device = g.device
        onConnectionState(true)
        onDeviceConnected(device.name ?: "BMS", device.address ?: "")
        updateStatusLog(true)
        startPolling()
    }

    private fun updateStatusLog(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLogUpdate < LOG_THROTTLE_MS) return
        lastLogUpdate = now

        val sb = StringBuilder()
        sb.append("프로토콜: ").append(detectedProtocol ?: "탐색 중...")
        if (updateHz > 0f) sb.append("   (%.1f Hz)".format(updateHz))
        sb.append("\n보낸 프레임: ").append(lastTx)
        sb.append("\n받은 바이트: ").append(rxTotal)
        sb.append(" / 해석 성공: ").append(parsedCount)
        if (rxLines.isEmpty()) {
            sb.append("\n\n수신 데이터 없음.\nBMS 순정 앱이 켜져 있으면 완전히 종료하세요.")
        } else {
            sb.append("\n\n최근 수신:\n").append(rxLines.joinToString("\n"))
        }
        sb.append("\n\n").append(serviceInfo)
        onLog(sb.toString())
    }

    private fun isStandardService(uuid: UUID): Boolean =
        shortUuid(uuid).lowercase() in STANDARD_SERVICES

    private fun buildDump(g: BluetoothGatt): String {
        val sb = StringBuilder()
        for (svc in g.services) {
            sb.append("[${shortUuid(svc.uuid)}]")
            if (isStandardService(svc.uuid)) sb.append(" (표준)")
            sb.append("\n")
            for ((i, ch) in svc.characteristics.withIndex()) {
                val p = ch.properties
                val flags = buildString {
                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("w")
                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N")
                    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I")
                }
                sb.append("  [$i] ${shortUuid(ch.uuid)}  $flags\n")
            }
        }
        return sb.toString().trimEnd()
    }

    // ─────────────────────────── 송신 ───────────────────────────

    private val requestRunnable = Runnable { sendRequest() }

    private fun startPolling() {
        if (writeChar == null) {
            onLog("쓰기 characteristic이 없습니다.")
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
        val g = gatt ?: return
        val wc = writeChar ?: return

        val found = detectedProtocol
        var name: String
        var frame: ByteArray

        if (found != null) {
            val f = PROBE_FRAMES.first { it.first == found }
            name = f.first
            frame = f.second

            // Daly만 온도가 별도 커맨드다
            requestCounter++
            if (requestCounter % TEMP_EVERY_N == 0) {
                DALY_TEMP_FRAMES[found]?.let {
                    frame = it
                    name = "$found·온도"
                }
            }
        } else {
            val f = PROBE_FRAMES[probeIndex % PROBE_FRAMES.size]
            probeIndex++
            name = f.first
            frame = f.second
        }

        lastTx = "$name  ${hex(frame)}"
        wc.value = frame
        wc.writeType = writeType
        g.writeCharacteristic(wc)
        Log.d(TAG, "TX($name): ${hex(frame)}")

        updateStatusLog(false)
        scheduleNext(if (found != null) RESPONSE_TIMEOUT_MS else PROBE_INTERVAL_MS)
    }

    // ─────────────────────────── 파싱 ───────────────────────────

    private fun tryParseBuffer() {
        while (rxBuffer.size >= 4) {
            when (rxBuffer[0].toInt() and 0xFF) {

                // ── LWS: 3A 16 [cmd] [len] <data> [cksum 2B LE] 0D 0A
                0x3A -> {
                    if ((rxBuffer[1].toInt() and 0xFF) != 0x16) {
                        rxBuffer.removeAt(0); continue
                    }
                    val len = rxBuffer[3].toInt() and 0xFF
                    val total = len + 8
                    if (total > 300) { rxBuffer.removeAt(0); continue }
                    if (rxBuffer.size < total) return
                    val f = rxBuffer.subList(0, total).toList()
                    // 꼬리가 0D 0A 인지 확인
                    if ((f[total - 2].toInt() and 0xFF) != 0x0D ||
                        (f[total - 1].toInt() and 0xFF) != 0x0A
                    ) {
                        rxBuffer.removeAt(0); continue
                    }
                    parseLws(f)
                    rxBuffer.subList(0, total).clear()
                }

                // ── JBD: DD [cmd] [status] [len] <data> [crc 2B] 77
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

                // ── Daly: A5 [addr] [cmd] 08 <8B> [cksum] = 13바이트
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
     * LWS 응답 (순정 앱 디컴파일로 실측한 오프셋).
     *
     * 0x2A(realtime) 데이터 배치 - 전부 리틀엔디안, 프레임 선두 기준 절대 위치:
     *   [4..7]   전류   int32   mA
     *   [8..11]  전압   uint32  mV
     *   [12]     온도   int8    ℃
     *   [16..19] 잔량   uint32  mAh
     *   [20]     충전 MOSFET  (bit7)
     *   [21]     방전 MOSFET  (bit7)
     *   [24]     SOC    uint8   %
     */
    private fun parseLws(f: List<Byte>) {
        val n = f.size
        val len = f[3].toInt() and 0xFF
        val cmd = f[2].toInt() and 0xFF

        // 체크섬: e[1] .. e[n-5] 합 == 16비트 리틀엔디안
        var sum = 0
        for (i in 1..(n - 5)) sum += f[i].toInt() and 0xFF
        val stored = ((f[n - 3].toInt() and 0xFF) shl 8) or (f[n - 4].toInt() and 0xFF)
        if (sum != stored) {
            Log.d(TAG, "LWS 체크섬 불일치: calc=$sum stored=$stored")
            return
        }

        if (cmd != 0x2A || len < 21) return

        val currentMa = s32le(f, 4)
        val voltageMv = u32le(f, 8)
        val tempC = f[12].toInt()               // 부호 있는 1바이트
        val soc = f[24].toInt() and 0xFF

        val v = voltageMv / 1000f
        val a = currentMa / 1000f

        if (v <= 0f || v > 200f) return
        if (soc > 100) return

        lockProtocol("LWS")
        onParsed(v, a, soc.toFloat())
        if (tempC > -40 && tempC < 150) onTemperature(tempC.toFloat())
    }

    /**
     * Daly 응답.
     *  0x90 - 전압 / (예약) / 전류(+30000) / SOC, 각 2바이트 0.1단위
     *  0x92 - 최고온도 (+40 오프셋)
     */
    private fun parseDaly(frame: List<Byte>) {
        when (frame[2].toInt() and 0xFF) {
            0x90 -> {
                val v = u16(frame[4], frame[5]) / 10f
                val a = (u16(frame[8], frame[9]) - 30000) / 10f
                val s = u16(frame[10], frame[11]) / 10f
                if (v <= 0f || v > 200f) return
                lockProtocol(if (frame[1].toInt() and 0xFF == 0x01) "Daly(40)" else "Daly(80)")
                onParsed(v, a, s)
            }

            0x92 -> {
                val maxT = (frame[4].toInt() and 0xFF) - 40
                if (maxT in -40..150) {
                    onTemperature(maxT.toFloat())
                    if (detectedProtocol != null) scheduleNext(MIN_GAP_MS)
                }
            }
        }
    }

    /**
     * JBD 0x03(기본정보) 응답.
     *  0-1 총전압(10mV)  2-3 전류(10mA)  19 SOC(%)
     *  22 NTC 개수  23~ NTC 값 2바이트씩 (0.1K)
     */
    private fun parseJbd(cmd: Int, d: List<Byte>) {
        if (cmd != 0x03 || d.size < 20) return
        val v = u16(d[0], d[1]) / 100f
        val a = s16(d[2], d[3]) / 100f
        val s = (d[19].toInt() and 0xFF).toFloat()
        if (v <= 0f || v > 200f) return
        lockProtocol("JBD")
        onParsed(v, a, s)

        val ntcCount = if (d.size > 22) d[22].toInt() and 0xFF else 0
        if (ntcCount in 1..8 && d.size >= 23 + ntcCount * 2) {
            var maxKelvin = 0
            for (i in 0 until ntcCount) {
                val k = u16(d[23 + i * 2], d[24 + i * 2])
                if (k > maxKelvin) maxKelvin = k
            }
            if (maxKelvin > 0) {
                val c = (maxKelvin - 2731) / 10f
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

        if (detectedProtocol != null) scheduleNext(MIN_GAP_MS)
    }

    private fun lockProtocol(name: String) {
        if (detectedProtocol == null) {
            detectedProtocol = name
            Log.d(TAG, "프로토콜 확정: $name")
            updateStatusLog(true)
        }
    }

    // ── 숫자 변환 ──

    private fun u16(hi: Byte, lo: Byte): Int =
        ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

    private fun s16(hi: Byte, lo: Byte): Int {
        val v = u16(hi, lo)
        return if (v > 32767) v - 65536 else v
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

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        stopPolling()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        resetSession()
    }
}
