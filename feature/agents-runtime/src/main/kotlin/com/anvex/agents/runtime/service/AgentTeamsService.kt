package com.anvex.agents.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * UNVERIFIED (never compiled). Keeps the process alive while agents run (Android may otherwise kill it). The work itself runs in
 * TeamRuntime's scope; if the process is killed anyway, runs are resumed from the event log (acceptance recovery).
 * The host manifest already declares FOREGROUND_SERVICE and FOREGROUND_SERVICE_DATA_SYNC (verified in 4.15.2).
 */
class AgentTeamsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Agent Teams", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Agent Teams").setContentText("Agents are working on your project").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIFICATION_ID, n)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "agent_teams"
        private const val NOTIFICATION_ID = 7421
        fun start(context: Context) { context.startForegroundService(Intent(context, AgentTeamsService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, AgentTeamsService::class.java)) }
    }
}
