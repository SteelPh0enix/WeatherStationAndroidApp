package com.steelph0enix.weatherstationapp.ble

import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanFilter
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.steelph0enix.weatherstationapp.WeatherStationConstants
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        const val ACTION_GATT_CHARACTERISTICS_DISCOVERED =
            "com.steelph0enix.weatherstationapp.ble.ACTION_GATT_CHARACTERISTICS_DISCOVERED"
        const val ACTION_DATA_RECEIVED =
            "com.steelph0enix.weatherstationapp.ble.ACTION_DATA_RECEIVED"
        const val ACTION_DATA_AVAILABLE =
            "com.steelph0enix.weatherstationapp.ble.ACTION_DATA_AVAILABLE"

        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTED = 1

        enum class IOState {
            IDLE, DATE_SET, TIME_SET,
        }
    }

    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BLEScanner? = null
    private var weatherStationDevice: BluetoothDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var weatherStationService: BluetoothGattService? = null
    private val weatherStationChars = mutableMapOf<String, BluetoothGattCharacteristic>()
    private var commIOState: IOState = IOState.IDLE

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

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.i(LOGTAG, "Char ${characteristic?.uuid} changed")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.i(LOGTAG, "Char ${characteristic?.uuid} written with status $status")
            when (characteristic?.uuid.toString()) {
                WeatherStationConstants.WEATHER_STATION_CHAR_UUID_DATE -> {
                    commIOState = IOState.DATE_SET
                }
                WeatherStationConstants.WEATHER_STATION_CHAR_UUID_TIME -> {
                    commIOState = IOState.TIME_SET
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.i(LOGTAG, "Char ${characteristic?.uuid} read with status $status")
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

    fun setCurrentDateAndTime() {
        val dateChar = getCharacteristic(WeatherStationConstants.WEATHER_STATION_CHAR_UUID_DATE)
        val timeChar = getCharacteristic(WeatherStationConstants.WEATHER_STATION_CHAR_UUID_TIME)

        if (dateChar == null || timeChar == null) {
            Log.e(
                LOGTAG,
                "Couldn't find time or date characteristic! Aborting setting date and time!"
            )
            return
        }

        val currentDateAndTime = Calendar.getInstance()
        val currentYear = currentDateAndTime.get(Calendar.YEAR) - 2000
        val currentMonth = currentDateAndTime.get(Calendar.MONTH) + 1
        val currentDay = currentDateAndTime.get(Calendar.DAY_OF_MONTH)
        val currentDayOfWeek = currentDateAndTime.get(Calendar.DAY_OF_WEEK)
        val currentHour = currentDateAndTime.get(Calendar.HOUR_OF_DAY)
        val currentMinute = currentDateAndTime.get(Calendar.MINUTE)
        val currentSecond = currentDateAndTime.get(Calendar.SECOND)

        Log.i(
            LOGTAG,
            "Current date and time: $currentYear-$currentMonth-$currentDay, $currentHour:$currentMinute:$currentSecond"
        )

        val dateBytes =
            byteArrayOf(
                currentYear.toByte(),
                currentMonth.toByte(),
                currentDay.toByte(),
                currentDayOfWeek.toByte()
            )
        val timeBytes =
            byteArrayOf(currentHour.toByte(), currentMinute.toByte(), currentSecond.toByte())

        dateChar.value = dateBytes
        timeChar.value = timeBytes

        GlobalScope.launch {
            bluetoothGatt?.writeCharacteristic(dateChar)
            while (commIOState != IOState.DATE_SET) {
                delay(10)
            }
            bluetoothGatt?.writeCharacteristic(timeChar)
            while (commIOState != IOState.TIME_SET) {
                delay(10)
            }
            commIOState = IOState.IDLE
            setControlByteValue(WeatherStationConstants.WEATHER_STATION_CONTROL_SET_DATE_AND_TIME)
        }
    }

    fun setRefreshInterval(hours: Number, minutes: Number, seconds: Number) {

    }

    fun weatherRecordsAvailable(): Number {
        return 0
    }

    fun fetchRecord() {

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
                    if (service.uuid.toString() == WeatherStationConstants.WEATHER_STATION_SERVICE_UUID) {
                        Log.i(LOGTAG, "Found weather station service with UUID ${service.uuid}")
                        weatherStationService = service
                        findWeatherStationCharacteristics()
                    }
                }
            }
        }
    }

    private fun findWeatherStationCharacteristics() {
        weatherStationChars.clear()
        weatherStationService?.characteristics?.forEach { bleChar ->
            weatherStationChars.putIfAbsent(bleChar.uuid.toString(), bleChar)
        }

        Log.i(LOGTAG, "Found ${weatherStationChars.size} characteristics")
        broadcastUpdate(ACTION_GATT_CHARACTERISTICS_DISCOVERED)
    }

    private fun getCharacteristic(uuid: String): BluetoothGattCharacteristic? {
        return weatherStationChars[uuid]
    }

    private fun setControlByteValue(value: Byte) {
        val controlChar =
            getCharacteristic(WeatherStationConstants.WEATHER_STATION_CHAR_UUID_CONTROL)

        controlChar?.value = byteArrayOf(value)

        GlobalScope.launch {
            while (commIOState != IOState.IDLE) {
                delay(10)
            }
            bluetoothGatt?.writeCharacteristic(controlChar)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
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