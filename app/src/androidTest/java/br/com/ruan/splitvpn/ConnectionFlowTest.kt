package br.com.ruan.splitvpn

import android.net.VpnService
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.ruan.splitvpn.ui.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionFlowTest {
    @Test fun connectionScreenExposesConsentAndStatus() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // prepare() devolve o diálogo do sistema ou null se já houve consentimento.
                VpnService.prepare(activity)
                val status = activity.findViewById<TextView>(R.id.statusText)
                assertNotNull(status)
                assertTrue(status.text.isNotBlank())
                assertNotNull(activity.findViewById<Button>(R.id.connectButton))
                assertNotNull(activity.findViewById<Button>(R.id.disconnectButton))
            }
        }
    }
}
