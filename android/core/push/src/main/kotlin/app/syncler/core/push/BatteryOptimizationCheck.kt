package app.syncler.core.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helper used by the host app to detect (and direct the user to disable)
 * battery optimization. Required on Samsung / OnePlus / many OEMs for
 * reliable background FCM + foreground-service delivery.
 *
 * Usage in the app:
 *   if (!BatteryOptimizationCheck.isIgnoring(context)) {
 *       startActivity(BatteryOptimizationCheck.requestIgnoreIntent(context))
 *   }
 */
object BatteryOptimizationCheck {

    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
