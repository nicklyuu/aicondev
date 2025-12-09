package com.example.aicondev

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.skydroid.rcsdk.KeyManager
import com.skydroid.rcsdk.RCSDKManager
import com.skydroid.rcsdk.SDKManagerCallBack
import com.skydroid.rcsdk.common.callback.CompletionCallbackWith
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.key.RemoteControllerKey

class MainActivity : AppCompatActivity() {

    private lateinit var squareView: ImageView
    private lateinit var debugText: TextView

    // ✅ 使用 Handler 来实现更稳定、高效的链式轮询
    private val pollingHandler = Handler(Looper.getMainLooper())
    private lateinit var pollingRunnable: Runnable

    private var connectionStatus: String = "等待权限..."

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                updateText("权限已授予，正在初始化...")
                initRCSDK()
            } else {
                updateText("权限被拒绝，SDK无法启动！")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        squareView = findViewById(R.id.square_view)
        debugText = findViewById(R.id.tv_debug_info)
        updateText(connectionStatus)

        // ✅ 定义轮询的具体任务
        pollingRunnable = Runnable { getJoystickData() }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            updateText("权限已就绪，正在初始化...")
            initRCSDK()
        } else {
            updateText("需要权限以连接硬件...")
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }


    private fun initRCSDK() {
        Log.d("MainActivity", "--- PREPARING TO INITIALIZE SDK ---")
        RCSDKManager.initSDK(this, object : SDKManagerCallBack {
            override fun onRcConnected() {
                Log.d("MainActivity", "--- SDK CALLBACK: onRcConnected ---")
                connectionStatus = "遥控器已连接！"
                runOnUiThread { updateText(connectionStatus) }
                startPolling()
            }

            override fun onRcConnectFail(e: SkyException?) {
                Log.e("MainActivity", "--- SDK CALLBACK: onRcConnectFail: ${e?.message} ---")
                connectionStatus = "连接失败: ${e?.message}"
                runOnUiThread { updateText(connectionStatus) }
            }

            override fun onRcDisconnect() {
                Log.w("MainActivity", "--- SDK CALLBACK: onRcDisconnect ---")
                connectionStatus = "遥控器断开"
                runOnUiThread { updateText(connectionStatus) }
                stopPolling()
            }
        })
        Log.d("MainActivity", "--- SDK INITIALIZATION METHOD CALLED ---")

        Log.d("MainActivity", "--- MANUALLY CALLING connectToRC() ---")
        RCSDKManager.connectToRC()
    }
    

    override fun onDestroy() {
        super.onDestroy()
        RCSDKManager.disconnectRC()
        stopPolling()
    }

    private fun startPolling() {
        // 先停止任何可能正在运行的旧任务，确保安全
        stopPolling()
        // 立即开始第一次轮询
        pollingHandler.post(pollingRunnable)
    }

    private fun stopPolling() {
        // 从 Handler 的消息队列中移除所有未执行的轮询任务
        pollingHandler.removeCallbacks(pollingRunnable)
    }

    private fun getJoystickData() {
        KeyManager.get(RemoteControllerKey.KeyChannels, object : CompletionCallbackWith<IntArray> {
            override fun onSuccess(channels: IntArray?) {
                if (channels != null && channels.size >= 4) {
                    val ch0 = channels[0]
                    val ch2 = channels[2] // ✅ 将 ch1(俯仰) 更换为 ch2
                    val ch4 = if (channels.size > 4) channels[4] else 1500
                    runOnUiThread {
                        moveSquare(ch0, ch2) // ✅ 将 ch2 传给 moveSquare 方法
                        checkButton(ch4)
                        val newText = "$connectionStatus\nCH0: $ch0 | CH2: $ch2" // ✅ 更新调试文本
                        updateText(newText)
                    }
                }
                // ✅ 关键：当本次请求成功后，预约下一次请求在 100ms 后执行
                pollingHandler.postDelayed(pollingRunnable, 100)
            }

            override fun onFailure(e: SkyException?) {
                Log.e("RCSDK", "读取通道数据失败: $e")
                runOnUiThread {
                    val newText = "$connectionStatus\n读取数据失败"
                    updateText(newText)
                }
                // ✅ 关键：即使失败了，也要预约下一次请求，以确保轮询不会中断
                pollingHandler.postDelayed(pollingRunnable, 100)
            }
        })
    }

    private fun moveSquare(roll: Int, pitch: Int) {
        //val deadzone = 20
        val speedFactor = 0.05f

        val deltaX = roll - 1500
        val deltaY = 1500 - pitch

        //if (Math.abs(roll - 1500) > deadzone) {
        squareView.translationX += deltaX * speedFactor
        //}

        //if (Math.abs(pitch - 1500) > deadzone) {
        squareView.translationY += deltaY * speedFactor
        //}
    }

    private fun checkButton(value: Int) {
        //val newColor = if (value > 1500) Color.BLUE else Color.RED
        //squareView.setBackgroundColor(newColor)
    }

    private fun updateText(str: String) {
        if (debugText.text != str) {
            debugText.text = str
        }
    }
}
