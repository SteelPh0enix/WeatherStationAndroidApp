package com.steelph0enix.weatherstationapp.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.util.Log

typealias BLEDeviceFoundCallback = (ScanResult) -> Unit

class BLEScanner(adapter: BluetoothAdapter) {
    private val bluetoothAdapter = adapter
    private val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
    private var bleScanCallback: BLEScanCallback? = null
    private var scanning = false
    private val scanHandler = Handler(Looper.getMainLooper())

    object BLEScannerConstants {
        const val SCAN_PERIOD: Long = 10000
    }

    class BLEScanCallback(devFoundCallback: BLEDeviceFoundCallback) : ScanCallback() {
        private val deviceFoundCallback: BLEDeviceFoundCallback = devFoundCallback
        private var deviceAddressList: MutableList<String> = mutableListOf()

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            Log.d("onBatchScanResults callback", "I have a list!")
            super.onBatchScanResults(results)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.d("onScanFailed callback", "Scan has failed with code $errorCode")
            super.onScanFailed(errorCode)
        }

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            Log.d(
                "onScanResult callback",
                "Callback type: $callbackType, MAC: ${result?.device?.address ?: "Unknown"}"
            )
            super.onScanResult(callbackType, result)

            if (callbackType != ScanSettings.CALLBACK_TYPE_MATCH_LOST) {
                if (result != null) {
                    if (!deviceAddressList.contains(result.device.address)) {
                        deviceAddressList += result.device.address
                        deviceFoundCallback(result)
                    }
                }
            }
        }
    }

    fun scanForDevices(
        deviceFoundCallback: BLEDeviceFoundCallback,
        filters: MutableList<ScanFilter>?
    ) {
        if (!scanning) {
            bleScanCallback = BLEScanCallback(deviceFoundCallback)
            val scanSettings = ScanSettings.Builder().build()

            scanHandler.postDelayed({
                stopLastScan()
            }, BLEScannerConstants.SCAN_PERIOD)

            if (filters == null) {
                bluetoothLeScanner.startScan(bleScanCallback)
            } else {
                bluetoothLeScanner.startScan(filters, scanSettings, bleScanCallback)
            }
            scanning = true
            Log.d("BLE Scanner", "Scan started!")
        } else {
            Log.d("BLE Scanner", "Scan is already in progress!")
        }
    }

    fun stopLastScan() {
        if (scanning && bleScanCallback != null) {
            Log.d("BLE Scanner", "Scan stopped!")
            scanning = false
            bluetoothAdapter.bluetoothLeScanner?.stopScan(bleScanCallback)
        }
    }

    fun scanInProgress(): Boolean = scanning
}