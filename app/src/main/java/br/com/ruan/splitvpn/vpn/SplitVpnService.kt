package br.com.ruan.splitvpn.vpn

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import br.com.ruan.splitvpn.R
import br.com.ruan.splitvpn.SplitVpnApp
import br.com.ruan.splitvpn.ui.MainActivity
import com.wireguard.android.backend.GoBackend

/** O backend oficial usa este VpnService para criar a TUN.
 * Parsing de pacotes, criptografia e encaminhamento ocorrem no wireguard-go nativo;
 * este serviço nunca lê nem registra payloads em Kotlin.
 */
class SplitVpnService : GoBackend.VpnService() {
    companion object {
        const val ACTION_DISCONNECT = "br.com.ruan.splitvpn.DISCONNECT"
        private const val CHANNEL = "vpn_active"
        private const val NOTIFICATION_ID = 42
        @Volatile var instance: SplitVpnService? = null
            private set
    }
    private val controller get() = (application as SplitVpnApp).controller
    override fun onCreate() {
        super.onCreate() // Registra o serviço no GoBackend antes de abrir a interface TUN.
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "VPN ativa", NotificationManager.IMPORTANCE_LOW))
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
        instance = this // Sinaliza prontidão somente após a notificação foreground existir.
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_DISCONNECT) controller.disconnect()
        else if (intent == null) controller.connect() // Reinício pelo sistema após morte do processo.
        return START_STICKY
    }
    override fun onRevoke() {
        controller.onRevoked() // O sistema removeu o consentimento; não religar automaticamente.
        super.onRevoke()
        stopSelf()
    }
    override fun onDestroy() {
        super.onDestroy()
        controller.onServiceDestroyed()
        instance = null
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, SplitVpnService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Split VPN ativa")
            .setContentText("Somente o aplicativo selecionado usa WireGuard")
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Desconectar", stop)
            .build()
    }
}
