package com.voicecommand.partner.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object PendingIntentFlags {
    fun forActivity(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun forService(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
