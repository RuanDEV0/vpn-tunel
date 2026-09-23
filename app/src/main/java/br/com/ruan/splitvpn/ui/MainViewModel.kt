package br.com.ruan.splitvpn.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.ruan.splitvpn.data.SettingsRepository
import br.com.ruan.splitvpn.domain.VpnSettings
import br.com.ruan.splitvpn.vpn.VpnController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel class MainViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val controller: VpnController
) : ViewModel() {
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VpnSettings())
    val status = controller.status
    suspend fun save(settings: VpnSettings, privateKey: String, presharedKey: String) =
        withContext(Dispatchers.IO) { repository.save(settings, privateKey, presharedKey) }
    fun connect() = controller.connect()
    fun disconnect() = controller.disconnect()
}
