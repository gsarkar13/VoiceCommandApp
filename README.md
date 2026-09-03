# Partner — Voice-Controlled Android Assistant

Say a wake phrase you define (default: **“Hey Partner”**), then a command — the phone does it.
Built and tuned for a **Poco X6 Pro (HyperOS, Android 14/15)**, but runs on any Android 8.0+ device.

> Read `RESEARCH.md` for the full feasibility analysis, engine comparisons, limitations,
> alternatives (Assistant Routines, Tasker, Voice Access, NFC tags…) and the expansion roadmap.

## What it can do

| Say (after the wake phrase) | Action |
|---|---|
| “lock my phone” | Locks the screen (accessibility action, device-admin fallback) |
| “play” / “pause” / “next song” / “previous song” | Media control of whatever is playing |
| “call [contact name]” | Finds the best match in contacts and calls it |
| “open [app name]” / “open settings” | Launches the app |
| “flashlight on / off” | Torch |
| “volume up / down / max / mute” | Media volume |
| “take a screenshot” | System screenshot |
| “go home” / “show recents” / “open notifications” / “quick settings” | Navigation |
| “what time is it” / “what’s the date” / “battery level” | Spoken answers (TTS) |
| “set alarm for 7 30 am” / “timer for 5 minutes” | Clock app, skip-UI |
| “silent mode on / off” | Ringer (needs DND access) |
| “brightness 50” | Screen brightness (needs write-settings) |
| “find my phone” | Max volume ring + vibration |
| “go to sleep” | Stops listening until you reopen the app |
| your own custom phrases | Map any phrase to any action in the app |

Wake phrases are **fully configurable**: add “Hey Jervis”, “Hello Sir”, “Dost”… toggle each
on/off, all active phrases are detected simultaneously, and everything persists across restarts.

## Architecture (short version)

- `WakeWordService` — foreground service (type `microphone`) owning a 16 kHz mic loop
- **Porcupine** (Picovoice) — per-phrase `.ppn` keyword models, best battery/accuracy (optional)
- **Vosk** — text-matching fallback for phrases without a `.ppn`, and offline command STT
- Commands → Android `SpeechRecognizer` (Google) → automatic Vosk fallback when offline
- Optional **voice gate**: enroll 3 samples of a wake phrase; MFCC+DTW filters other voices
  (convenience filter — **not security-grade**, see RESEARCH.md §5)
- Actions via AccessibilityService, DeviceAdmin, and public system intents
- All speech processing is on-device; no audio leaves the phone

## Setup

### 1. Build & install
1. Open the project folder in **Android Studio** (Hedgehog/Koala or newer). Let Gradle sync —
   Android Studio creates the wrapper automatically. Requires JDK 17+ (bundled with recent AS).
2. Enable USB debugging on the phone, plug in, press **Run**. Or build an APK
   (`Build → Build APKs`) and `adb install app-debug.apk`.

### 2. Vosk model (enables out-of-the-box wake + offline commands)
1. Download **`vosk-model-small-en-us-0.15.zip`** (~40 MB) from
   <https://alphacephei.com/vosk/models> and unzip it.
2. Copy the folder to the phone so one of these paths exists (pick one):
   - `/sdcard/Download/vosk-model-small-en-us-0.15/` (adb push or USB file transfer)
   - `/sdcard/vosk-model/`
   - Android/data/com.voicecommand.partner/files/vosk-model/
3. The app auto-detects it and shows the path under *Wake-word engines*.
   You can also type an explicit path there.

### 3. Porcupine (optional, recommended for battery + exotic phrases)
1. Create a free account at <https://console.picovoice.ai> → copy your **AccessKey** →
   paste it in the app (*Wake-word engines*).
2. For any wake phrase: *Porcupine Console → Train keyword* → type e.g. `Hey Partner`
   → download **Android (.ppn)** → copy to the phone (e.g. Download folder).
