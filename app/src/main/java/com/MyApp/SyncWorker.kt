package com.example.myapp.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // Implement sync or background work here
        return Result.success()
    }
}
