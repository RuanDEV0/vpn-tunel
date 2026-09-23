package br.com.ruan.splitvpn.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import br.com.ruan.splitvpn.databinding.ActivityMainBinding
import br.com.ruan.splitvpn.domain.ConnectionPhase
import br.com.ruan.splitvpn.domain.VpnSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private val model: MainViewModel by viewModels()
    private var packageNames: List<String> = emptyList()
    private var loaded = false
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) model.connect()
        else Toast.makeText(this, "Autorização VPN negada", Toast.LENGTH_LONG).show()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // A VPN continua disponível se o usuário recusar a permissão de aviso.
        requestVpnConsent()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val packages = installedLaunchableApps()
                val labels = packages.map { pkg ->
                    try { "${packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))} ($pkg)" }
                    catch (_: Exception) { pkg }
                }
                packages to labels
            }
            packageNames = apps.first
            val labels = apps.second
            binding.appSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            loaded = true
            val saved = model.settings.value
            if (saved.packageName.isNotBlank()) selectPackage(saved.packageName)
        }
        binding.saveButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    saveFields()
                    Toast.makeText(this@MainActivity, "Configuração salva", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    binding.statusText.text = "Erro ao salvar configurações (${e.javaClass.simpleName})"
                }
            }
        }
        binding.connectButton.setOnClickListener {
            lifecycleScope.launch {
                try {
                    saveFields()
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else requestVpnConsent()
                } catch (e: Exception) {
                    binding.statusText.text = e.message ?: "Não foi possível salvar a configuração"
                }
            }
        }
        binding.disconnectButton.setOnClickListener { model.disconnect() }
        binding.privacyButton.setOnClickListener {
            lifecycleScope.launch {
                val policy = withContext(Dispatchers.IO) { assets.open("privacy.txt").bufferedReader().use { it.readText() } }
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("Política de privacidade")
                    .setMessage(policy).setPositiveButton("Fechar", null).show()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.settings.collect { if (!binding.address.isFocused) showSettings(it) } }
                launch { model.status.collect {
                    binding.statusText.text = when (it.phase) {
                        ConnectionPhase.DISCONNECTED -> "Desconectado"
                        ConnectionPhase.CONNECTING -> "Conectando: ${it.message}"
                        ConnectionPhase.CONNECTED -> "Conectado: ${it.message}"
                        ConnectionPhase.ERROR -> "Erro: ${it.message}"
                    }
                    binding.selectedApp.text = "Aplicativo: ${it.packageName.ifBlank { model.settings.value.packageName.ifBlank { "nenhum" } }}"
                    binding.trafficText.text = "Enviados: ${it.txBytes} bytes  •  Recebidos: ${it.rxBytes} bytes"
                } }
            }
        }
    }
    private fun requestVpnConsent() {
        val intent: Intent? = VpnService.prepare(this)
        if (intent == null) model.connect() else consent.launch(intent)
    }
    private suspend fun saveFields() {
        val pkg = packageNames.getOrNull(binding.appSpinner.selectedItemPosition).orEmpty()
        model.save(VpnSettings(
            packageName = pkg,
            address = binding.address.text.toString(),
            dns = binding.dns.text.toString(),
            endpoint = binding.endpoint.text.toString(),
            peerPublicKey = binding.peerPublicKey.text.toString(),
            allowedIps = binding.allowedIps.text.toString()
        ), binding.privateKey.text.toString(), binding.presharedKey.text.toString())
        binding.privateKey.text.clear()
        binding.presharedKey.text.clear()
    }
    private fun showSettings(s: VpnSettings) {
        if (binding.endpoint.hasFocus()) return
        if (loaded) selectPackage(s.packageName)
        binding.address.setText(s.address)
        binding.dns.setText(s.dns)
        binding.endpoint.setText(s.endpoint)
        binding.peerPublicKey.setText(s.peerPublicKey)
        binding.allowedIps.setText(s.allowedIps)
        binding.keyHint.text = if (s.hasPrivateKey) "Chave privada salva com segurança" else "Chave privada necessária"
    }
    private fun selectPackage(pkg: String) {
        val index = packageNames.indexOf(pkg)
        if (index >= 0) binding.appSpinner.setSelection(index)
    }
    private fun installedLaunchableApps(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val items = if (Build.VERSION.SDK_INT >= 33)
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else packageManager.queryIntentActivities(intent, 0)
        return items.mapNotNull { it.activityInfo?.packageName }.distinct().sorted()
    }
}
