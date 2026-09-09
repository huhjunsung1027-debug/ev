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
    /** DL- 로 시작하면 Daly BMS 후보 */
    val isLikelyBms: Boolean
        get() = name.startsWith("DL-", ignoreCase = true) ||
                name.contains("daly", ignoreCase = true)
}

/**
 * Daly Smart BMS(BLE) 연결/파싱 매니저.
 *
 * 서비스/characteristic UUID는 고정하지 않고 자동으로 찾는다.
 * Daly는 모델·펌웨어마다 UUID가 달라서(fff0 / ffe0 / 6e40... 등) 고정하면 못 붙는다.
 * Notify 속성과 Write 속성을 둘 다 가진 커스텀 서비스를 골라서 사용한다.
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
        private const val POLL_INTERVAL_MS = 1000L

        /** 표준(제조사 데이터 아님) 서비스 - 자동 탐색에서 제외 */
        private val STANDARD_SERVICES = setOf(
            "1800", "1801", "1804", "180a", "180f", "1811", "fe59"
        )

        // 전압/전류/SOC 조회 요청 프레임 (Daly UART-over-BLE, 커맨드 0x90)
        // A5 80 90 08 00 00 00 00 00 00 00 XX(checksum)
        private val REQUEST_FRAME: ByteArray by lazy {
            val frame = byteArrayOf(
                0xA5.toByte(), 0x80.toByte(), 0x90.toByte(), 0x08,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
            val sum = frame.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
            frame + (sum and 0xFF).toByte()
        }

        /** 0000XXXX-0000-1000-8000-00805f9b34fb 형태면 4자리로 줄여서 표시 */
        fun shortUuid(uuid: UUID): String {
            val s = uuid.toString().lowercase()
            return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
                s.substring(4, 8)
            } else s
        }
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    // stopScan()은 startScan()에 넘긴 "그 객체"로만 멈춘다. 반드시 참조를 들고 있어야 함.
    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var scanning = false

    /** 스캔으로 모은 기기들 (주소 기준 중복 제거) */
    private val foundDevices = linkedMapOf<String, BleDevice>()

    /** 자동으로 찾아낸 통신 characteristic */
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    /** 마지막으로 붙은 기기의 서비스 구조 덤프 (문제 생기면 이걸 보고 진단) */
    var lastServiceDump: String = ""
        private set

    // 응답 프레임 조립용 버퍼 (BLE는 20바이트 단위로 쪼개져 올 수 있음)
    private val rxBuffer = mutableListOf<Byte>()

    // ─────────────────────────── 스캔 ───────────────────────────

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter == null || !adapter.isEnabled) {
            onLog("블루투스가 꺼져 있습니다. 켜고 다시 시도하세요.")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
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
                // 신호세기는 계속 바뀌니 더 강한 값으로 갱신
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

    /** BMS 후보(DL-)를 위로, 그다음 신호 강한 순 */
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
        gatt?.close()
        rxBuffer.clear()
        notifyChar = null
        writeChar = null
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onLog("연결됨. 서비스 확인 중...")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onLog("연결이 끊겼습니다. (status=$status)")
                onConnectionState(false)
                stopPolling()
                rxBuffer.clear()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // 1) 전체 구조를 덤프해둔다 (자동 탐색 실패 시 진단용)
            lastServiceDump = buildDump(g)
            Log.d(TAG, "SERVICES:\n$lastServiceDump")

            // 2) Notify + Write 를 둘 다 가진 커스텀 서비스를 자동으로 고른다
            val target = g.services.firstOrNull { svc ->
                !isStandardService(svc.uuid) &&
                        findNotify(svc) != null && findWrite(svc) != null
            }

            if (target == null) {
                onConnectionState(false)
                onLog(
                    "통신용 서비스를 찾지 못했습니다.\n" +
                            "이 기기가 BMS가 맞는지 확인하세요.\n\n" +
                            "발견된 구조:\n$lastServiceDump"
                )
                return
            }

            notifyChar = findNotify(target)
            writeChar = findWrite(target)

            val nUuid = shortUuid(notifyChar!!.uuid)
            val wUuid = shortUuid(writeChar!!.uuid)
            val sUuid = shortUuid(target.uuid)
            onLog("서비스 $sUuid 사용 (알림 $nUuid / 쓰기 $wUuid)\n데이터 요청 시작...")

            g.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar!!.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                // CCCD 쓰기가 끝난 뒤 폴링을 시작해야 첫 요청이 씹히지 않는다.
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
            val bytes = characteristic.value ?: return
            Log.d(TAG, "RX: ${bytes.joinToString(" ") { "%02X".format(it) }}")
            rxBuffer.addAll(bytes.toList())
            tryParseBuffer()
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginCommunication(g: BluetoothGatt) {
        val device = g.device
        onConnectionState(true)
        onDeviceConnected(device.name ?: "BMS", device.address ?: "")
        startPolling(g)
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

    // ─────────────────────────── 통신 ───────────────────────────

    @SuppressLint("MissingPermission")
    private fun startPolling(g: BluetoothGatt) {
        stopPolling()
        val wc = writeChar ?: run {
            onLog("쓰기 characteristic이 없습니다.")
            return
        }
        // 응답 없는 쓰기만 지원하면 그쪽으로
        val type = if (wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        pollRunnable = object : Runnable {
            @Suppress("DEPRECATION")
            override fun run() {
                wc.value = REQUEST_FRAME
                wc.writeType = type
                g.writeCharacteristic(wc)
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        handler.post(pollRunnable!!)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun tryParseBuffer() {
        // 응답 프레임: A5 01 90 08 [전압 2B] [.. 2B] [전류 2B, +30000] [SOC 2B] [checksum]
        while (rxBuffer.size >= 13) {
            val startIdx = rxBuffer.indexOfFirst { it == 0xA5.toByte() }
            if (startIdx == -1) {
                rxBuffer.clear()
                return
            }
            if (startIdx > 0) repeat(startIdx) { rxBuffer.removeAt(0) }
            if (rxBuffer.size < 13) return

            val frame = rxBuffer.subList(0, 13).toList()
            rxBuffer.subList(0, 13).clear()

            val cmd = frame[2].toInt() and 0xFF
            if (cmd == 0x90) {
                val voltageRaw = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
                val currentRaw = ((frame[8].toInt() and 0xFF) shl 8) or (frame[9].toInt() and 0xFF)
                val socRaw = ((frame[10].toInt() and 0xFF) shl 8) or (frame[11].toInt() and 0xFF)

                onData(voltageRaw / 10f, (currentRaw - 30000) / 10f, socRaw / 10f)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScan()
        stopPolling()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        notifyChar = null
        writeChar = null
    }
}
