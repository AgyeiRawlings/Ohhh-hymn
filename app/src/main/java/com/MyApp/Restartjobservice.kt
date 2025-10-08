package com.example.socketclient

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build

class RestartJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        // Restart your SocketService here
        val intent = Intent(this, SocketService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        return false // no more work remaining
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return false
    }
}
