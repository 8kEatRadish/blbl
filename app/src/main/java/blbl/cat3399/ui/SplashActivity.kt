package blbl.cat3399.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import blbl.cat3399.R
import blbl.cat3399.core.ui.BaseActivity

open class SplashActivity : BaseActivity() {
    private var forwarded = false
    private var splashAnimStarted = false
    private var splashStartAtMs: Long = 0L
    private var logoView: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        splashStartAtMs = SystemClock.uptimeMillis()
        logoView = findViewById(R.id.iv_splash_logo)
        logoView?.apply {
            alpha = 0f
            scaleX = 0.84f
            scaleY = 0.84f
            translationY = resources.displayMetrics.density * 10f
        }
    }

    override fun onResume() {
        super.onResume()
        playSplashMotionAndForward()
    }

    override fun shouldRecreateOnUiScaleChange(): Boolean = false

    override fun shouldApplyThemePreset(): Boolean = false

    private fun playSplashMotionAndForward() {
        if (splashAnimStarted) return
        splashAnimStarted = true
        logoView?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.translationY(0f)
            ?.setDuration(320L)
            ?.setInterpolator(OvershootInterpolator(0.62f))
            ?.withEndAction {
                logoView?.animate()
                    ?.scaleX(0.965f)
                    ?.scaleY(0.965f)
                    ?.setDuration(150L)
                    ?.setInterpolator(DecelerateInterpolator(1.3f))
                    ?.start()
            }
            ?.start()

        val elapsed = (SystemClock.uptimeMillis() - splashStartAtMs).coerceAtLeast(0L)
        val delay = (SPLASH_MIN_VISIBLE_MS - elapsed).coerceAtLeast(120L)
        window?.decorView?.postDelayed({ forwardToMainIfNeeded() }, delay)
    }

    private fun forwardToMainIfNeeded() {
        if (forwarded) return
        forwarded = true
        window?.decorView?.post {
            if (isFinishing || isDestroyed) return@post
            startActivity(
                Intent(this, MainActivity::class.java),
            )
            finish()
            applyOpenTransitionAnimation()
        }
    }

    private fun applyOpenTransitionAnimation() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, R.anim.app_open_main_enter, R.anim.app_open_splash_exit)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.app_open_main_enter, R.anim.app_open_splash_exit)
        }
    }

    companion object {
        private const val SPLASH_MIN_VISIBLE_MS = 520L
    }
}
