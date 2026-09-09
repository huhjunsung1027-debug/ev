package com.minsu.evdash

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import java.util.*

/**
 * Daly Smart BMS(BLE) 연결/파싱 매니저.
 *
 * ⚠️ 중요: Daly BMS는 펌웨어/모델별로 프로토콜이 조금씩 다릅니다.
 * 아래 UUID와 요청 프레임은 가장 널리 쓰이는 "구형 Daly UART-over-BLE" 규격
 * (A5 80 90 ... 요청 / 0x90 응답 프레임에 전압·전류·SOC 포함) 기준입니다.
 * 만약 기존 Daly 앱은 붙는데 이 코드로 데이터가 안 들어오면:
 *   1) nRF Connect 앱으로 기존 Daly 앱 연결 시 오가는 실제 바이트를 캡처해서
 *      REQUEST_FRAME / parseFrame()의 오프셋을 실측값에 맞게 수정하세요.
 *   2) onCharacteristicChanged 안의 Log.d("DalyBLE", ...) 로그로 원본 바이트를
 *      먼저 확인하는 게 가장 빠릅니다. (adb logcat -s DalyBLE)
 */
class DalyBleManager(
    private val context: Context,
    private val onData: (voltage: Float, current: Float, soc: Float) -> Unit,
    private val onConnectionState: (connected: Boolean) -> Unit,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "DalyBLE"
        val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SCAN_TIMEOUT_MS = 15000L
        private const val POLL_INTERVAL_MS = 1000L

        // 전압/전류/SOC 조회 요청 프레임 (구형 Daly UART-over-BLE 규격, 커맨드 0x90)
        // A5 80 90 08 00 00 00 00 00 00 00 XX(checksum)
        private val REQUEST_FRAME: ByteArray by lazy {
            val frame = byteArrayOf(
                0xA5.toByte(), 0x80.toByte(), 0x90.toByte(), 0x08,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
            val sum = frame.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }
            frame + (sum and 0xFF).toByte()
        }
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    // stopScan()은 startScan()에 넘긴 "그 콜백 객체"로만 멈춥니다. 반드시 참조를 들고 있어야 함.
    private var activeScanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null

    // 응답 프레임 조립용 버퍼 (BLE는 20바이트 단위로 쪼개져 올 수 있음)
    private val rxBuffer = mutableListOf<Byte>()

    @SuppressLint("MissingPermission")
    fun startScanAndConnect(deviceNameFilter: String = "") {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled) {
            onLog("블루투스가 꺼져 있습니다. 켜고 다시 시도하세요.")
            return
        }
        if (scanner == null) {
            onLog("블루투스 어댑터를 찾을 수 없습니다.")
            return
        }
        if (scanning) return
        scanning = true
        onLog("BLE 스캔 시작...")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (deviceNameFilter.isEmpty() ||
                    name.contains(deviceNameFilter, ignoreCase = true) ||
                    name.contains("DL-", ignoreCase = true) ||
                    name.contains("Daly", ignoreCase = true)
                ) {
                    onLog("기기 발견: $name (${result.device.address})")
                    stopScanInternal()
                    connect(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScanInternal()
                onLog("스캔 실패: $errorCode")
            }
        }
        activeScanCallback = callback
        scanner.startScan(callback)

        // 15초 후에도 못 찾으면 스캔 중단
        scanTimeoutRunnable = Runnable {
            if (scanning) {
                stopScanInternal()
                onLog("스캔 시간 초과 - 기기를 찾지 못했습니다.")
            }
        }
        handler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        activeScanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        activeScanCallback = null
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        scanning = false
    }

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
    private fun connect(device: BluetoothDevice) {
        onLog("연결 시도: ${device.address}")
        gatt?.close()
        rxBuffer.clear()
        gatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onLog("GATT 연결됨, 서비스 탐색 시작")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onLog("GATT 연결 끊김 (status=$status)")
                onConnectionState(false)
                stopPolling()
                rxBuffer.clear()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                onLog("Daly 서비스 UUID(fff0)를 찾을 수 없습니다. 이 BMS는 다른 프로토콜일 수 있습니다.")
                return
            }
            val notifyChar = service.getCharacteristic(NOTIFY_UUID)
            if (notifyChar == null) {
                onLog("알림 characteristic(fff1)을 찾을 수 없습니다.")
                return
            }

            g.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                // CCCD 쓰기가 끝난 뒤(onDescriptorWrite)에 폴링을 시작해야
                // 첫 요청 프레임이 씹히지 않습니다. GATT 작업은 한 번에 하나씩만 처리됨.
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            } else {
                onConnectionState(true)
                onLog("연결 완료(CCCD 없음). 주기적 데이터 요청 시작")
                startPolling(g, service)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            val service = g.getService(SERVICE_UUID) ?: return
            onConnectionState(true)
            onLog("연결 완료. 주기적 데이터 요청 시작")
            startPolling(g, service)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: return
            Log.d(TAG, "RX: ${bytes.joinToString(" ") { "%02X".format(it) }}")
            rxBuffer.addAll(bytes.toList())
            tryParseBuffer()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPolling(g: BluetoothGatt, service: BluetoothGattService) {
        stopPolling()
        val writeChar = service.getCharacteristic(WRITE_UUID) ?: run {
            onLog("쓰기 characteristic(fff2)을 찾을 수 없습니다.")
            return
        }
        pollRunnable = object : Runnable {
            @Suppress("DEPRECATION")
            override fun run() {
                writeChar.value = REQUEST_FRAME
                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(writeChar)
                handler.postDelayed(this, POLL_INTERVAL_MS) // 1초마다 갱신
            }
        }
        handler.post(pollRunnable!!)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun tryParseBuffer() {
        // 응답 프레임: A5 01 90 08 [전압 2B] [전류 2B, +30000 offset] [SOC 2B] ... [checksum]
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

                val voltage = voltageRaw / 10f
                val current = (currentRaw - 30000) / 10f
                val soc = socRaw / 10f

                onData(voltage, current, soc)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScanInternal()
        stopPolling()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
