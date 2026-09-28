package moe.chenxy.huaweipods

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.config.LowLatencyPrefs
import moe.chenxy.huaweipods.pods.decodeHuaweiDeviceRouteFromBroadcast
import moe.chenxy.huaweipods.pods.supportsLowLatencyControl
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction

class LowLatencyStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_CHANGED ||
            !intent.getBooleanExtra(HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_WRITE_SUCCESS, false) ||
            !intent.hasExtra(HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_ENABLED)
        ) return

        val route = decodeHuaweiDeviceRouteFromBroadcast(
            intent.getStringExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE),
        )?.takeIf { it.supportsLowLatencyControl } ?: return
        val address = intent.getStringExtra("address") ?: return
        val enabled = intent.getBooleanExtra(HuaweiPodsAction.EXTRA_HUAWEI_LOW_LATENCY_ENABLED, false)
        val stored = LowLatencyPrefs.setDesired(
            prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE),
            service = HuaweiPodsApp.xposedService,
            address = address,
            route = route,
            enabled = enabled,
        )
        if (!stored) Log.w(TAG, "Unable to save low-latency policy for $address")
    }

    private companion object {
        const val TAG = "HuaweiPods-LowLatency"
    }
}
