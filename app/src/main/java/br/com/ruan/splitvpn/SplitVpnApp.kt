package br.com.ruan.splitvpn

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import br.com.ruan.splitvpn.vpn.VpnController

@HiltAndroidApp class SplitVpnApp : Application() {
    @Inject lateinit var controller: VpnController
}
