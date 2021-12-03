package com.steelph0enix.weatherstationapp.ble

import android.bluetooth.*
import android.util.Log
import com.steelph0enix.weatherstationapp.WeatherStationConstants
import java.util.*

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
    fun amountOfRecordsRead(amount: Int)
}

class WeatherStationIO(eventCallbacks: WeatherStationIOCallbacks) {
    private val callbacks = eventCallbacks
    private var weatherStationService: BluetoothGattService? = null
    private var weatherStationGatt: BluetoothGatt? = null
    private val weatherStationChars = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val operationQueue = ArrayDeque<() -> Unit>()

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
                areServicesDiscovered = true
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
            Log.d(
                LOGTAG,
                "Characteristic ${characteristic?.uuid.toString()} written with status $status"
            )
            // remove last operation (the one that caused the callback)
            operationQueue.removeLast()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // nothing to do here, really
                Log.i(LOGTAG, "Write was successful!")
            }
            // run next operation
            if (operationQueue.size > 0) {
                val nextOp = operationQueue.last
                nextOp()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            Log.d(
                LOGTAG,
                "Characteristic ${characteristic?.uuid.toString()} read with status $status"
            )
            // remove last operation (the one that caused the callback)
            operationQueue.removeLast()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(LOGTAG, "Read was successful!")

                when (characteristic?.uuid.toString()) {
                    WeatherStationConstants.WEATHER_STATION_CHAR_UUID_NO_OF_RECORDS -> {
                        characteristic?.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0)
                            ?.let { callbacks.amountOfRecordsRead(it) }
                    }
                }
            }
            // run next operation
            if (operationQueue.size > 0) {
                val lastOp = operationQueue.last
                lastOp()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.d(LOGTAG, "Characteristic ${characteristic?.uuid.toString()} changed!")
            when (characteristic?.uuid.toString()) {
                WeatherStationConstants.WEATHER_STATION_CHAR_UUID_CONTROL -> {
                    val controlByteValue = characteristic?.value?.get(0)?.toInt() ?: -1
                    if (controlByteValue >= 0) {
                        Log.i(LOGTAG, "Control byte value changed to $controlByteValue")
                    }
                }
            }
        }
    }

    fun isConnectedToStation() = isConnected
    fun areCharacteristicsDiscovered() = areCharsDiscovered
    fun gattCallback() = bluetoothGattCallback

    fun getAmountOfRecords() {
        readChar(WeatherStationConstants.WEATHER_STATION_CHAR_UUID_NO_OF_RECORDS)
    }

    fun setCurrentDateAndTime() {
        val currentDateAndTime = Calendar.getInstance()
        val currentYear = currentDateAndTime.get(Calendar.YEAR) - 2000
        val currentMonth = currentDateAndTime.get(Calendar.MONTH) + 1
        val currentDay = currentDateAndTime.get(Calendar.DAY_OF_MONTH)
        val currentDayOfWeek = currentDateAndTime.get(Calendar.DAY_OF_WEEK)
        val currentHour = currentDateAndTime.get(Calendar.HOUR_OF_DAY)
        val currentMinute = currentDateAndTime.get(Calendar.MINUTE)
        val currentSecond = currentDateAndTime.get(Calendar.SECOND)

        writeDate(currentYear, currentMonth, currentDay, currentDayOfWeek)
        writeTime(currentHour, currentMinute, currentSecond)
        writeControlByte(WeatherStationConstants.WEATHER_STATION_CONTROL_SET_DATE_AND_TIME)
    }

    private fun readChar(charUUID: String) {
        Log.d(LOGTAG, "Requested read from char $charUUID")
        val charToRead = weatherStationChars[charUUID] ?: return

        operationQueue.addFirst {
            Log.d(LOGTAG, "Starting read from characteristic ${charUUID}!")
            weatherStationGatt?.readCharacteristic(charToRead)
        }

        if (operationQueue.size == 1) {
            val lastOp = operationQueue.last
            lastOp()
        }
    }

    private fun writeChar(char: BluetoothGattCharacteristic) {
        Log.d(LOGTAG, "Requested write to char ${char.uuid.toString()}")

        operationQueue.addFirst {
            Log.d(LOGTAG, "Starting write to characteristic ${char.uuid.toString()}!")
            weatherStationGatt?.writeCharacteristic(char)
        }

        if (operationQueue.size == 1) {
            val lastOp = operationQueue.last
            lastOp()
        }
    }

    private fun writeDate(year: Int, month: Int, day: Int, dayOfWeek: Int) {
        val dateChar =
            weatherStationChars[WeatherStationConstants.WEATHER_STATION_CHAR_UUID_DATE]
                ?: return

        dateChar.value = byteArrayOf(
            year.toByte(), month.toByte(), day.toByte(), dayOfWeek.toByte()
        )

        writeChar(dateChar)
    }

    private fun writeTime(hour: Int, minute: Int, second: Int) {
        val timeChar = weatherStationChars[WeatherStationConstants.WEATHER_STATION_CHAR_UUID_TIME]
            ?: return

        timeChar.value = byteArrayOf(hour.toByte(), minute.toByte(), second.toByte())

        writeChar(timeChar)
    }

    private fun writeControlByte(value: Byte) {
        val controlChar =
            weatherStationChars[WeatherStationConstants.WEATHER_STATION_CHAR_UUID_CONTROL]
                ?: return

        controlChar.value = byteArrayOf(value)

        writeChar(controlChar)
    }

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
                    return
                }
            }
        }

        // In case when the service is not found, return won't execute, so...
        callbacks.deviceDiscoveryFinished(false)
    }

    private fun findWeatherStationCharacteristics() {
        if (weatherStationService == null || weatherStationService?.characteristics?.isEmpty() == true) {
            callbacks.deviceDiscoveryFinished(false)
            return
        }

        weatherStationService?.characteristics?.forEach { char ->
            weatherStationChars.putIfAbsent(char.uuid.toString(), char)
        }

        areCharsDiscovered = true
        callbacks.deviceDiscoveryFinished(true)
    }
}