package com.example.aicondev

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.material.switchmaterial.SwitchMaterial
import com.skydroid.fpvplayer.FPVWidget
import com.skydroid.rcsdk.KeyManager
import com.skydroid.rcsdk.RCSDKManager
import com.skydroid.rcsdk.SDKManagerCallBack
import com.skydroid.rcsdk.common.callback.CompletionCallbackWith
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.key.RemoteControllerKey
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var fpvWidget: FPVWidget
    private lateinit var modeSwitch: SwitchMaterial
    private lateinit var locationText: TextView
    private lateinit var excavatorImage: ImageView

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var geocoder: Geocoder

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var pollingRunnable: Runnable
    private lateinit var reconnectRunnable: Runnable

    private var connectionStatus: String = "等待权限..."
    private val TAG = "AICON_DEV" // 定义一个统一的日志标签

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                Log.i(TAG, "权限已授予，正在初始化...")
                initRCSDK()
                startLocationUpdates()
            } else {
                Log.e(TAG, "权限被拒绝，SDK无法启动！")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Make the activity fullscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }

        fpvWidget = findViewById(R.id.fpvWidget)
        modeSwitch = findViewById(R.id.mode_switch)
        locationText = findViewById(R.id.location_text)
        excavatorImage = findViewById(R.id.excavator_image)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        geocoder = Geocoder(this, Locale.getDefault())

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    try {
                        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        val address = addresses?.firstOrNull()
                        val city = address?.locality ?: "Unknown City"
                        val country = address?.countryName ?: "Unknown Country"
                        val newLocationText = "$city, $country\nLat: ${location.latitude}, Lon: ${location.longitude}"
                        locationText.text = newLocationText
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting address from location", e)
                        val newLocationText = "Lat: ${location.latitude}, Lon: ${location.longitude}"
                        locationText.text = newLocationText
                    }
                }
            }
        }

        modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                modeSwitch.text = "Auto Mode"
            } else {
                modeSwitch.text = "Remote Mode"
            }
        }

        // 定义轮询和重连的具体任务
        pollingRunnable = Runnable { getJoystickData() }
        reconnectRunnable = Runnable { tryToReconnect() }
        initFpvPlayer()
        checkAndRequestPermissions()
    }
    private fun initFpvPlayer() {
        // 根据文档设置视频流地址，这是必须的
        fpvWidget.url = "rtsp://192.168.144.108:554/stream=0"
        Log.i(TAG, "图传播放器已设置地址: ${fpvWidget.url}")

        // 您也可以在这里进行其他播放器配置，例如：
        // fpvWidget.usingMediaCodec = true // 开启硬解
    }
    private fun checkAndRequestPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            Log.i(TAG, "权限已就绪，正在初始化...")
            initRCSDK()
            startLocationUpdates()
        } else {
            Log.i(TAG, "需要权限以连接硬件...")
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun initRCSDK() {
        RCSDKManager.initSDK(this, object : SDKManagerCallBack {
            override fun onRcConnected() {
                mainHandler.removeCallbacks(reconnectRunnable) // 连接成功，取消任何待处理的重连任务
                connectionStatus = "遥控器已连接！"
                Log.i(TAG, connectionStatus)
                startPolling()
            }

            override fun onRcConnectFail(e: SkyException?) {
                connectionStatus = "连接失败: ${e?.message}"
                Log.e(TAG, connectionStatus)
                stopPolling()
                scheduleReconnect() // 连接失败，启动带延迟的重连
            }

            override fun onRcDisconnect() {
                connectionStatus = "遥控器断开"
                Log.w(TAG, connectionStatus)
                stopPolling()
                scheduleReconnect() // 连接断开，启动带延迟的重连
            }
        })

        // 首次尝试连接
        tryToReconnect()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: 恢复应用，启动图传并尝试连接遥控器")
        // 启动图传
        fpvWidget.start()
        // 尝试连接遥控器
        tryToReconnect()
        startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: 暂停应用，停止所有服务")
        // 停止图传
        fpvWidget.stop()
        // 断开遥控器连接
        RCSDKManager.disconnectRC()
        // 停止所有待处理的任务
        stopPolling()
        mainHandler.removeCallbacks(reconnectRunnable)
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: 应用销毁")
        // onPause 中已处理大部分资源释放，这里确保万无一失
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun scheduleReconnect() {
        stopPolling()
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, 3000)
    }

    private fun tryToReconnect() {
        Log.i(TAG, "正在尝试连接...")
        RCSDKManager.connectToRC()
    }

    private fun startPolling() {
        stopPolling()
        mainHandler.post(pollingRunnable)
    }

    private fun stopPolling() {
        mainHandler.removeCallbacks(pollingRunnable)
    }

    private fun getJoystickData() {
        KeyManager.get(RemoteControllerKey.KeyChannels, object : CompletionCallbackWith<IntArray> {
            override fun onSuccess(channels: IntArray?) {
                if (channels != null && channels.size >= 2) {
                    val ch0 = channels[0]
                    val ch1 = channels[1]
                    Log.d(TAG, "CH0: $ch0 | CH1: $ch1")
                    runOnUiThread {
                        if (ch0 > 1500) {
                            excavatorImage.rotation = -90f
                        } else if (ch0 < 1500) {
                            excavatorImage.rotation = 90f
                        } else if (ch1 < 1500) {
                            excavatorImage.rotation = -180f
                        } else {
                            excavatorImage.rotation = 0f
                        }
                    }
                }
                // 只要遥控器是连接状态，就继续下一次轮询
                if (connectionStatus == "遥控器已连接！") {
                    mainHandler.postDelayed(pollingRunnable, 100)
                }
            }

            override fun onFailure(e: SkyException?) {
                Log.e(TAG, "读取数据失败", e)
                // 即使读取失败，只要连接还在，就继续尝试
                if (connectionStatus == "遥控器已连接！") {
                    mainHandler.postDelayed(pollingRunnable, 100)
                }
            }
        })
    }
}
