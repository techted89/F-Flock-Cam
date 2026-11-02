package com.techted89.flockcam.remote

sealed class RemoteAction {
    data class StartMonitoring(val source: String? = null) : RemoteAction()
    data class StopMonitoring(val source: String? = null) : RemoteAction()
    data class RefreshCatalog(val source: String? = null) : RemoteAction()
}

fun RemoteAction.toWireValue(): String = when (this) {
    is RemoteAction.StartMonitoring -> "START_MONITORING"
    is RemoteAction.StopMonitoring -> "STOP_MONITORING"
    is RemoteAction.RefreshCatalog -> "REFRESH_CATALOG"
}

fun String.toRemoteActionOrNull(): RemoteAction? = when (this.uppercase()) {
    "START_MONITORING" -> RemoteAction.StartMonitoring()
    "STOP_MONITORING" -> RemoteAction.StopMonitoring()
    "REFRESH_CATALOG" -> RemoteAction.RefreshCatalog()
    else -> null
}
