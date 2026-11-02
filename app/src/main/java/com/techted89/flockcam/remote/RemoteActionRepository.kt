package com.techted89.flockcam.remote

import android.content.Context
import com.techted89.flockcam.data.model.ActionAck
import com.techted89.flockcam.data.model.ActionResponse
import com.techted89.flockcam.data.model.EnqueueActionRequest
import com.techted89.flockcam.data.model.PendingAction
import com.techted89.flockcam.data.remote.FlockApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteActionRepository(
    private val context: Context,
    private val api: FlockApi,
) {
    private val _pendingActions = MutableStateFlow<List<PendingAction>>(emptyList())
    val pendingActions: StateFlow<List<PendingAction>> = _pendingActions.asStateFlow()

    suspend fun getPendingActions(deviceId: String): List<PendingAction> {
        val response = api.getPendingActions(deviceId)
        if (response.isSuccessful) {
            val actions = response.body()?.actions ?: emptyList()
            _pendingActions.value = actions
            return actions
        }
        return emptyList()
    }

    suspend fun enqueueAction(deviceId: String, action: String, source: String?): ActionResponse {
        val request = EnqueueActionRequest(
            deviceId = deviceId,
            action = action,
            source = source,
        )
        val response = api.enqueueAction(request)
        return if (response.isSuccessful) {
            response.body() ?: ActionAck(status = "QUEUED", actionId = null)
        } else {
            ActionAck(status = "FAILED", actionId = null)
        }
    }

    fun setPendingActions(actions: List<PendingAction>) {
        _pendingActions.value = actions
    }
}
