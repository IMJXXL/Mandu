package com.mangareader.backup.scheduled

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object ScheduledBackupScheduler {
    private const val WORK_NAME = "scheduled_backup"

    fun schedule(context: Context, config: ScheduledBackupConfig) {
        if (!config.enabled) {
            cancel(context)
            return
        }
        val period = when (config.frequency) {
            BackupFrequency.DAILY -> 24L
            BackupFrequency.WEEKLY -> 168L
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(false)
            .build()
        val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(period, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    fun triggerNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScheduledBackupWorker>()
            .setInputData(workDataOf("manual" to true))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("${WORK_NAME}_now", ExistingWorkPolicy.REPLACE, request)
    }

    fun isScheduled(context: Context): Boolean {
        return ScheduledBackupConfigStore.load(context).enabled
    }
}