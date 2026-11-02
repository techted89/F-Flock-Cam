package com.techted89.flockcam.remote

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.techted89.flockcam.data.model.ActionAck
import com.techted89.flockcam.data.model.ActionResponse
import com.techted89.flockcam.data.model.EnqueueActionRequest
import com.techted89.flockcam.data.model.PendingAction
import com.techted89.flockcam.data.remote.FlockApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RemoteActionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun doWork(): Result {
        val repository = (applicationContext as com.techted89.flockcam.FlockCamApplication).remoteActionRepository
        val deviceId = params.inputData.getString(KEY_DEVICE_ID) ?: DEFAULT_DEVICE_ID
        val pending = repository.getPendingActions(deviceId)

        if (pending.isNotEmpty()) {
            for (action in pending) {
                when (action.action.uppercase()) {
                    ACTION_START_MONITORING -> FlockMonitoringService.start(
                        applicationContext,
                        ACTION_START_MONITORING,
                    )
                    ACTION_STOP_MONITORING -> FlockMonitoringService.stop(applicationContext)
                    ACTION_REFRESH_CATALOG -> Unit
                }
            }
            repository.setPendingActions(emptyList())
        }

        return Result.success()
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val DEFAULT_DEVICE_ID = "anonymous-device"

        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_REFRESH_CATALOG = "REFRESH_CATALOG"
    }
}