3. In the app: *Wake phrases → ⭳ Import* on that phrase’s row → pick the `.ppn` file.
   The row now shows “Porcupine model”.
4. Without Porcupine the app still works — every phrase falls back to Vosk text matching
   (needs the Vosk model). Prefer 2+ word phrases in Vosk mode; single words like “Dost”
   are more reliable with a trained `.ppn`.

### 4. Permissions checklist (in-app)
Grant in order: Microphone → Contacts & calling → Notifications (Android 13+) →
**Accessibility** (found in Settings → Accessibility → “Partner Voice Control”; this powers
lock/screenshot/home) → Device admin (optional lock fallback) → Do-Not-Disturb access
(silent mode) → Write system settings (brightness) → Ignore battery optimizations.

### 5. Poco/HyperOS survival (one-time, critical)
Xiaomi aggressively kills background apps (see dontkillmyapp.com/xiaomi). On your X6 Pro:
1. Settings → Apps → Partner → App permissions → **Background autostart: ON**
2. Settings → Apps → Partner → Battery → **No restrictions**
3. Recents → long-press the Partner card → **pin/lock** it, so “Clear all” spares it
4. Keep MIUI/HyperOS optimizations ON; avoid Ultra Battery Saver

## Daily use
Tap **Start listening** once. Say **“Hey Partner”** (beep) then a command. The notification
shows which engines are live; its **Stop** action ends listening. “Go to sleep” also stops it —
reopen the app to resume. After a reboot on Android 14+, tap the restart notification
(or open the app) — the OS does not allow a mic service to start itself from the boot
receiver on 14+.

## Voice gate (optional)
*Wake phrases are separate from the gate*: pick one enrolled phrase, record 3 samples,
enable the switch. When that specific phrase is heard, your voice must match the template.
Strictness: Strict (fewer accepts) / Balanced / Loose. It will reject most other people/TV
voices but is **not** an authentication mechanism — a recording of you can pass. Keep it off
for anything security-relevant; Android never allows voice-unlock anyway.

## Custom commands (presets)
*Custom commands → Add*: type a phrase (e.g. `goodnight`), choose an action (Lock screen,
Silent mode on, etc.), optional argument (contact/app name, minutes, percent). Custom
phrases are matched before built-ins.

## Troubleshooting
- **Service dies / no wake word after a while** → HyperOS settings in step 5; check that the
  notification is present; re-pin in Recents.
- **No wake engine available** → set the AccessKey (Porcupine) and/or place the Vosk model,
  then *Reload engines*.
- **Wake word misses in Vosk mode** → speak the full phrase, 1 m from the mic; prefer 2+ word
  phrases; or import a `.ppn` for that phrase.
- **Commands fail offline** → the Google recognizer needs internet on Poco; without it the app
  automatically uses Vosk — make sure the model is installed.
- **“Call” opens the dialer instead of calling** → grant Phone permission.
- **Lock says enable accessibility** → turn on the accessibility service (or device admin).
- **“Do Not Disturb access” / “Write system settings” toggle missing** → these are Special app
  access grants; on HyperOS you can also reach them via Settings → Apps → Partner → Other
  permissions (“Do not disturb access”, “Modify system settings”). Re-check after app updates —
  HyperOS sometimes resets special access grants.
- **After reboot nothing happens** → expected on Android 14+: open the app once (the boot
  notification offers this), listening resumes automatically.
- **Media keys ignored by some app** → `dispatchMediaKeyEvent` occasionally varies by ROM/app;
  test with a mainstream player first.

## Limitations (honest)
- No voice unlock, no Wi-Fi/Bluetooth/airplane toggles (blocked for 3rd-party apps since
  Android 10) — see RESEARCH.md §2.2.
- Always-on mic costs battery: ~1–2%/day Porcupine-only, ~3–8%/day if a phrase uses Vosk.
- The speaker gate is a convenience filter, not security.
- Play-Store distribution would need accessibility/device-admin policy review — this project
  assumes personal side-loading.
