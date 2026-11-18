package io.github.juwanhwang.autoqqpet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import io.github.juwanhwang.autoqqpet.ModulePrefs
import io.github.juwanhwang.autoqqpet.ui.data.LiveState
import io.github.juwanhwang.autoqqpet.ui.theme.UiPrefs

/**
 * 唯一的 Activity。界面全部是 Compose（见 [AevumApp]）；这里只管生命周期：
 * 可见时开始收心跳与每秒刷新，不可见时全部停掉。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 模块 App 自己的偏好文件（[EulaTerms] 也用它存同意指纹）。 */
        const val PREFS = ModulePrefs.NAME
        const val KEY_NOTICE_SEEN = "notice_seen"
    }

    private lateinit var live: LiveState
    private lateinit var gate: AgreementGate
    private val navigator = Navigator()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        UiPrefs.init(this)
        live = LiveState(this)
        gate = AgreementGate(this) { ensureNotifPermission() }
        setContent {
            AevumApp(live = live, navigator = navigator, gate = gate, onExit = { finishAffinity() })
        }
    }

    override fun onStart() {
        super.onStart()
        live.start()
        live.refresh()
        live.bumpData()   // 从系统设置页回来时，电池/闹钟等状态型摘要要当场重读（同“名单摘要不刷新”一类）
        gate.recheck()
    }

    override fun onResume() {
        super.onResume()
        if (gate.agreed) ensureNotifPermission()
    }

    override fun onStop() {
        super.onStop()
        live.stop()
    }

    private fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !asked
        ) {
            asked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private var asked = false
}
