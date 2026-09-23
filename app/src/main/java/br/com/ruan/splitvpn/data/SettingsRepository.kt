package br.com.ruan.splitvpn.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import br.com.ruan.splitvpn.domain.VpnSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vpnDataStore by preferencesDataStore("vpn_settings")

@Singleton class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secrets: SecretStore
) {
    private object Keys {
        val pkg = stringPreferencesKey("package")
        val address = stringPreferencesKey("address")
        val dns = stringPreferencesKey("dns")
        val endpoint = stringPreferencesKey("endpoint")
        val publicKey = stringPreferencesKey("public_key")
        val allowedIps = stringPreferencesKey("allowed_ips")
    }
    val settings: Flow<VpnSettings> = context.vpnDataStore.data.map { p ->
        VpnSettings(
            packageName = p[Keys.pkg].orEmpty(),
            address = p[Keys.address] ?: "10.7.0.2/32",
            dns = p[Keys.dns] ?: "1.1.1.1",
            endpoint = p[Keys.endpoint].orEmpty(),
            peerPublicKey = p[Keys.publicKey].orEmpty(),
            allowedIps = p[Keys.allowedIps] ?: "0.0.0.0/0, ::/0",
            hasPrivateKey = secrets.has("private"),
            hasPresharedKey = secrets.has("preshared")
        )
    }.flowOn(Dispatchers.IO)
    suspend fun save(value: VpnSettings, privateKey: String, presharedKey: String) {
        // A DataStore só contém campos não secretos; as chaves ficam cifradas com Android Keystore.
        if (privateKey.isNotBlank()) secrets.put("private", privateKey.trim())
        if (presharedKey.isNotBlank()) secrets.put("preshared", presharedKey.trim())
        context.vpnDataStore.edit { p ->
            p[Keys.pkg] = value.packageName.trim()
            p[Keys.address] = value.address.trim()
            p[Keys.dns] = value.dns.trim()
            p[Keys.endpoint] = value.endpoint.trim()
            p[Keys.publicKey] = value.peerPublicKey.trim()
            p[Keys.allowedIps] = value.allowedIps.trim()
        }
    }
    fun privateKey(): String = secrets.get("private") ?: ""
    fun presharedKey(): String = secrets.get("preshared") ?: ""
}
