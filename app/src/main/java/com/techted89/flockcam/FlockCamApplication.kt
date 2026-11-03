package com.techted89.flockcam

import android.app.Application
import com.techted89.flockcam.data.ads.RewardedAdManager
import com.techted89.flockcam.data.auth.GoogleSignInHelper
import com.techted89.flockcam.data.billing.BillingManager
import com.techted89.flockcam.data.location.FlockLocationMonitor
import com.techted89.flockcam.data.remote.FlockApi
import com.techted89.flockcam.data.remote.FlockApiClient
import com.techted89.flockcam.data.repository.AuthRepository
import com.techted89.flockcam.data.repository.CameraRepository
import com.techted89.flockcam.data.repository.DirectionsRepository
import com.techted89.flockcam.data.session.SessionStore
import com.techted89.flockcam.remote.RemoteActionRepository

class FlockCamApplication : Application() {

    val locationMonitor: FlockLocationMonitor by lazy { FlockLocationMonitor(this) }

    val sessionStore: SessionStore by lazy { SessionStore(this) }

    val flockApi: FlockApi by lazy { FlockApiClient.create(this) }

    val authRepository: AuthRepository by lazy { AuthRepository(flockApi, sessionStore) }

    val cameraRepository: CameraRepository by lazy { CameraRepository(flockApi, sessionStore) }

    val directionsRepository: DirectionsRepository by lazy { DirectionsRepository() }

    val billingManager: BillingManager by lazy { BillingManager(this) }

    val rewardedAdManager: RewardedAdManager by lazy { RewardedAdManager(this) }

    val googleSignInHelper: GoogleSignInHelper by lazy { GoogleSignInHelper() }

    val remoteActionRepository: RemoteActionRepository by lazy { RemoteActionRepository(this, flockApi) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ENABLE_REMOTE_ACTION_RUNNER) {
            // WorkManager registers its default coroutine worker factory automatically,
            // so RemoteActionWorker can run as a standard CoroutineWorker.
        }
    }
}
