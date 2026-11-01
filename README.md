# AmpyCam 📸

Fotocamera Android della famiglia Ampy (stile AmpyRocket, AmpyLink, AmpyRAW Cyberdark): layout ispirato a Pixel Camera,
**controlli da reflex/mirrorless Canon EOS** ed **effetti speciali** a ogni scatto e a ogni avvio/stop video.
Camera2 in Java puro, nessuna libreria esterna. Android 8 → Android 17, pensata per Pixel 7 Pro.

## Scarica
Releases → `latest` → `AmpyCam.apk` (oppure Actions → ultimo build → artifact `AmpyCam-APK`).

## Modalità (carosello in basso, oppure scorri a destra/sinistra sul mirino)
| Modo | Cosa fa |
|---|---|
| **NOTTE** | Stacking: 4–16 fotogrammi YUV a esposizione lunga mediati (rumore ÷ √N), ombre sollevate |
| **RITRATTO** | Zoom 2×, sfocatura dello sfondo con maschera morbida centrata sul volto rilevato |
| **FOTO** | Automatico (A+), tocca per mettere a fuoco, trascina in verticale per la luminosità (come Pixel) |
| **VIDEO** | 720p / 1080p / 4K, 24-30-60 fps, H.264 o HEVC, stabilizzazione, audio |
| **PRO** | La ghiera Canon: **A+ · P · Tv · Av · M · C1** con ghiera principale a scatti |

## Funzioni Canon
- **Ghiera dei modi** P / Tv / Av / M / C1 (tieni premuto C1 per registrare le impostazioni correnti, come la posizione C1 di una EOS)
- **Tv con ISO Auto**, **M con ISO Auto** (ciclo di misurazione esposimetrica sul live view), **P/Av con ISO fisso** (tempo automatico)
- **Ghiera principale** per tempi a ⅓ di stop (da 30" a 1/16000 secondo il sensore), ISO a ⅓ di stop, compensazione ±3 EV
- **Indicatore del livello di esposizione** −3…+3 nella barra info, lampeggia se fuori scala in M; marcatori AEB
- **Simulazione dell'esposizione** nel mirino (anche con tempi lunghi)
- **Bilanciamento del bianco**: AWB, Luce diurna, Ombra, Nuvoloso, Tungsteno, Fluorescente, **Kelvin 2500–10000 K**
- **Picture Style**: Auto, Standard, Ritratto, Paesaggio, Dettaglio fine, Neutro, Fedele, Monocromatico (+ look Ampy: Cyberdark, Neon, Kodak Portra, Kodak Gold, Teal & Orange, Noir, Matrix) con anteprima dal vivo
- **AF One Shot / Servo / MF**, punto AF al tocco, **rilevamento volti**, **AE/AF lock ✱** (tieni premuto sul mirino), mezza pressione = AF
- **MF** con **focus peaking** e **ingrandimento ×5 / ×10** (doppio tocco)
- **Misurazione**: valutativa, parziale, spot, media pesata al centro
- **Drive**: singolo, **raffica H+ / L** (tieni premuto), autoscatto 2 s / 10 s / 10 s + 3 scatti, **intervallometro**
- **AEB** bracketing 3 scatti (0 / − / +) da ±⅓ a ±2 EV
- **Priorità toni chiari (D+)**, **limite ISO Auto**, **otturatore silenzioso**
- **RAW (DNG) + JPEG**, formati **3:2** (nativo Canon), 4:3, 16:9, 1:1 con maschera nel mirino
- **Livella elettronica a due assi**, **istogramma RGB** con avviso di clipping, **zebra**, 7 griglie (terzi, diagonali, 4×4, sezione e spirale aurea…)
- **Menu Q** (tocca la barra info o il chip Q), file **IMG_xxxx.JPG / .DNG / MVI_xxxx.MP4** in `DCIM/AmpyCam`, contatore scatti, scatti rimanenti, batteria
- Tasti volume = pulsante di scatto

## Effetti speciali
**Scatto**: Glitch Shot (split RGB + strappi + testo che si decodifica, poi la foto vola nella miniatura), Iride neon (lamelle del diaframma),
Genio (lampada magica di AmpyRocket), Pixel burn, CRT off, Holo scan (con dati di scatto), Onda d'urto, Polaroid, Classico, Casuale.
**Video**: REC glitch (bordo rosso + REC decodificato, allo stop la scena si spegne come un CRT), HUD scan, Pellicola 3-2-1.
Si scelgono dal chip ✦ in alto (con anteprima immediata) o dalle Impostazioni. Il modo NOTTE usa Holo scan con la barra di avanzamento.

## Temi
Gli stessi 6 temi di AmpyRocket / AmpyRAW: Cyberpunk, Windows Vista Aero, Futuristic Glass, Retro, Minimal, Blood Moon.

## Note oneste
- Gli smartphone hanno il diaframma fisso: in **Av** il valore F è quello dell'obiettivo in uso e la modalità dà priorità agli ISO bassi.
- Il RAW dipende dal telefono (Pixel 7 Pro: sì). Ritratto e notte sono elaborazioni dell'app, non l'HDR+/Night Sight di Google.
- Il progetto compila su GitHub Actions; va ancora collaudato sul telefono reale.

## Build
GitHub Actions compila a ogni push (`.github/workflows/android-apk.yml`). Firma stabile opzionale con i secrets
`AMPYCAM_KEYSTORE_B64`, `AMPYCAM_STORE_PASSWORD`, `AMPYCAM_KEY_ALIAS`, `AMPYCAM_KEY_PASSWORD`.
In locale: Android Studio, JDK 17, Gradle 8.13, SDK 36.

Davide Fraccaro · [Frak89](https://github.com/Frak89)
