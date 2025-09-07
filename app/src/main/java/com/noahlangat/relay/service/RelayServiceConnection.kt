package com.noahlangat.relay.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Manages connection to the RelayService for UI components
 */
class RelayServiceConnection(private val context: Context) : ServiceConnection {

    private var relayService: RelayService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _serviceState = MutableStateFlow<RelayService.ServiceState?>(null)
    val serviceState: StateFlow<RelayService.ServiceState?> = _serviceState

    private val _serviceStats = MutableStateFlow<RelayService.ServiceStats?>(null)
    val serviceStats: StateFlow<RelayService.ServiceStats?> = _serviceStats

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as? RelayService.RelayServiceBinder
        val srv = binder?.getService()
        relayService = srv

        if (srv != null) {
            _isConnected.value = true
            Timber.i("Connected to RelayService")

            // Collect service state
            scope.launch {
                srv.serviceState.collect { state ->
                    _serviceState.value = state
                }
            }

            // Collect service stats
            scope.launch {
                srv.serviceStats.collect { s ->
                    _serviceStats.value = s
                }
            }
        } else {
            Timber.e("Failed to get RelayService from binder")
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Timber.i("Disconnected from RelayService")
        scope.coroutineContext.cancelChildren()
        relayService = null
        _isConnected.value = false
        _serviceState.value = null
        _serviceStats.value = null
    }

    /** Bind to the RelayService */
    fun bind(): Boolean {
        val intent = Intent(context, RelayService::class.java)
        return try {
            context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Timber.e(e, "Failed to bind to RelayService")
            false
        }
    }

    /** Unbind from the RelayService */
    fun unbind() {
        try {
            if (_isConnected.value) context.unbindService(this)
        } catch (e: Exception) {
            Timber.w(e, "Error unbinding from RelayService")
        }

        scope.coroutineContext.cancelChildren()
        relayService = null
        _isConnected.value = false
        _serviceState.value = null
        _serviceStats.value = null
    }

    /** Start the relay service */
    fun startService() {
        val intent = Intent(context, RelayService::class.java).apply {
            action = RelayService.ACTION_START_RELAY
        }
        try {
            context.startForegroundService(intent)
            Timber.i("Started RelayService")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start RelayService")
        }
    }

    /** Stop the relay service */
    fun stopService() {
        val intent = Intent(context, RelayService::class.java).apply {
            action = RelayService.ACTION_STOP_RELAY
        }
        try {
            context.startService(intent)
            Timber.i("Stopped RelayService")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop RelayService")
        }
    }

    /** Get current service instance (if connected) */
    fun getService(): RelayService? = relayService

    /** Check if currently connected to service */
    fun isServiceConnected(): Boolean = _isConnected.value && relayService != null

    companion object {
        private const val TAG = "RelayServiceConnection"
    }
}
