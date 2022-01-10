package com.steelph0enix.weatherstationapp.ble

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.steelph0enix.weatherstationapp.WeatherStationConstants
import java.util.*

private const val LOGTAG = "WeatherStationService"

// This class is a service of weather station.
// It's responsibility is to handle finding the device, connecting to it and discovering
// it's characteristics. After that, the control is handled to WeatherStationIO object
// which handles the actual communication.
// This service is also responsible of broadcasting state changes and operation results.

class WeatherStationService : Service() {
    // Values
    private val serviceBinder = LocalBinder()

    // Variables
    private var btAdapter: BluetoothAdapter? = null
    private var btScanner: BLEScanner? = null
    private var wsDevice: BluetoothDevice? = null
    private var wsIO: WeatherStationIO? = null
    private var currentAmountOfRecordsAvailable = 0
    private var recordList = mutableListOf<WeatherRecord>()

    // Inline/companion objects
    companion object {
        const val ACTION_STATION_FOUND =
            "com.steelph0enix.weatherstationapp.ble.ACTION_STATION_FOUND"
        const val ACTION_CONNECTED = "com.steelph0enix.weatherstationapp.ble.ACTION_CONNECTED"
        const val ACTION_DISCONNECTED = "com.steelph0enix.weatherstationapp.ble.ACTION_DISCONNECTED"
        const val AMOUNT_OF_RECORDS_READ =
            "com.steelph0enix.weatherstationapp.ble.AMOUNT_OF_RECORDS_READ"
        const val RECORD_FETCHED = "com.steelph0enix.weatherstationapp.ble.RECORD_FETCHED"
    }

    private val btDeviceFoundCallback: BLEDeviceFoundCallback = {
        wsDevice = it.device
        btScanner?.stopLastScan()

        Log.i(LOGTAG, "Found weather station device with MAC ${wsDevice?.address}")
        broadcastUpdate(ACTION_STATION_FOUND)
        connectToWeatherStation()
    }

    private val wsIOCallbacks = object : WeatherStationIOCallbacks {
        override fun deviceConnected() {
            Log.i(LOGTAG, "Device connected (callback)!")
            broadcastUpdate(ACTION_CONNECTED)
        }

        override fun deviceDisconnected() {
            Log.i(LOGTAG, "Device disconnected (callback)!")
            broadcastUpdate(ACTION_DISCONNECTED)
        }

        override fun deviceDiscoveryFinished(isSuccessful: Boolean) {
            Log.i(LOGTAG, "Device discovery finished (callback) with status $isSuccessful!")
            wsIO?.setCurrentDateAndTime()
        }

        override fun recordFetched(record: WeatherRecord) {
            Log.i(LOGTAG, "Weather station record fetched (callback)!")
            Log.i(
                LOGTAG,
                "Record data:\n\t${record.year}-${record.month}-${record.day}, ${record.hour}:${record.minute}:${record.second}\n\tTemperature: ${record.temperature}*C\n\tPressure: ${record.pressure}hPa\n\tHumidity: ${record.humidity}%"
            )
            recordList += record
            broadcastUpdate(RECORD_FETCHED)
        }

        override fun dateAndTimeChanged() {
            Log.i(LOGTAG, "Date and time changed (callback)!")
        }

        override fun updateIntervalChanged() {
            Log.i(LOGTAG, "Update interval changed (callback)!")
        }

        override fun amountOfRecordsRead(amount: Int) {
            Log.i(
                LOGTAG,
                "Amount of records read (callback), there's $amount records on the station!"
            )
            currentAmountOfRecordsAvailable = amount
            broadcastUpdate(AMOUNT_OF_RECORDS_READ)
        }
    }

    // Public methods
    /// Getters/setters
    fun bluetoothAdapter() = btAdapter
    fun isConnected() = wsIO?.isConnectedToStation() ?: false
    fun isInitialized() = bluetoothAdapter() != null
    fun isDeviceFound() = wsDevice != null

    fun amountOfRecords() = currentAmountOfRecordsAvailable

    /// Other methods
    fun initialize(): Boolean {
        Log.i(LOGTAG, "Started initialization process...")
        btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null) {
            Log.e(LOGTAG, "Couldn't get default bluetooth adapter!")
            return false
        }

        btScanner = BLEScanner(btAdapter!!)
        Log.i(LOGTAG, "Init successful!")
        return true
    }

//    fun close() {
//
//    }

    fun beginConnectionProcess(): Boolean {
        Log.i(LOGTAG, "Beginning connection process...")
        if (!isInitialized()) {
            Log.e(
                LOGTAG,
                "Tried to begin connection process before initializing the service, aborting!"
            )
            return false
        }

        startScanForWeatherStation()
        return true
    }

    fun updateAmountOfRecords() {
        wsIO?.getAmountOfRecords()
    }

    fun startDataFetching() {
        wsIO?.startFetchingRecords()
    }

    fun getLatestWeatherRecord(): WeatherRecord {
        return recordList.last()
    }

    fun getLatestWeatherRecordIndex(): Int {
        return recordList.lastIndex
    }

    // Private methods
    private fun startScanForWeatherStation() {
        Log.i(LOGTAG, "Starting BLE scan for weather station...")

        val filters = mutableListOf<ScanFilter>(
            ScanFilter.Builder()
                .setDeviceName(WeatherStationConstants.WEATHER_STATION_NAME)
                .build()
        )

        btScanner?.scanForDevices(btDeviceFoundCallback, filters)
    }

    private fun connectToWeatherStation(): Boolean {
        Log.i(LOGTAG, "Connecting to weather station...")
        if (!isDeviceFound()) {
            Log.e(LOGTAG, "Weather station hasn't been found yet, aborting!")
            return false
        }

        wsIO = WeatherStationIO(wsIOCallbacks)
        wsDevice?.connectGatt(this, false, wsIO?.gattCallback())
        return true
    }

    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }

    // Overridden methods
    override fun onBind(intent: Intent?): IBinder {
        return serviceBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
//        close()
        return super.onUnbind(intent)
    }

    // Inner classes
    inner class LocalBinder : Binder() {
        fun getService(): WeatherStationService {
            return this@WeatherStationService
        }
    }
}


