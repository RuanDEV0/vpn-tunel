package br.com.ruan.splitvpn.domain

import com.wireguard.config.Config
import com.wireguard.config.BadConfigException
import java.io.BufferedReader
import java.io.StringReader
import javax.inject.Inject

class WireGuardConfigFactory @Inject constructor() {
    fun create(settings: VpnSettings, privateKey: String, presharedKey: String): Config {
        // Campos são validados pelo parser oficial antes de serem usados pelo backend nativo.
        // Quebras de linha são rejeitadas para impedir injeção de diretivas WireGuard.
        val values = listOf(settings.packageName, settings.address, settings.dns, settings.endpoint,
            settings.peerPublicKey, settings.allowedIps, privateKey, presharedKey)
        if (values.any { '\n' in it || '\r' in it }) throw ConfigurationException("Configuração inválida")
        val source = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = ${settings.address}")
            if (settings.dns.isNotBlank()) appendLine("DNS = ${settings.dns}")
            appendLine("IncludedApplications = ${settings.packageName}")
            appendLine("[Peer]")
            appendLine("PublicKey = ${settings.peerPublicKey}")
            if (presharedKey.isNotBlank()) appendLine("PresharedKey = $presharedKey")
            appendLine("Endpoint = ${settings.endpoint}")
            appendLine("AllowedIPs = ${settings.allowedIps}")
            appendLine("PersistentKeepalive = 25")
        }
        try {
            return Config.parse(BufferedReader(StringReader(source)))
        } catch (e: BadConfigException) {
            // Exibir apenas enums seguros: getText(), message e cause podem conter chaves.
            throw ConfigurationException("Configuração WireGuard inválida em ${e.section}.${e.location} (${e.reason})")
        } catch (_: Exception) {
            throw ConfigurationException("Configuração WireGuard inválida: revise IPs, endpoint e chaves")
        }
    }
}
