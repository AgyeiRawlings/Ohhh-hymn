package com.example.socketclient

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object JobSchedulerUtil {
    
    fun scheduleRestartJob(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val jobInfo = JobInfo.Builder(1, ComponentName(context, RestartJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setBackoffCriteria(1000, JobInfo.BACKOFF_POLICY_LINEAR)
            .build()

        jobScheduler.schedule(jobInfo)
    }
}
