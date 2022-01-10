package com.steelph0enix.weatherstationapp.ble

import android.bluetooth.*
import android.util.Log
import com.steelph0enix.weatherstationapp.WeatherStationConstants
import java.util.*

private const val LOGTAG = "WeatherStationIO"

data class WeatherRecord(
    var year: Int = 0,
    var month: Int = 0,
    var day: Int = 0,
    var hour: Int = 0,
    var minute: Int = 0,
    var second: Int = 0,
    var temperature: Float = 0f,
    var pressure: Float = 0f,
    var humidity: Float = 0f
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
    private var currentWeatherRecord = WeatherRecord()
    private var currentControlByteValue: Byte = 0
    private var isFetchingRecords = false
    private var currentlyStoredRecordsCount = 0


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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // nothing to do here, really
                Log.i(LOGTAG, "Write was successful!")
            }
            // run next operation
            runNextOperationFromQueue()
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(LOGTAG, "Read was successful!")

                when (characteristic?.uuid.toString()) {
                    WeatherStationConstants.WEATHER_STATION_CHAR_UUID_NO_OF_RECORDS -> {
                        characteristic?.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 0)
                            ?.let {
                                currentlyStoredRecordsCount = it
                                callbacks.amountOfRecordsRead(it)
                            }
                    }
                    WeatherStationConstants.WEATHER_STATION_CHAR_UUID_TIME -> {
                        currentWeatherRecord.second =
                            characteristic?.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 2)
                                ?: 0
                        currentWeatherRecord.minute =
                            characteristic?.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1)
                                ?: 0
                        currentWeatherRecord.hour =
                            characteristic?.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                                ?: 0
                        Log.d(
                            LOGTAG,
                            "Time read: ${currentWeatherRecord.hour}:${currentWeatherRecord.minute}:${currentWeatherRecord.second}"
                        )
                    }
                    WeatherStationConstants.WEATHER_STATION_CHAR_UUID_DATE -> {
                        currentWeatherRecord.day =
                            characteristic?.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 2)
                                ?: 0
                        currentWeatherRecord.month =
                            characteristic?.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1)
                                ?: 0
                        currentWeatherRecord.year = 2000 + (characteristic?.getIntValue(
                            BluetoothGattCharacteristic.FORMAT_UINT8,
                            0
                        ) ?: 0)
                        Log.d(
                            LOGTAG,
                            "Date read: ${currentWeatherRecord.day}-${currentWeatherRecord.month}-${currentWeatherRecord.year}"
                        )
                    }
                    WeatherStationConstants.WEATHER_STATION_CHAR_UUID_TEMPERATURE -> {
                        currentWeatherRecord.temperature = (characteristic?.getIntValue(
                            BluetoothGattCharacteristic.FORMAT_SINT32,
                            0
                        ) ?: 0).toFloat() / 100f
                        Log.d(LOGTAG, "Temperature read: ${currentWeatherRecord.temperature}")
                    }
                    WeatherStationConstants.WEATHER_STATION_CHAR_UUID_PRESSURE -> {
                        currentWeatherRecord.pressure = (characteristic?.getIntValue(
                            BluetoothGattCharacteristic.FORMAT_SINT32,
                            0
                        ) ?: 0).toFloat() / 100f
                        Log.d(LOGTAG, "Pressure read: ${currentWeatherRecord.temperature}")

                    }
                    WeatherStationConstants.WEATHER_STATION_CHAR_UUID_HUMIDITY -> {
                        currentWeatherRecord.humidity = (characteristic?.getIntValue(
                            BluetoothGattCharacteristic.FORMAT_SINT32,
                            0
                        ) ?: 0).toFloat() / 100f
                        Log.d(LOGTAG, "Humidity read: ${currentWeatherRecord.temperature}")

                        // Humidity is last, so we make a bold assumption and
                        // notify about new record, as it should be complete now
                        notifyAboutNewRecord()
                    }
                }
            }
            // run next operation
            runNextOperationFromQueue()
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
                        currentControlByteValue = controlByteValue.toByte()

                        // We're fetching records, so proceed...
                        if (currentControlByteValue == WeatherStationConstants.WEATHER_STATION_CONTROL_NEXT_RECORD_AVAILABLE) {
                            fetchNextRecord()
                        }
                    }
                }
                WeatherStationConstants.WEATHER_STATION_CHAR_UUID_NO_OF_RECORDS -> {
                    val weatherRecordsCount = characteristic?.value?.get(0)?.toInt() ?: -1
                    if (weatherRecordsCount >= 0) {
                        Log.i(
                            LOGTAG,
                            "There are $weatherRecordsCount records available on the device"
                        )
                        currentlyStoredRecordsCount = weatherRecordsCount
                        callbacks.amountOfRecordsRead(weatherRecordsCount)
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            runNextOperationFromQueue()
        }
    }

    fun isConnectedToStation() = isConnected
    fun areCharacteristicsDiscovered() = areCharsDiscovered
    fun gattCallback() = bluetoothGattCallback
    fun isFetchingRecords() = isFetchingRecords

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

    fun startFetchingRecords() {
        if (currentlyStoredRecordsCount > 0) {
            isFetchingRecords = true
            writeControlByte(WeatherStationConstants.WEATHER_STATION_CONTROL_GET_DATA)
        }
    }

    private fun notifyAboutNewRecord() {
        callbacks.recordFetched(currentWeatherRecord)
    }

    private fun fetchNextRecord() {
        readChar(WeatherStationConstants.WEATHER_STATION_CHAR_UUID_TIME)
        readChar(WeatherStationConstants.WEATHER_STATION_CHAR_UUID_DATE)
        readChar(WeatherStationConstants.WEATHER_STATION_CHAR_UUID_TEMPERATURE)
        readChar(WeatherStationConstants.WEATHER_STATION_CHAR_UUID_PRESSURE)
        readChar(WeatherStationConstants.WEATHER_STATION_CHAR_UUID_HUMIDITY)
        if (currentlyStoredRecordsCount > 0) {
            writeControlByte(WeatherStationConstants.WEATHER_STATION_CONTROL_FETCH_NEXT_RECORD)
        } else {
            isFetchingRecords = false
        }
    }

    private fun enableOperationQueue() {
        if (operationQueue.size == 1) {
            val lastOp = operationQueue.last
            lastOp()
        }
    }

    private fun runNextOperationFromQueue() {
        operationQueue.removeLast()
        if (operationQueue.size > 0) {
            val lastOp = operationQueue.last
            lastOp()
        }
    }

    private fun readChar(charUUID: String) {
        Log.d(LOGTAG, "Requested read from char $charUUID")
        val charToRead = weatherStationChars[charUUID] ?: return

        operationQueue.addFirst {
            Log.d(LOGTAG, "Starting read from characteristic ${charUUID}!")
            weatherStationGatt?.readCharacteristic(charToRead)
        }

        enableOperationQueue()
    }

    private fun writeChar(char: BluetoothGattCharacteristic) {
        Log.d(LOGTAG, "Requested write to char ${char.uuid.toString()}")

        operationQueue.addFirst {
            Log.d(LOGTAG, "Starting write to characteristic ${char.uuid.toString()}!")
            weatherStationGatt?.writeCharacteristic(char)
        }

        enableOperationQueue()
    }

    private fun enableCharNotifications(char: BluetoothGattCharacteristic?) {
        val CCCDUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val descriptor = char?.getDescriptor(CCCDUUID)

        operationQueue.addFirst {
            weatherStationGatt?.setCharacteristicNotification(char, true)
            descriptor?.value = (BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            weatherStationGatt?.writeDescriptor(descriptor)
        }

        enableOperationQueue()
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

        // Setup control char notifications
        val controlChar =
            weatherStationChars[WeatherStationConstants.WEATHER_STATION_CHAR_UUID_CONTROL]
        val numberOfMeasurementsChar =
            weatherStationChars[WeatherStationConstants.WEATHER_STATION_CHAR_UUID_NO_OF_RECORDS]
        enableCharNotifications(controlChar)
        enableCharNotifications(numberOfMeasurementsChar)

        areCharsDiscovered = true
        callbacks.deviceDiscoveryFinished(true)
    }
}