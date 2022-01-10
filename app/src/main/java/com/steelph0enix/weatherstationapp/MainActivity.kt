package com.steelph0enix.weatherstationapp

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattService
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.weatherstationapp.R
import com.github.mikephil.charting.charts.LineChart
import com.steelph0enix.weatherstationapp.ble.WeatherStationService

class MainActivity : AppCompatActivity() {
    enum class AppState {
        IDLE,
        LOOKING_FOR_STATION,
        CONNECTING,
        CONNECTED,
        FETCHING_DATA
    }

    private lateinit var toolbar: Toolbar
    private lateinit var temperatureTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var pressureTextView: TextView
    private lateinit var humidityTextView: TextView
    private lateinit var temperatureChart: LineChart
    private lateinit var pressureChart: LineChart
    private lateinit var humidityChart: LineChart

    private lateinit var temperatureChartManager: LineChartManager
    private lateinit var pressureChartManager: LineChartManager
    private lateinit var humidityChartManager: LineChartManager

    private var weatherStationService: WeatherStationService? = null
    private var appState = AppState.IDLE

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            weatherStationService = (service as WeatherStationService.LocalBinder).getService()
            weatherStationService?.let { wsService ->
                Log.d("MainActivity", "Starting WeatherStationService")
                if (wsService.initialize()) {
                    checkBLEPermissions()
                    checkIfBLEIsOn()
                    wsService.beginConnectionProcess()
                    setAppState(AppState.LOOKING_FOR_STATION)
                } else {
                    Log.e("MainActivity", "Couldn't init weather station service, aborting!")
                    weatherStationService = null
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            weatherStationService = null
        }
    }

