package com.techted89.flockcam

import android.app.Application
import com.techted89.flockcam.data.location.FlockLocationMonitor
import com.techted89.flockcam.data.remote.FlockApi
import com.techted89.flockcam.data.remote.FlockApiClient
import com.techted89.flockcam.remote.RemoteActionRepository

class FlockCamApplication : Application() {

    val locationMonitor: FlockLocationMonitor by lazy { FlockLocationMonitor(this) }

    val flockApi: FlockApi by lazy { FlockApiClient.create(this) }

    val remoteActionRepository: RemoteActionRepository by lazy { RemoteActionRepository(this, flockApi) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ENABLE_REMOTE_ACTION_RUNNER) {
            // WorkManager registers its default coroutine worker factory automatically,
            // so RemoteActionWorker can run as a standard CoroutineWorker.
        }
    }
}
