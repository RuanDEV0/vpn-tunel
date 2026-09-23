package br.com.ruan.splitvpn.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import br.com.ruan.splitvpn.data.SettingsRepository
import br.com.ruan.splitvpn.domain.*
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class VpnController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    private val configFactory: WireGuardConfigFactory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "splitvpn"
        override fun onStateChange(newState: Tunnel.State) = Unit
    }
    @Volatile private var desired = false
    @Volatile private var restarting = false
    @Volatile private var networkDown = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private val mutableStatus = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = mutableStatus

    fun connect() { scope.launch { connectLocked() } }
    private suspend fun connectLocked() = mutex.withLock {
        try {
            val settings = repository.settings.first()
            SettingsValidator.validate(settings, ::isInstalled)
            if (VpnService.prepare(context) != null) throw ConfigurationException("Autorize a VPN novamente no aplicativo")
            desired = true
            mutableStatus.value = VpnStatus(ConnectionPhase.CONNECTING, settings.packageName, message = "Conectando…")
            val config = configFactory.create(settings, repository.privateKey(), repository.presharedKey())
            // O GoBackend encerra o VpnService ao desligar a TUN. Espere a destruição
            // antes de registrar o novo serviço, evitando que ele inicie o serviço da AAR.
            if (backend?.getState(tunnel) == Tunnel.State.UP) {
                restarting = true
                try {
                    backend?.setState(tunnel, Tunnel.State.DOWN, null)
                    withTimeout(5000) { while (SplitVpnService.instance != null) delay(50) }
                } finally { restarting = false }
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(Intent(context, SplitVpnService::class.java))
            else context.startService(Intent(context, SplitVpnService::class.java))
            withTimeout(5000) { while (SplitVpnService.instance == null) delay(50) }
            val engine = backend ?: GoBackend(context).also { backend = it }
            engine.setState(tunnel, Tunnel.State.UP, config)
            registerNetworkCallback()
            mutableStatus.value = VpnStatus(ConnectionPhase.CONNECTED, settings.packageName, message = "Túnel ativo; aguardando tráfego")
            startStats()
        } catch (e: Exception) {
            desired = false
            stopStats()
            unregisterNetworkCallback()
            val message = when (e) {
                is ConfigurationException -> e.message ?: "Configuração inválida"
                is TimeoutCancellationException -> "Tempo limite ao iniciar o serviço VPN"
                is BackendException -> when (e.reason) {
                    BackendException.Reason.DNS_RESOLUTION_FAILURE -> "Falha ao resolver DNS do endpoint"
                    BackendException.Reason.VPN_NOT_AUTHORIZED -> "Autorização VPN revogada"
                    BackendException.Reason.TUN_CREATION_ERROR -> "Falha ao criar a interface VPN"
                    BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> "Falha ao ativar o motor WireGuard"
                    else -> "Falha ao iniciar o túnel WireGuard"
                }
                else -> "Falha ao conectar WireGuard (${e.javaClass.simpleName})"
            }
            mutableStatus.value = VpnStatus(ConnectionPhase.ERROR, message = message)
            SplitVpnService.instance?.stopSelf()
        }
    }
    fun disconnect() { scope.launch { disconnectLocked() } }
    private suspend fun disconnectLocked() = mutex.withLock {
        desired = false
        reconnectJob?.cancel()
        stopStats()
        unregisterNetworkCallback()
        try { backend?.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) { }
        mutableStatus.value = VpnStatus()
        SplitVpnService.instance?.stopSelf()
    }
    fun onRevoked() {
        desired = false
        scope.launch {
            mutex.withLock {
                stopStats()
                unregisterNetworkCallback()
                try { backend?.setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) { }
                mutableStatus.value = VpnStatus(ConnectionPhase.ERROR, message = "Permissão VPN revogada pelo sistema")
            }
        }
    }
    fun onServiceDestroyed() {
        if (desired && !restarting) {
            desired = false
            mutableStatus.value = VpnStatus(ConnectionPhase.ERROR, message = "Serviço VPN encerrado pelo sistema")
        }
    }
    private fun isInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        else context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { networkDown = true; scheduleReconnect("Rede móvel ou Wi-Fi indisponível") }
            override fun onAvailable(network: Network) { if (networkDown) { networkDown = false; scheduleReconnect("Rede disponível; reconectando") } }
        }
        connectivity.registerNetworkCallback(request, callback)
        networkCallback = callback
    }
    private fun unregisterNetworkCallback() {
        networkCallback?.let { connectivity.unregisterNetworkCallback(it) }
        networkCallback = null
    }
    private fun scheduleReconnect(message: String) {
        if (!desired) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            mutableStatus.value = mutableStatus.value.copy(phase = ConnectionPhase.CONNECTING, message = message)
            delay(1500)
            if (!desired) return@launch
            val available = connectivity.allNetworks.any { network ->
                connectivity.getNetworkCapabilities(network)?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                } == true
            }
            if (available) connectLocked()
        }
    }
    private fun startStats() {
        stopStats()
        statsJob = scope.launch {
            var firstOutboundAt = 0L
            while (isActive && desired) {
                delay(1000)
                try {
                    val stats = backend?.getStatistics(tunnel) ?: continue
                    val rx = stats.totalRx()
                    val tx = stats.totalTx()
                    val handshake = stats.peers().any { (stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L) > 0L }
                    if (tx > 0 && !handshake) {
                        if (firstOutboundAt == 0L) firstOutboundAt = System.currentTimeMillis()
                        if (System.currentTimeMillis() - firstOutboundAt > 15_000) {
                            mutableStatus.value = mutableStatus.value.copy(
                                phase = ConnectionPhase.ERROR, txBytes = tx, rxBytes = rx,
                                message = "Timeout do handshake WireGuard; confira servidor e chaves")
                            continue
                        }
                    }
                    if (mutableStatus.value.phase != ConnectionPhase.ERROR || handshake)
                        mutableStatus.value = mutableStatus.value.copy(
                            phase = ConnectionPhase.CONNECTED, txBytes = tx, rxBytes = rx,
                            message = if (handshake) "Handshake confirmado" else "Túnel ativo; aguardando tráfego")
                } catch (_: Exception) {
                    mutableStatus.value = mutableStatus.value.copy(phase = ConnectionPhase.ERROR, message = "Falha ao ler estatísticas do túnel")
                }
            }
        }
    }
    private fun stopStats() { statsJob?.cancel(); statsJob = null }
}
