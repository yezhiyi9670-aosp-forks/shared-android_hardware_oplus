/*
 * Copyright (C) 2021-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioSystem
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler
import java.io.File
import java.util.concurrent.Executors

class KeyHandler(private val context: Context) : DeviceKeyHandler {
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val cameraManager = context.getSystemService(CameraManager::class.java)!!
    private val notificationManager = context.getSystemService(NotificationManager::class.java)!!
    private val vibrator = context.getSystemService(Vibrator::class.java)!!

    private val packageContext =
        context.createPackageContext(KeyHandler::class.java.getPackage()!!.name, 0)
    private val sharedPreferences
        get() =
            packageContext.getSharedPreferences(
                packageContext.packageName + "_preferences",
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS,
            )

    private val executorService = Executors.newSingleThreadExecutor()

    private var isInMuteSession = false
    private var isMuteSessionBroken = false
    private var preMuteMediaVolume = 0
    private var wasUsingSpeaker = false

    private fun isSpeakerCurrentMediaOutput(): Boolean {
        return AudioSystem.getDevicesForStream(AudioSystem.STREAM_MUSIC) ==
            AudioSystem.DEVICE_OUT_SPEAKER
    }
    private fun getSpeakerMediaVolume(): Int {
        return AudioSystem.getStreamVolumeIndex(
            AudioSystem.STREAM_MUSIC, AudioSystem.DEVICE_OUT_SPEAKER
        )
    }
    private fun setSpeakerMediaVolume(volume: Int) {
        AudioSystem.setStreamVolumeIndexAS(
            AudioSystem.STREAM_MUSIC, volume, false, AudioSystem.DEVICE_OUT_SPEAKER
        )
    }
    private fun muteMediaStream() {
        audioManager.adjustVolume(AudioManager.ADJUST_MUTE, 0)
    }
    private fun unmuteMediaStream() {
        audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
    }

    // The adjustVolume "stream mute" is per-stream and not per-device.
    // Hence during a mute session,
    // we use stream mute when current device is speaker (restore speaker media volume if needed),
    // so that the user can unmute and restore to original volume by clicking Volume Up once.
    // Otherwise always make the speaker media volume 0, and do not mute the stream to avoid affecting external devices.
    // If any sign of manual unmuting found, consider mute session broken and do not further interfere with muting.
    private fun enterMuteSession() {
        if (isInMuteSession) {
            return
        }
        isInMuteSession = true
        isMuteSessionBroken = false
        preMuteMediaVolume = getSpeakerMediaVolume()
        wasUsingSpeaker = isSpeakerCurrentMediaOutput()
        if (wasUsingSpeaker) {
            muteMediaStream()
        } else {
            setSpeakerMediaVolume(0)
        }
    }
    private fun tryRestoreMediaVolume() {
        val mediaVolume = getSpeakerMediaVolume()
        if (mediaVolume == 0 || mediaVolume == preMuteMediaVolume) {
            // Don't know if getSpeakerMediaVolume() returns media volume masked by mute (0) or unmasked (preMuteMediaVolume)
            setSpeakerMediaVolume(preMuteMediaVolume)
        } else {
            preMuteMediaVolume = mediaVolume
            isMuteSessionBroken = true
        }
    }
    private fun exitMuteSession() {
        if (!isInMuteSession) {
            return
        }
        isInMuteSession = false
        if (isMuteSessionBroken) {
            return
        }
        if (wasUsingSpeaker) {
            unmuteMediaStream()
        } else {
            tryRestoreMediaVolume()
        }
    }
    private fun onMediaStreamUnmuted() {
        val isUsingSpeaker = isSpeakerCurrentMediaOutput()
        if (!isInMuteSession) {
            return
        }
        if (wasUsingSpeaker && isUsingSpeaker) {
            isMuteSessionBroken = true
        }
    }
    private fun onMediaStreamDeviceChanged() {
        if (!isInMuteSession) {
            return
        }
        val isUsingSpeaker = isSpeakerCurrentMediaOutput()
        if (!wasUsingSpeaker && isUsingSpeaker && !isMuteSessionBroken) {
            tryRestoreMediaVolume()
            muteMediaStream()
        }
        if (wasUsingSpeaker && !isUsingSpeaker && !isMuteSessionBroken) {
            unmuteMediaStream()
            setSpeakerMediaVolume(0)
        }
        wasUsingSpeaker = isUsingSpeaker
    }

    private fun setTorchState(state: Boolean) {
        val cameraId =
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager
                    .getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        if (cameraId != null) {
            cameraManager.setTorchMode(cameraId, state)
        }
    }

    private val broadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AudioManager.STREAM_MUTE_CHANGED_ACTION -> {
                        val stream = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
                        val state =
                            intent.getBooleanExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, false)
                        if (stream == AudioSystem.STREAM_MUSIC && !state) {
                            executorService.submit { onMediaStreamUnmuted() }
                        }
                    }

                    AudioManager.STREAM_DEVICES_CHANGED_ACTION -> {
                        val stream = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
                        if (stream == AudioSystem.STREAM_MUSIC) {
                            executorService.submit { onMediaStreamDeviceChanged() }
                        }
                    }

                    Intent.ACTION_BOOT_COMPLETED -> populateKeyState(true)
                }
            }
        }

    init {
        context.registerReceiver(
            broadcastReceiver,
            IntentFilter().apply {
                addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION)
                addAction(AudioManager.STREAM_DEVICES_CHANGED_ACTION)
                addAction(Intent.ACTION_BOOT_COMPLETED)
            },
        )
    }

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return event
        }

        val deviceName = event.device.name

        if (deviceName != "oplus,hall_tri_state_key" && deviceName != "oplus,tri-state-key") {
            return event
        }

        populateKeyState(false)

        return null
    }

    private fun populateKeyState(firstRun: Boolean) {
        when (File("/proc/tristatekey/tri_state").readText().trim()) {
            "1" -> handleMode(POSITION_TOP, firstRun)
            "2" -> handleMode(POSITION_MIDDLE, firstRun)
            "3" -> handleMode(POSITION_BOTTOM, firstRun)
        }
    }

    private fun vibrateIfNeeded(mode: Int) {
        when (mode) {
            AudioManager.RINGER_MODE_VIBRATE ->
                vibrator.vibrate(MODE_VIBRATION_EFFECT, HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES)
            AudioManager.RINGER_MODE_NORMAL ->
                vibrator.vibrate(MODE_NORMAL_EFFECT, HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES)
        }
    }

    private fun getModeForPosition(position: Int): Int? {
        return when (position) {
            POSITION_TOP -> sharedPreferences.getString(ALERT_SLIDER_TOP_KEY, "0")!!.toInt()
            POSITION_MIDDLE ->
                sharedPreferences.getString(ALERT_SLIDER_MIDDLE_KEY, "1")!!.toInt()
            POSITION_BOTTOM ->
                sharedPreferences.getString(ALERT_SLIDER_BOTTOM_KEY, "2")!!.toInt()
            else -> return null
        }
    }

    private fun handleMode(position: Int, firstRun: Boolean) {
        val muteMedia = sharedPreferences.getBoolean(MUTE_MEDIA_WITH_SILENT, false)
        val showDialog = sharedPreferences.getBoolean(SHOW_DIALOG, true)

        val mode = getModeForPosition(position)
        if (mode == null) {
            return
        }

        var zenModesInvolved = false
        var torchInvolved = false
        for (otherPosition in listOf(POSITION_TOP, POSITION_MIDDLE, POSITION_BOTTOM)) {
            when (getModeForPosition(otherPosition)) {
                ZEN_PRIORITY_ONLY,
                ZEN_TOTAL_SILENCE,
                ZEN_ALARMS_ONLY -> {
                    zenModesInvolved = true
                    break
                }
                TORCH_OFF,
                TORCH_ON -> {
                    torchInvolved = true
                    break
                }
                else -> { }
            }
        }

        executorService.submit {
            when (mode) {
                AudioManager.RINGER_MODE_SILENT -> {
                    if (zenModesInvolved) {
                        setZenMode(Settings.Global.ZEN_MODE_OFF)
                    }
                    if (torchInvolved) {
                        setTorchState(false)
                    }
                    audioManager.ringerModeInternal = mode
                    if (muteMedia) {
                        enterMuteSession()
                    }
                }
                AudioManager.RINGER_MODE_VIBRATE,
                AudioManager.RINGER_MODE_NORMAL -> {
                    if (zenModesInvolved) {
                        setZenMode(Settings.Global.ZEN_MODE_OFF)
                    }
                    if (torchInvolved) {
                        setTorchState(false)
                    }
                    audioManager.ringerModeInternal = mode
                    exitMuteSession()
                }
                ZEN_PRIORITY_ONLY,
                ZEN_TOTAL_SILENCE,
                ZEN_ALARMS_ONLY -> {
                    if (torchInvolved) {
                        setTorchState(false)
                    }
                    audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                    setZenMode(mode - ZEN_OFFSET)
                    exitMuteSession()
                }
                TORCH_ON,
                TORCH_OFF -> {
                    setTorchState(mode == TORCH_ON)
                }
            }

            if (!firstRun) {
                if (showDialog) sendNotification(position, mode)
                vibrateIfNeeded(mode)
            }
        }
    }

    private fun setZenMode(zenMode: Int) {
        // Set zen mode
        notificationManager.setZenMode(zenMode, null, TAG)

        // Wait until zen mode change is committed
        while (notificationManager.zenMode != zenMode) {
            Thread.sleep(10)
        }
    }

    private fun sendNotification(position: Int, mode: Int) {
        context.sendBroadcast(
            Intent(CHANGED_ACTION).apply {
                putExtra("position", position)
                putExtra("mode", mode)
            }
        )
    }

    companion object {
        private const val TAG = "KeyHandler"

        // Intent actions
        const val CHANGED_ACTION = "org.lineageos.settings.UPDATE_SETTINGS"

        // Slider key positions
        const val POSITION_TOP = 1
        const val POSITION_MIDDLE = 2
        const val POSITION_BOTTOM = 3

        // Preference keys
        private const val ALERT_SLIDER_TOP_KEY = "config_top_position"
        private const val ALERT_SLIDER_MIDDLE_KEY = "config_middle_position"
        private const val ALERT_SLIDER_BOTTOM_KEY = "config_bottom_position"
        private const val MUTE_MEDIA_WITH_SILENT = "config_mute_media"
        private const val SHOW_DIALOG = "config_show_dialog"

        // ZEN constants
        private const val ZEN_OFFSET = 2
        const val ZEN_PRIORITY_ONLY = 3
        const val ZEN_TOTAL_SILENCE = 4
        const val ZEN_ALARMS_ONLY = 5

        // Torch constants
        private const val TORCH_OFFSET = 8
        const val TORCH_ON = TORCH_OFFSET + 0
        const val TORCH_OFF = TORCH_OFFSET + 1

        // Vibration attributes
        private val HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)

        // Vibration effects
        private val MODE_NORMAL_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK)
        private val MODE_VIBRATION_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }
}
