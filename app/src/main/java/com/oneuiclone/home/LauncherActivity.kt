package com.oneuiclone.home

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import com.oneuiclone.home.debug.AnimLog

class LauncherActivity : Activity() {

    companion object {
        const val REQUEST_CONTACTS = 4202
    }

    lateinit var launcher: Launcher
        private set
    private var resumedOnce = false
    private var setupChecked = false
    private var lastUiMode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnimLog.init(this)
        AppUpdate.init(this)
        AnimLog.log("activity created")
        window.setDecorFitsSystemWindows(false)
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        lastUiMode = resources.configuration.uiMode
        launcher = Launcher(this)
        setContentView(launcher.root)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            launcher.onBack()
        }
    }

    override fun onStart() {
        super.onStart()
        AnimLog.log("onStart")
        launcher.background.attach()
        launcher.widgets.start()
        launcher.onStart()
    }

    override fun onResume() {
        super.onResume()
        AnimLog.log("onResume")
        resumedOnce = true
        launcher.onResume()
        window.decorView.post { showSetupIfDue() }
    }

    override fun onPause() {
        super.onPause()
        AnimLog.log("onPause")
        launcher.onPause()
    }

    override fun onStop() {
        super.onStop()
        AnimLog.log("onStop")
        launcher.background.detach()
        launcher.onStop()
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        AnimLog.log("onEnterAnimationComplete")
        launcher.closeFinished()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already showing home → collapse states (One UI behaviour).
        val alreadyOnHome = hasWindowFocus() && isResumedCompat()
        AnimLog.log("home intent alreadyOnHome=$alreadyOnHome contract=${intent.hasExtra(GestureNavContract.EXTRA_GESTURE_CONTRACT)}")
        launcher.onNewIntent(intent, alreadyOnHome)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        AnimLog.log("window focus=$hasFocus")
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        AnimLog.log("top resumed=$isTopResumedActivity")
    }

    /**
     * First Home ever (or the first since setup was added): permissions and the wallpaper, once; or setup back in
     * front after it made us Home. Waits for Home's own first-run widget dialog, which would otherwise cover it.
     */
    fun showSetupIfDue() {
        if (isFinishing || !isResumedCompat() || !launcher.readyForSetup) return
        com.oneuiclone.home.settings.SetupActivity.resumeAfterHome(this)
        if (!setupChecked) {
            setupChecked = true
            com.oneuiclone.home.settings.SetupActivity.openOnce(this)
        }
    }

    private fun isResumedCompat(): Boolean = window.decorView.isAttachedToWindow && resumedOnce

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if ((newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) != (lastUiMode and Configuration.UI_MODE_NIGHT_MASK)) {
            launcher.onUiModeChanged()
        }
        lastUiMode = newConfig.uiMode
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        launcher.widgets.onActivityResult(requestCode, resultCode)
        showSetupIfDue()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CONTACTS) {
            launcher.onContactsPermission(grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        if (requestCode == AppBubbles.REQUEST_NOTIFICATIONS) {
            AppBubbles.onPermissionResult(this, grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    override fun onDestroy() {
        launcher.widgets.stop()
        launcher.destroy()
        super.onDestroy()
    }
}
