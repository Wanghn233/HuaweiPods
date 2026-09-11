package moe.chenxy.huaweipods.ui.components

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.HuaweiWindNoiseReductionController
import moe.chenxy.huaweipods.pods.supportsWindNoiseReduction
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WindNoiseReductionControl(address: String, route: HuaweiDeviceRoute) {
    if (!route.supportsWindNoiseReduction) return
    val context = LocalContext.current
    var enabled by remember(address, route) { mutableStateOf<Boolean?>(null) }
    var pending by remember(address, route) { mutableStateOf(false) }
    val device = remember(context, address) { context.windNoiseDevice(address) }

    LaunchedEffect(device, route) {
        device?.let {
            HuaweiWindNoiseReductionController.requestState(context, it, route) { value ->
                enabled = value
            }
        }
    }

    val toggle = {
        if (!pending && device != null) {
            val target = enabled != true
            pending = true
            HuaweiWindNoiseReductionController.setEnabled(
                context = context,
                device = device,
                route = route,
                enabled = target,
                onState = { value -> enabled = value },
                onComplete = { success ->
                    pending = false
                    if (!success) {
                        Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !pending && device != null, role = Role.Switch, onClick = toggle)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.wind_noise_reduction),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.headline1,
            )
            Text(
                text = stringResource(R.string.wind_noise_reduction_summary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        }
        Spacer(Modifier.width(12.dp))
        Checkbox(
            state = when (enabled) {
                true -> ToggleableState.On
                false -> ToggleableState.Off
                null -> ToggleableState.Indeterminate
            },
            enabled = !pending && device != null,
            onClick = toggle,
        )
    }
}

@SuppressLint("MissingPermission")
private fun Context.windNoiseDevice(address: String) =
    takeIf { BluetoothAdapter.checkBluetoothAddress(address) }
        ?.getSystemService(BluetoothManager::class.java)
        ?.adapter
        ?.getRemoteDevice(address)
