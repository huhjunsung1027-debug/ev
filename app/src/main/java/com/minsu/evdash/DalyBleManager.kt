package com.minsu.evdash

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import java.util.*

/** 스캔으로 발견한 BLE 기기 하나 */
data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int
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
 * 지원 프로토콜
 *  1) Daly UART-over-BLE  (요청 A5 xx 90 08 ... / 응답 A5 01 90 08 ...)
 *  2) JBD(Xiaoxiang) Smart BMS (요청 DD A5 03 00 FF FD 77 / 응답 DD 03 00 len ... 77)
 *  3) Daly Modbus 계열은 응답 바이트만 화면에 덤프 (해석은 실측 후 추가)
 */
class DalyBleManager(
    private val context: Context,
    private val onData: (voltage: Float, current: Float, soc: Float) -> Unit,
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
        private const val PROBE_INTERVAL_MS = 700L   // 프로토콜 탐색 중 전송 간격
        private const val POLL_INTERVAL_MS = 1000L   // 확정 후 갱신 간격

        /**
         * 방전 전류를 양수로 볼지. JBD는 방전이 음수로 오는 게 기본이라 뒤집는다.
         * 화면 부호가 반대로 나오면 이 값만 바꾸면 된다.
         */
        private const val JBD_INVERT_CURRENT = true

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

        /** 찔러볼 요청 프레임 목록 (이름, 바이트) */
        private val PROBE_FRAMES: List<Pair<String, ByteArray>> by lazy {
            listOf(
                "Daly(40)" to dalyFrame(0x40, 0x90),
                "Daly(80)" to dalyFrame(0x80, 0x90),
                "JBD" to byteArrayOf(
                    0xDD.toByte(), 0xA5.toByte(), 0x03, 0x00,
                    0xFF.toByte(), 0xFD.toByte(), 0x77
                ),
                "Modbus" to byteArrayOf(
                    0xD2.toByte(), 0x03, 0x00, 0x00, 0x00, 0x3E,
                    0xD7.toByte(), 0xB9.toByte()
                )
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
    private var pollRunnable: Runnable? = null

    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var scanning = false

    private val foundDevices = linkedMapOf<String, BleDevice>()

    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private var serviceInfo = ""
    var lastServiceDump: String = ""
        private set

    // ── 프로토콜 탐색 상태 ──
    private var detectedProtocol: String? = null   // null이면 아직 탐색 중
    private var probeIndex = 0
    private var lastTx = ""
    private val rxLines = mutableListOf<String>()
    private var rxTotal = 0
    private var parsedCount = 0

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
        onScanResults(emptyList())   // 연결 후엔 로그가 보이도록 목록을 비운다
        gatt?.close()
        resetSession()
        gatt = device.connectGatt(context, false, gattCallback)
    }

    private fun resetSession() {
        rxBuffer.clear()
        rxLines.clear()
        notifyChar = null
        writeChar = null
        detectedProtocol = null
        probeIndex = 0
        lastTx = ""
        rxTotal = 0
        parsedCount = 0
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onLog("연결됨. 서비스 확인 중...")
                g.requestMtu(247)   // 긴 응답 프레임이 잘리지 않게
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onLog("연결이 끊겼습니다. (status=$status)")
                onConnectionState(false)
                stopPolling()
                rxBuffer.clear()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            lastServiceDump = buildDump(g)
            Log.d(TAG, "SERVICES:\n$lastServiceDump")

            val target = g.services.firstOrNull { svc ->
                !isStandardService(svc.uuid) &&
                        findNotify(svc) != null && findWrite(svc) != null
            }

            if (target == null) {
                onConnectionState(false)
                onLog(
                    "통신용 서비스를 찾지 못했습니다.\n\n발견된 구조:\n$lastServiceDump"
                )
                return
            }

            notifyChar = findNotify(target)
            writeChar = findWrite(target)
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

    private fun handleRx(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        Log.d(TAG, "RX: ${hex(bytes)}")
        rxTotal += bytes.size
        rxLines.add(hex(bytes))
        while (rxLines.size > 5) rxLines.removeAt(0)

        rxBuffer.addAll(bytes.toList())
        if (rxBuffer.size > 512) rxBuffer.clear()
        tryParseBuffer()
        updateStatusLog()
    }

    @SuppressLint("MissingPermission")
    private fun beginCommunication(g: BluetoothGatt) {
        val device = g.device
        onConnectionState(true)
        onDeviceConnected(device.name ?: "BMS", device.address ?: "")
        updateStatusLog()
        startPolling(g)
    }

    /** 연결 창에 뿌릴 진단 문자열 */
    private fun updateStatusLog() {
        val sb = StringBuilder()
        sb.append(serviceInfo).append("\n\n")
        sb.append("프로토콜: ")
        sb.append(detectedProtocol ?: "탐색 중...")
        sb.append("\n보낸 프레임: ").append(lastTx)
        sb.append("\n받은 바이트: ").append(rxTotal)
        sb.append(" / 해석 성공: ").append(parsedCount)
        if (rxLines.isEmpty()) {
            sb.append("\n\n수신 데이터 없음.\nBMS 순정 앱이 켜져 있으면 완전히 종료하세요.")
        } else {
            sb.append("\n\n최근 수신:\n")
            sb.append(rxLines.joinToString("\n"))
        }
        onLog(sb.toString())
    }

    // ─────────────────── 서비스 자동 탐색 도우미 ───────────────────

    private fun isStandardService(uuid: UUID): Boolean =
        shortUuid(uuid).lowercase() in STANDARD_SERVICES

    private fun findNotify(svc: BluetoothGattService): BluetoothGattCharacteristic? =
        svc.characteristics.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        }

    private fun findWrite(svc: BluetoothGattService): BluetoothGattCharacteristic? =
        svc.characteristics.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }

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

    @SuppressLint("MissingPermission")
    private fun startPolling(g: BluetoothGatt) {
        stopPolling()
        val wc = writeChar ?: run {
            onLog("쓰기 characteristic이 없습니다.")
            return
        }
        val type = if (wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        pollRunnable = object : Runnable {
            @Suppress("DEPRECATION")
            override fun run() {
                val found = detectedProtocol
                val (name, frame) = if (found != null) {
                    PROBE_FRAMES.first { it.first == found }
                } else {
                    // 아직 못 찾았으면 후보를 돌아가며 찔러본다
                    val f = PROBE_FRAMES[probeIndex % PROBE_FRAMES.size]
                    probeIndex++
                    f
                }

                lastTx = "$name  ${hex(frame)}"
                wc.value = frame
                wc.writeType = type
                g.writeCharacteristic(wc)
                Log.d(TAG, "TX($name): ${hex(frame)}")

                updateStatusLog()
                handler.postDelayed(
                    this,
                    if (found != null) POLL_INTERVAL_MS else PROBE_INTERVAL_MS
                )
            }
        }
        handler.post(pollRunnable!!)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
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

    /** Daly 0x90 응답: 전압 / (예약) / 전류(+30000) / SOC, 각 2바이트 0.1단위 */
    private fun parseDaly(frame: List<Byte>) {
        val cmd = frame[2].toInt() and 0xFF
        if (cmd != 0x90) return
        val v = u16(frame[4], frame[5]) / 10f
        val a = (u16(frame[8], frame[9]) - 30000) / 10f
        val s = u16(frame[10], frame[11]) / 10f
        if (v <= 0f || v > 200f) return    // 말도 안 되는 값이면 버림
        lockProtocol(if (frame[1].toInt() and 0xFF == 0x01) "Daly(40)" else "Daly(80)")
        parsedCount++
        onData(v, a, s)
    }

    /**
     * JBD 0x03(기본정보) 응답.
     *  0-1 총전압(10mV)  2-3 전류(10mA, 방전 음수)  19 SOC(%)
     */
    private fun parseJbd(cmd: Int, d: List<Byte>) {
        if (cmd != 0x03 || d.size < 20) return
        val v = u16(d[0], d[1]) / 100f
        var a = s16(d[2], d[3]) / 100f
        if (JBD_INVERT_CURRENT) a = -a
        val s = (d[19].toInt() and 0xFF).toFloat()
        if (v <= 0f || v > 200f) return
        lockProtocol("JBD")
        parsedCount++
        onData(v, a, s)
    }

    private fun lockProtocol(name: String) {
        if (detectedProtocol == null) {
            detectedProtocol = name
            Log.d(TAG, "프로토콜 확정: $name")
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
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        resetSession()
    }
}