    private val gattUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WeatherStationService.ACTION_STATION_FOUND -> {
                    Log.i("MainActivity", "Station found, connecting...")
                    setAppState(AppState.CONNECTING)
                }
                WeatherStationService.ACTION_CONNECTED -> {
                    Log.i("MainActivity", "Connected to station!")
                    setAppState(AppState.CONNECTED)
                }
                WeatherStationService.ACTION_DISCONNECTED -> {
                    Log.i("MainActivity", "Disconnected from station!")
                }
                WeatherStationService.AMOUNT_OF_RECORDS_READ -> {
                    Log.i(
                        "MainActivity",
                        "Amount of records on the device: ${weatherStationService?.amountOfRecords()}"
                    )
                    weatherStationService?.amountOfRecords()?.let { records ->
                        if (records > 0) {
                            weatherStationService?.startDataFetching()
                        }
                    }
                }
                WeatherStationService.RECORD_FETCHED -> {
                    weatherStationService?.let { service ->
                        val lastRecord = service.getLatestWeatherRecord()
                        val lastRecordIndex = service.getLatestWeatherRecordIndex()

                        temperatureTextView.text =
                            getString(R.string.temperature_value_celcius, lastRecord.temperature)
                        pressureTextView.text =
                            getString(R.string.pressure_value, lastRecord.pressure)
                        humidityTextView.text =
                            getString(R.string.humidity_value, lastRecord.humidity)

                        temperatureChartManager.addValue(
                            lastRecordIndex.toFloat(),
                            lastRecord.temperature
                        )
                        pressureChartManager.addValue(
                            lastRecordIndex.toFloat(),
                            lastRecord.pressure
                        )
                        humidityChartManager.addValue(
                            lastRecordIndex.toFloat(),
                            lastRecord.humidity
                        )
                    }

                }
            }
        }
    }

    object MainActivityConstants {
        object IntentCodes {
            const val REQUEST_BLE_PERMISSIONS = 1
            const val REQUEST_ENABLE_BT = 2
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.app_toolbar)
        statusTextView = findViewById(R.id.text_view_status)
        temperatureTextView = findViewById(R.id.text_view_temperature)
        pressureTextView = findViewById(R.id.text_view_pressure)
        humidityTextView = findViewById(R.id.text_view_humidity)
        temperatureChart = findViewById(R.id.temperature_chart)
        pressureChart = findViewById(R.id.pressure_chart)
        humidityChart = findViewById(R.id.humidity_chart)

        temperatureChartManager = LineChartManager(temperatureChart, applicationContext)
        pressureChartManager = LineChartManager(pressureChart, applicationContext)
        humidityChartManager = LineChartManager(humidityChart, applicationContext)

        setSupportActionBar(toolbar)

        temperatureChartManager.initializeChart("Temperature", Color.RED, 0f, 50f)
        pressureChartManager.initializeChart("Pressure", Color.GREEN, 900f, 1100f)
        humidityChartManager.initializeChart("Humidity", Color.BLUE, 0f, 100f)

        setAppState(AppState.IDLE)

        val gattServiceIntent = Intent(this, WeatherStationService::class.java)
        bindService(gattServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.app_toolbar, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_refresh_data -> {
                Log.d("MainActivity", "Refresh data clicked!")
                if (weatherStationService == null || weatherStationService?.isConnected() == false) {
                    Log.d("MainActivity", "Not connected to weather station, scanning...")
                    weatherStationService?.beginConnectionProcess()
                } else {
                    weatherStationService?.updateAmountOfRecords()
                }
                true
            }
            R.id.menu_settings -> {
                Log.d("MainActivity", "Settings clicked!")
                true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == MainActivityConstants.IntentCodes.REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                Log.i("Main activity", "BLE enabled!")
            } else {
                Log.w("Main activity", "BLE is NOT enabled!")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(gattUpdateReceiver, makeGattUpdateIntentFilter())
        if (weatherStationService != null && weatherStationService?.isConnected() == false) {
            weatherStationService?.beginConnectionProcess()
            setAppState(AppState.LOOKING_FOR_STATION)
            Log.d("MainActivity", "Started looking for weather station after resuming app...")
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(gattUpdateReceiver)
    }

    private fun updateChartData() {

    }

    private fun makeGattUpdateIntentFilter(): IntentFilter {
        return IntentFilter().apply {
            addAction(WeatherStationService.ACTION_STATION_FOUND)
            addAction(WeatherStationService.ACTION_CONNECTED)
            addAction(WeatherStationService.ACTION_DISCONNECTED)
            addAction(WeatherStationService.AMOUNT_OF_RECORDS_READ)
            addAction(WeatherStationService.RECORD_FETCHED)
        }
    }

    private fun checkBLEPermissions() {
        val permissionCheck =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                MainActivityConstants.IntentCodes.REQUEST_BLE_PERMISSIONS
            )
        }
    }

    private fun checkIfBLEIsOn() {
        if (weatherStationService != null &&
            weatherStationService?.bluetoothAdapter() != null &&
            weatherStationService?.bluetoothAdapter()?.isEnabled == false
        ) {
            Log.i("Main activity", "Starting BLE enable intent...")
            val resultLauncher =
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        Log.i("Main activity", "BLE enabled!")
                    }
                }

            val enableBleIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            resultLauncher.launch(enableBleIntent)
        } else {
            Log.i("Main activity", "BLE already enabled!")
        }
    }

    private fun displayGattServices(servicesList: List<BluetoothGattService?>?) {
        servicesList?.let { list ->
            list.forEach { service ->
                if (service != null) {
                    Log.i("MainActivity", "Found service with UUID ${service.uuid}")
                }
            }
        }
    }

    private fun setAppState(newState: AppState) {
        when (newState) {
            AppState.IDLE -> statusTextView.text = getString(R.string.status_idle)
            AppState.LOOKING_FOR_STATION -> statusTextView.text =
                getString(R.string.status_looking_for_station)
            AppState.CONNECTING -> statusTextView.text = getString(R.string.status_connecting)
            AppState.CONNECTED -> {
                statusTextView.text = getString(R.string.status_connected)
                if (weatherStationService?.amountOfRecords()!! > 0) {
                    setAppState(AppState.FETCHING_DATA)
                }
            }
            AppState.FETCHING_DATA -> {
                statusTextView.text = getString(
                    R.string.status_fetching_data,
                    weatherStationService?.amountOfRecords()
                )
            }
        }

        appState = newState
    }
}