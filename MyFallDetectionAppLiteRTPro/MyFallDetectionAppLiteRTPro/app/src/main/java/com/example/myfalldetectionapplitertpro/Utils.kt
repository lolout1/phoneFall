package com.example.myfalldetectionapplitertpro

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

object Utils {
    fun vibratePhone(context: Context, durationMs: Long) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }
}
