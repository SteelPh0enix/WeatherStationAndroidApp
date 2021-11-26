package com.steelph0enix.weatherstationapp.ble

import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanFilter
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.steelph0enix.weatherstationapp.WeatherStationConstants
import java.util.*

private const val LOGTAG = "WeatherStationService"

class WeatherStationService : Service() {
    companion object {
        const val ACTION_GATT_CONNECTED =
            "com.steelph0enix.weatherstationapp.ble.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED =
            "com.steelph0enix.weatherstationapp.ble.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED =
            "com.steelph0enix.weatherstationapp.ble.ACTION_GATT_SERVICES_DISCOVERED"

        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTED = 2

    }

    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BLEScanner? = null
    private var weatherStationDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var weatherStationService: BluetoothGattService? = null
    private val weatherStationChars: Map<String, BluetoothGattCharacteristic> = mutableMapOf()

    fun getBluetoothAdapter(): BluetoothAdapter? = bluetoothAdapter
    fun isWeatherStationFound(): Boolean {
        return weatherStationDevice != null
    }

    private val bleDeviceFoundCallback: BLEDeviceFoundCallback = {
        weatherStationDevice = it.device
        bleScanner?.stopLastScan()

        Log.i(LOGTAG, "Found weather station device with MAC ${weatherStationDevice?.address}")
        connect()
    }

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(LOGTAG, "Connected to GATT server!")
                bluetoothGatt = gatt
                broadcastUpdate(ACTION_GATT_CONNECTED)
                bluetoothGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(LOGTAG, "Disconnected from GATT server!")
                broadcastUpdate(ACTION_GATT_DISCONNECTED)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(LOGTAG, "Services discovered!")
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
                findWeatherStationService()
            } else {
                Log.w(LOGTAG, "onServicesDiscovered failed with status $status")
            }
        }
    }

    fun initialize(): Boolean {
        Log.i(LOGTAG, "Initializing Weather Station Service")
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        return if (bluetoothAdapter == null) {
            Log.e(LOGTAG, "The device does not have any bluetooth adapter!")
            false
        } else {
            bleScanner = BLEScanner(bluetoothAdapter!!)
            Log.i(LOGTAG, "Bluetooth adapter found, created scanner - WSE initialized")
            true
        }
    }

    fun lookForWeatherStation() {
        if (weatherStationDevice == null) {
            Log.i(LOGTAG, "Started search for weather station device...")
            val filters = mutableListOf<ScanFilter>(
                ScanFilter.Builder()
                    .setDeviceName(WeatherStationConstants.WEATHER_STATION_NAME)
                    .build()
            )

            bleScanner?.scanForDevices(bleDeviceFoundCallback, filters)
        } else {
            Log.i(LOGTAG, "Weather station device already exists")
        }
    }

    fun getSupportedGattServices(): List<BluetoothGattService?>? {
        return bluetoothGatt?.services
    }

    private fun connect(): Boolean {
        Log.i(LOGTAG, "Connecting to weather station...")
        if (!isWeatherStationFound()) {
            return false
        }

        weatherStationDevice?.connectGatt(this, false, bluetoothGattCallback)
        return true
    }

    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }

    private fun close() {
        bluetoothGatt?.let { gatt ->
            gatt.close()
            bluetoothGatt = null
        }
    }

    private fun findWeatherStationService() {
        getSupportedGattServices()?.let { servicesList ->
            servicesList.forEach { service ->
                service?.let {
                    if (service.uuid.toString()
                            .lowercase(Locale.getDefault()) == WeatherStationConstants.WEATHER_STATION_SERVICE_UUID.lowercase(
                            Locale.getDefault()
                        )
                    ) {
                        Log.i(LOGTAG, "Found weather station service with UUID ${service.uuid}")
                        weatherStationService = service
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        close()
        return super.onUnbind(intent)
    }

    inner class LocalBinder : Binder() {
        fun getService(): WeatherStationService {
            return this@WeatherStationService
        }
    }
}