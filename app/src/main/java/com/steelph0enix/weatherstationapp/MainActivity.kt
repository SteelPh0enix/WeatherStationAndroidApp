package com.steelph0enix.weatherstationapp

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattService
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.weatherstationapp.R
import com.steelph0enix.weatherstationapp.ble.WeatherStationService

class MainActivity : AppCompatActivity() {
    private lateinit var toolbar: Toolbar

    private var weatherStationService: WeatherStationService? = null

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            weatherStationService = (service as WeatherStationService.LocalBinder).getService()
            weatherStationService?.let { wsService ->
                Log.d("MainActivity", "Starting WeatherStationService")
                if (wsService.initialize()) {
                    checkBLEPermissions()
                    checkIfBLEIsOn()
                    wsService.beginConnectionProcess()
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
                WeatherStationService.ACTION_CONNECTED -> {
                    Log.i("MainActivity", "Connected to station!")
                }
                WeatherStationService.ACTION_DISCONNECTED -> {
                    Log.i("MainActivity", "Disconnected from station!")
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

        setSupportActionBar(toolbar)

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
                if (weatherStationService != null && weatherStationService?.isConnected() == true) {
                    Log.d("MainActivity", "Not connected to weather station, scanning...")
                    weatherStationService?.beginConnectionProcess()
                } else {
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
        if (weatherStationService != null) {
            val result = weatherStationService?.beginConnectionProcess()
            Log.d("MainActivity", "Started looking for weather station after resuming app...")
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(gattUpdateReceiver)
    }

    private fun makeGattUpdateIntentFilter(): IntentFilter? {
        return IntentFilter().apply {
            addAction(WeatherStationService.ACTION_CONNECTED)
            addAction(WeatherStationService.ACTION_DISCONNECTED)
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
}