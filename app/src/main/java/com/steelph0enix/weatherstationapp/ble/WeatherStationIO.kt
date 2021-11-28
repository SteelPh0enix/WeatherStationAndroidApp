package com.steelph0enix.weatherstationapp.ble

import android.bluetooth.*
import android.util.Log
import com.steelph0enix.weatherstationapp.WeatherStationConstants

private const val LOGTAG = "WeatherStationIO"

data class WeatherRecord(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val temperature: Float,
    val pressure: Float,
    val humidity: Float
)

interface WeatherStationIOCallbacks {
    fun deviceConnected()
    fun deviceDisconnected()
    fun deviceDiscoveryFinished(isSuccessful: Boolean)
    fun recordFetched(record: WeatherRecord)
    fun dateAndTimeChanged()
    fun updateIntervalChanged()
}

class WeatherStationIO(eventCallbacks: WeatherStationIOCallbacks) {
    private val callbacks = eventCallbacks
    private var weatherStationService: BluetoothGattService? = null
    private var weatherStationGatt: BluetoothGatt? = null
    private val weatherStationChars = mutableMapOf<String, BluetoothGattCharacteristic>()

    private var isConnected = false
    private var areServicesDiscovered = false
    private var areCharsDiscovered = false

    private val bluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(LOGTAG, "Connected to station!")
                weatherStationGatt = gatt
                isConnected = true
                callbacks.deviceConnected()
                weatherStationGatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(LOGTAG, "Disconnected from station!")
                cleanupOnDisconnect()
                callbacks.deviceDisconnected()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                findWeatherStationService()
            } else {
                Log.e(LOGTAG, "Looking for services failed with code $status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {

        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {

        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {

        }
    }

    fun isConnectedToStation() = isConnected
    fun areCharacteristicsDiscovered() = areCharsDiscovered
    fun gattCallback() = bluetoothGattCallback

    private fun cleanupOnDisconnect() {
        isConnected = false
        areCharsDiscovered = false
        areServicesDiscovered = false
        weatherStationGatt = null
        weatherStationService = null
        weatherStationChars.clear()
    }

    private fun findWeatherStationService() {
        weatherStationGatt?.services?.let { serviceList ->
            serviceList.forEach { service ->
                if (service.uuid.toString() == WeatherStationConstants.WEATHER_STATION_SERVICE_UUID) {
                    Log.i(LOGTAG, "Found weather station service!")
                    weatherStationService = service
                    findWeatherStationCharacteristics()
                }
            }
        }
    }

    private fun findWeatherStationCharacteristics() {

    }
}