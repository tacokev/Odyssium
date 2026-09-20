package com.skintclub.odyssey.utils

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Returns the name of the currently connected Bluetooth audio device, or null if none.
 * Only returns the name if Bluetooth is the active audio output.
 */
fun getConnectedBluetoothDeviceName(context: Context): String? {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Suppress("DEPRECATION")
    val isBluetoothActive = audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
    if (!isBluetoothActive) return null

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val activeBluetoothDevice =
            audioDevices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                ?: audioDevices.find { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        return activeBluetoothDevice?.productName?.toString()
    }
    return null
}
