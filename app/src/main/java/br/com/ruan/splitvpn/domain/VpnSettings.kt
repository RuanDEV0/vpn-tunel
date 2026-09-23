package br.com.ruan.splitvpn.domain

data class VpnSettings(
    val packageName: String = "",
    val address: String = "10.7.0.2/32",
    val dns: String = "1.1.1.1",
    val endpoint: String = "",
    val peerPublicKey: String = "",
    val allowedIps: String = "0.0.0.0/0, ::/0",
    val hasPrivateKey: Boolean = false,
    val hasPresharedKey: Boolean = false
)

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class VpnStatus(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val packageName: String = "",
    val txBytes: Long = 0,
    val rxBytes: Long = 0,
    val message: String = ""
)

class ConfigurationException(message: String) : Exception(message)

object SettingsValidator {
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    fun validate(settings: VpnSettings, installed: (String) -> Boolean) {
        if (!packagePattern.matches(settings.packageName) || !installed(settings.packageName))
            throw ConfigurationException("Aplicativo alvo não encontrado no dispositivo")
        if (!settings.hasPrivateKey) throw ConfigurationException("Informe a chave privada WireGuard")
        if (settings.endpoint.isBlank()) throw ConfigurationException("Informe o endpoint WireGuard")
        if (settings.peerPublicKey.isBlank()) throw ConfigurationException("Informe a chave pública do servidor")
        if (settings.address.isBlank() || settings.allowedIps.isBlank())
            throw ConfigurationException("Endereço e rotas WireGuard são obrigatórios")
    }
}
