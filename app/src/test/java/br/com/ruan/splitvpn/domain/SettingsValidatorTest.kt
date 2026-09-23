package br.com.ruan.splitvpn.domain

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsValidatorTest {
    private val installed = mockk<(String) -> Boolean>()
    private val valid = VpnSettings(packageName = "com.example.target", endpoint = "vpn.example:51820",
        peerPublicKey = "server-key", hasPrivateKey = true)
    @Test fun acceptsInstalledTarget() {
        every { installed.invoke("com.example.target") } returns true
        SettingsValidator.validate(valid, installed)
    }
    @Test fun rejectsMissingTarget() {
        every { installed.invoke("com.example.target") } returns false
        val error = assertThrows(ConfigurationException::class.java) {
            SettingsValidator.validate(valid, installed)
        }
        assertEquals("Aplicativo alvo não encontrado no dispositivo", error.message)
    }
    @Test fun rejectsMissingSecret() {
        every { installed.invoke("com.example.target") } returns true
        assertThrows(ConfigurationException::class.java) {
            SettingsValidator.validate(valid.copy(hasPrivateKey = false), installed)
        }
    }
}
