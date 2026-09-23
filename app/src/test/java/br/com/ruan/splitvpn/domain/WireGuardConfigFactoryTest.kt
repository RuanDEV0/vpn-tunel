package br.com.ruan.splitvpn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.wireguard.crypto.KeyPair

class WireGuardConfigFactoryTest {
    private val privateKey = KeyPair().privateKey.toBase64()
    private val publicKey = KeyPair().publicKey.toBase64()
    @Test fun includesOnlySelectedApp() {
        val settings = VpnSettings(packageName = "com.example.target", endpoint = "127.0.0.1:51820",
            peerPublicKey = publicKey, hasPrivateKey = true)
        val config = WireGuardConfigFactory().create(settings, privateKey, "")
        assertEquals(setOf("com.example.target"), config.`interface`.includedApplications)
        assertEquals(emptySet<String>(), config.`interface`.excludedApplications)
    }
    @Test fun rejectsDirectiveInjection() {
        val settings = VpnSettings(packageName = "com.example.target\nDNS = 9.9.9.9")
        assertThrows(ConfigurationException::class.java) {
            WireGuardConfigFactory().create(settings, privateKey, "")
        }
    }
}
