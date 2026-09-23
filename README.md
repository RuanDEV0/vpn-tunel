# Split VPN

Cliente Android (Kotlin, minSdk 26) que cria uma VPN WireGuard para **um único aplicativo** escolhido na tela. Usa a biblioteca oficial `com.wireguard.android:tunnel`, cujo `GoBackend` cria a TUN com `VpnService.Builder.addAllowedApplication` para `IncludedApplications`. O serviço VPN do app herda `GoBackend.VpnService`, opera em primeiro plano e fornece a notificação de desconexão.

## Build

Requer JDK 17 e Android SDK Platform 36. Defina `ANDROID_HOME` para o SDK ou crie `local.properties` com `sdk.dir=/caminho/para/Android/Sdk`. A partir da raiz:

```sh
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Para instalar: `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Testes unitários: `./gradlew testDebugUnitTest`. Teste instrumentado: `./gradlew connectedDebugAndroidTest` com emulador/dispositivo ativo. O teste instrumentado verifica a tela e o ponto de entrada de consentimento; handshake real requer servidor e validação manual.

## Servidor WireGuard de teste

Configure um servidor WireGuard de sua confiança com UDP 51820 aberto, encaminhamento IPv4/IPv6 habilitado e NAT para saída. Gere pares de chaves **fora** deste projeto com `wg genkey | tee privatekey | wg pubkey > publickey`. Exemplo de servidor (substitua os valores e ajuste a interface de saída/NAT à sua rede):

```ini
[Interface]
Address = 10.7.0.1/24
ListenPort = 51820
PrivateKey = <CHAVE_PRIVADA_DO_SERVIDOR>

[Peer]
PublicKey = <CHAVE_PUBLICA_DO_CLIENTE>
AllowedIPs = 10.7.0.2/32
```

Em Linux, por exemplo, habilite o encaminhamento IPv4 com `sudo sysctl -w net.ipv4.ip_forward=1` e configure mascaramento para a subrede VPN na interface de saída (substitua `eth0`): `sudo iptables -t nat -A POSTROUTING -s 10.7.0.0/24 -o eth0 -j MASQUERADE`. Permita o encaminhamento no firewall e ative `wg-quick@wg0`. Persistir essas regras depende da distribuição. Não copie as chaves de exemplo literais.

Na tela do app selecione o aplicativo alvo e informe endereço do cliente `10.7.0.2/32`, DNS, endpoint público `<host>:51820`, chave pública do servidor, rotas `0.0.0.0/0, ::/0` e chave privada do cliente. Se seu servidor não encaminha IPv6, use apenas `0.0.0.0/0` e ajuste DNS/endereço para IPv4. O servidor deve incluir a chave pública derivada da chave privada do cliente; se usar preshared key, configure o mesmo valor em ambos. Após conectar, gere tráfego no app selecionado e confirme o handshake e bytes. Compare outro app para conferir que permanece na rede normal.

## Arquitetura e segurança

- UI XML + `MainViewModel` + `StateFlow`; domínio em `domain/`, dados em `data/`, VPN em `vpn/`.
- Configurações comuns em DataStore. Chaves privadas e pré-compartilhadas cifradas em arquivos locais por AES-GCM com chave no Android Keystore. Backup desabilitado.
- O app consulta a lista de aplicativos com launcher e valida o pacote instalado antes de conectar. Só esse pacote entra em `IncludedApplications`.
- I/O de DataStore, Keystore, inicialização do backend e leitura das estatísticas executam em `Dispatchers.IO`.
- O código não registra chaves nem payloads. Exceções do parser que podem incluir dados de configuração são reduzidas a mensagem genérica.

## Limitações e publicação

- O status `Conectado` inicial significa TUN criada; o texto muda para `Handshake confirmado` após tráfego. WireGuard normalmente negocia quando há pacotes. Timeout é reportado se houver bytes enviados sem handshake por 15 segundos.
- Erros internos de parsing de pacotes do `wireguard-go` não são expostos pela API pública da biblioteca; o app informa erros de configuração e ativação. A inspeção de pacotes permanece no backend oficial.
- Em Android 11+, a lista mostra apps com atividade launcher, conforme `<queries>`; apps sem launcher não aparecem. Um perfil de trabalho pode ter restrições adicionais.
- A reconexão é acionada por mudança da rede não VPN. Restrições do sistema para serviços iniciados em segundo plano podem impedir uma nova inicialização após o sistema encerrar o processo; o usuário poderá reabrir o app.
- Para Play Store, forneça uma política de privacidade hospedada (a versão local fica no app), declare o uso da API `VpnService` e do foreground service tipo `specialUse` na Play Console, explique o roteamento exclusivo ao app escolhido e conclua a revisão aplicável. Não há chave de assinatura release neste repositório.

Referências: [biblioteca oficial WireGuard para Android](https://github.com/WireGuard/wireguard-android/blob/master/README.md), [VpnService.Builder.addAllowedApplication](https://developer.android.com/reference/android/net/VpnService.Builder#addAllowedApplication(java.lang.String)) e [foreground service specialUse](https://developer.android.com/about/versions/14/changes/fgs-types-required).
