# AScan Agent — Native (Kotlin)

App Android **nativo** com Jetpack Compose. Roda em paralelo ao APK Kivy antigo.

## Pacote

- `applicationId`: `com.ascan.ascanagent` (mesmo do Kivy → update futuro por cima)
- Versão: `2.0.3-native`

## Funcionalidades (v1)

- UI roxa estilo moderno (Compose Material 3)
- Até 5 servidores
- Combo online via GitHub `combos/`
- 5 modos de ataque
- Threads configuráveis
- Proxy online / direto
- Hits em tempo real + ranking
- Salvamento em `Download/AScan_App/HITS`
- PLAYER (abre M3U no app de IPTV)

## Build

```bash
cd android-native
gradle assembleDebug
```

Workflow: `.github/workflows/build-native.yml`

O projeto **Kivy** (`main.py`) continua intacto na raiz.
