# Voice-Controlled Android Assistant — Research, Feasibility & Expansion Analysis

Target device: **Poco X6 Pro** (HyperOS on Android 14/15, Dimensity 8300-Ultra, 12 GB RAM / 512 GB)
Goal: *“Hey Partner, lock my phone”* — a personally-trained, always-listening voice assistant that executes
device tasks, plus a survey of every other way to make the phone easier to use.

---

## 1. Prompt analysis (deconstructed)

| Requirement | Technical meaning | Difficulty |
|---|---|---|
| Wake word “Hey Partner” | Continuous, on-device keyword spotting (KWS) | Medium |
| User-configurable wake words (“Hey Jervis”, “Hello Sir”, “Dost”) | Multiple simultaneous KWS models, runtime-managed | Medium-High |
| “Voice training” (pre-set) | (a) phrase→action presets, (b) optional speaker gate | Low / High |
| “Lock my phone” | Accessibility global action or Device Admin | Low |
| Play, pause, call contact, etc. | Media key dispatch, Contacts + `ACTION_CALL` | Low-Medium |
| Works reliably on a Xiaomi phone | Survive HyperOS app-killing | **The hardest part** |

**Core truth:** everything you asked for is possible for a third-party app — *except* two things:
1. A truly zero-battery wake word (that DSP path is reserved for the privileged “Hey Google” app), and
2. Voice-unlocking the phone or bypassing the lock screen (prohibited by Android’s security model).

---

## 2. Platform ground truth — what Android allows a 3rd-party app

### 2.1 Always-on microphone
- **Android 11+**: mic access is muted for background apps; you need a **foreground service**.
- **Android 14+ (your phone)**: the service must declare `foregroundServiceType="microphone"`, and a
  mic-type FGS **cannot be started while the app is in the background** (e.g., not directly after reboot —
  the app must be opened once, or the user taps a “restart” notification).
- The **low-power DSP hotword path** (Qualcomm SVA / “Ok Google” offload) is not available to normal apps.
  A third-party wake word must run a CPU audio loop → real but modest battery cost.
- Lock-screen listening: the FGS can keep capturing while locked, but OEM behavior varies; expect to
  test on HyperOS. “Hey Google”-from-AOD is privileged and not replicable.

### 2.2 Actions — possible vs. restricted

| Action | How | Works? | Notes |
|---|---|---|---|
| Lock screen | `AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN` (Android 9+) | Yes | Best path; also DeviceAdmin `lockNow()` as fallback (deprecated path on 14+, still functional) |
| Play / pause / next / previous | `AudioManager.dispatchMediaKeyEvent()` | Yes | Deprecated-but-working; occasionally ROM-specific quirks |
| Call someone by name | Contacts lookup + `ACTION_CALL` | Yes | Runtime permissions: `READ_CONTACTS`, `CALL_PHONE` |
| Open any app | Launcher intent query + launch | Yes | Needs `<queries>` manifest block (Android 11+ package visibility) |
| Flashlight on/off | `CameraManager.setTorchMode()` | Yes | No camera permission needed |
| Volume up/down/max/mute | `AudioManager` (media stream) | Yes | Ringer streams need DND access |
| Silent / vibrate / normal mode | `NotificationManager` ringer + policy access | Yes | Requires user to grant “Do Not Disturb access” |
| Screenshot | `GLOBAL_ACTION_TAKE_SCREENSHOT` (Android 9+) | Yes | System saves it like a normal screenshot |
| Alarm / timer | `AlarmClock.ACTION_SET_ALARM / SET_TIMER` | Yes | Permission `SET_ALARM`; alarm app shows optional UI |
| Brightness | `Settings.System.SCREEN_BRIGHTNESS` | Yes | Needs “Write system settings” special grant |
| Notification shade / quick settings / home / recents | Accessibility global actions | Yes | |
| Battery / time / date (spoken answer) | System APIs + TTS | Yes | |
| Find my phone (loud ring) | Ringer max + TTS + vibrate | Yes | |
| Read notifications aloud | Accessibility notification feedback | Possible (sensitive) | Roadmap R2; Play-policy-sensitive |
| Type text into any app field | Accessibility `ACTION_SET_TEXT` | Possible (sensitive) | Roadmap R2 |
| **Wi-Fi / Bluetooth / airplane toggle** | — | **No** (3rd-party, since Android 10) | Only `Settings.Panel` UI can open (user must tap) |
| **Unlock phone** | — | **No** | Security model; even Google Assistant can’t unlock with voice |
| **Send SMS content** | — | Restricted | `SEND_SMS` is Play-policy-restricted; use share intents instead |

### 2.3 Xiaomi / HyperOS reality (dontkillmyapp.com rates Xiaomi 5/5 — worst tier)

Your Poco X6 Pro will kill the assistant unless you, once:
1. **Autostart**: Settings → Apps → *app* → App permissions → **Background autostart** ON (MIUI 14+/HyperOS),
   or Security app → Permissions → Auto-start.
2. **Battery**: Settings → Apps → *app* → Battery → **No restrictions** (and “Save power in background” → off).
3. **Pin the app** in Recents (long-press card → padlock) so “Clear all” spares it.
4. Optionally: developer options → **MIUI/HyperOS optimizations** leave ON (turning off breaks other things).
5. Avoid Ultra Battery Saver (it kills everything except a whitelist).

The app ships a checklist + direct deep-links for each of these.

---

## 3. Engine landscape (wake word)

| Engine | License | Custom phrase | Model size | CPU/battery | Accuracy | Notes |
|---|---|---|---|---|---|---|
| **Porcupine (Picovoice)** | Proprietary, free personal tier | Yes — train any phrase in Picovoice Console (web), export `.ppn` per platform | ~1–2 MB/phrase | Best-in-class (typically <1%/day) | Best-in-class | Requires free `AccessKey`; multiple simultaneous keywords supported (`process()` returns keyword index) |
| **Vosk keyword matching** | Apache 2.0 | Yes — any text phrase, zero training | ~40–80 MB model | Moderate (full ASR always running) | Good for 2+ word phrases | Also reusable as the offline command recognizer; small models for 20+ languages incl. Hindi |
| openWakeWord | Apache 2.0 (models CC-BY-NC-SA) | Yes — train in Colab (<1 h) | ~1 MB + TFLite | Good | Competitive | Python/ONNX-first; Android = manual TFLite integration |
| microWakeWord | Apache 2.0 | Train yourself | tiny | Very low | Good | Built for ESP32-class; Android port is DIY |
| Snowboy | — | — | — | — | — | **Dead** (shut down 2020) — avoid |
| SpeechRecognizer as hotword | — | — | — | — | — | Cannot run continuously/reliably in background; not a wake-word engine |

**Decision for this app:** Porcupine is the primary engine *per phrase* (user imports a Console-exported
`.ppn` for each phrase — free), and Vosk text-matching is the universal fallback so any phrase works with
zero setup. Both run in one pipeline; whichever fires first wins. This also gives resilience: bad/missing
AccessKey → everything still works via Vosk.

### Custom wake-phrase notes (your examples)
- **“Hey Partner”, “Hey Jervis”, “Hello Sir”** — Porcupine Console trains each in ~1 minute (type it,
  download Android `.ppn`, import into app). Vosk matching also works (English-ish phrases).
- **“Dost”** (single word, Hindi/Urdu): with Vosk en-US it may transcribe as “dust/toast” — short single
  words have higher false-accept risk (mitigated in-app by a tighter distance threshold). Porcupine handles
  it fine because it is phoneme-based, not vocabulary-based. Recommendation: 2+ word phrases for Vosk mode;
  Porcupine `.ppn` for anything exotic or non-English.

## 4. Command recognition (after wake word)

| Option | Pros | Cons |
|---|---|---|
| **Android `SpeechRecognizer`** (Google) | Free, best accuracy, no model download, many languages | Usually network-dependent (Poco has no on-device engine); Google processes audio (privacy) |
| **Vosk one-shot** | Fully offline, private, airplane-mode-proof | ~50 MB model; slightly lower accuracy |
| **Hybrid (chosen)** | Best of both: try system recognizer, auto-fall back to Vosk offline | Slightly more code |
| Whisper tiny (ONNX/TFLite) | Great accuracy | Slow on-device, no true streaming; R3 curiosity |
| Cloud STT APIs | Best accuracy | Cost, keys, privacy — unnecessary here |

## 5. “Voice training” — what is honestly achievable

Two different things people mean by “voice training”:

1. **Command presets** (easy, implemented): you record/map custom phrases to actions (“goodnight” →
   lock + silent). Pure configuration, not speaker identity.
2. **Speaker gate** (hard): only *your* voice triggers the assistant.
   - What this app implements: enroll 3 samples of one wake phrase → MFCC + DTW template match on the
     wake audio at runtime. Blocks TV/radio/other people *most of the time*. **Not security-grade**: a
     recording of you, or a similar voice, can pass. It is a convenience filter, exactly like it sounds.
   - Production-grade on-device speaker verification: **Picovoice Eagle** (enrollment + embedding similarity,
     works with any phrase, commercial) or open-source x-vectors (SpeechBrain ECAPA-TDNN via ONNX, ~20 MB,
     meaningful R&D effort). Roadmap R4.
   - Even Google’s “Voice Match” is explicit that it is a convenience feature, not authentication —
     and Android will never let a voice match unlock the device regardless.

## 6. Implemented architecture

```
┌─────────────────────────── WakeWordService (FGS, type=microphone) ───────────────────────────┐
│ MicLoop (own AudioRecord 16 kHz, ring buffer, pause/resume)                                  │
│   ├─ PorcupineWakeEngine  (.ppn files from filesDir/keywords, N simultaneous keywords)       │
│   └─ VoskWakeEngine       (text-phrase matching on live partial transcription)               │
│ onWake ─→ VoiceGate (optional, MFCC+DTW vs enrolled profile for that phrase)                 │
│        ─→ Beep → CommandEngine (SpeechRecognizer → VoskOneShot fallback) → text             │
│        ─→ CommandParser (custom presets first, then ~25 built-in rules)                      │
│        ─→ CommandExecutor (Accessibility / DeviceAdmin / system intents / TTS confirmation)  │
│        ─→ micLoop.resume()                                                                    │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
 WakePhraseStore (JSON, persists across restarts): id, label, match texts, .ppn path, enabled
 VoiceCommandAccessibilityService: lock / screenshot / home / recents / shades
 MyDeviceAdminReceiver: lock fallback (Android 8.x)
 BootReceiver: restart (≤13) / one-tap notification (14+ mic-FGS restriction)
```

Battery expectations on the X6 Pro: Porcupine phrases ≈ 1–2%/day; Vosk-always-on ≈ 3–8%/day
(5 800 mAh battery — fine either way; the Dimensity 8300 handles Vosk small models easily).

---

## 7. Expansion roadmap

| Phase | Feature | Why / how |
|---|---|---|
| R1 (done) | Multi wake phrases, presets, hybrid STT, speaker gate, 25 commands | This codebase |
| R2 | Notification read-out & reply (“read my notifications”, “reply OK”) | Accessibility `NotificationListener`-style feedback + `ACTION_SET_TEXT`; sensitive permission — keep opt-in |
| R2 | “Goodnight” macro chains (one phrase → N actions) | Preset already supports it via macro action type |
| R3 | Natural-language commands via on-device LLM | `Gemini Nano`/AICore (Pixel-first) or llama.cpp with a 1–3 B model on the X6 Pro; replaces regex parser |
| R3 | Multilingual commands | Vosk hi/bn models + `SpeechRecognizer` `EXTRA_LANGUAGE`; wake words stay engine-agnostic |
| R4 | Real speaker verification (Eagle / ECAPA embeddings) | Any-phrase gating, spoof-hardening, per-user profiles |
| R4 | Wear OS / Bluetooth-button trigger | Trigger without wake word; offloads listening start |
| R5 | Context automations (NFC tag, Wi-Fi SSID, time, geofence) | “Phone in car dock → start listening”; NFC tags + Tasker interop |
| R5 | Whisper tiny offline fallback for noisy environments | Better far-field robustness than Vosk small |

## 8. Other ways to use your phone more easily (beyond this app)

| Method | Setup | Flexibility | Offline | Cost | Verdict |
|---|---|---|---|---|---|
| **This app** | Medium (one-time grants + optional Picovoice account) | Full (any phrase → any supported action) | Mostly | Free | Best for your exact “Hey Partner” vision |
| **Google Assistant/Gemini Routines** | Low (no code) | Limited to Google’s action catalog; “Hey Google” wake word only | No | Free | Great complement: “Hey Google, goodnight” chains |
| **Voice Access** (Google accessibility app) | Low | Extremely high (controls *everything* on screen by voice) | Partial | Free | Different goal: full hands-free UI control; no custom wake word |
| **Tasker + AutoVoice** | High | Maximum automation (any trigger → any action) | Yes | Paid (~$3.5) | Power-user alternative to this whole project |
| **MacroDroid** | Medium | High, friendlier UI than Tasker | Yes | Free tier / ~$5 | Best no-code automation app on Android |
| **Bixby Routines / Modes** (Samsung) | n/a on Poco | — | — | — | Samsung-only |
| **HyperOS “Modes”** (your phone) | Low | Medium (time/location/charging triggers) | Yes | Free | Complements: automatic dark mode/DND per scenario |
| **NFC tags** (X6 Pro has NFC) | Low | Medium (tap → routine) | Yes | ~$1/tag | Brilliant physical shortcuts: bedside tag = “goodnight” |
| **Quick settings tiles / shortcuts** | Low | Low-medium | Yes | Free | The app could donate its own tiles later |

Recommended combo for your phone: **this app for voice** + HyperOS Modes for time/place automation +
one NFC tag on the nightstand.

## 9. Pros, cons, limitations of the custom-app approach (honest list)

**Pros:** any wake word, any command mapping, offline-first, private (Porcupine/Vosk are on-device),
zero recurring cost, extensible, no dependence on Google’s whims.

**Cons / limitations & mitigations:**
- Battery: real but small (Porcupine) — use Porcupine `.ppn` for your main phrase.
- HyperOS may still kill the service after aggressive cleaning — follow the in-app Xiaomi checklist;
  pin in recents; battery “No restrictions”.
- Android 14+: after reboot you must open the app once (mic FGS can’t start from boot) — the app
  posts a one-tap restart notification.
- Media key dispatch is officially deprecated — works today on HyperOS, re-verify after big updates.
- Speaker gate is a convenience filter, not authentication — never gate anything security-relevant.
- Mic-always-on has social/privacy optics; the app never uploads audio (all engines on-device),
  and the status is always visible in the notification.
- Play Store distribution would face Accessibility/Device-Admin policy review — irrelevant if
  side-loaded (which is the plan for personal use).
- Single-device test matrix: you (Poco X6 Pro); other OEMs will need the dontkillmyapp treatment.

## 10. Final recommendation

Build exactly what is implemented here: **Porcupine-per-phrase (with .ppn import) + Vosk text fallback,
hybrid command STT, custom presets, optional non-security speaker gate**, with a first-run permission
wizard including the Xiaomi-specific survival settings. Keep Assistant Routines and one NFC tag as
complements. Revisit Eagle-style speaker verification and LLM intent parsing if you outgrow the regex
command grammar.

Sources consulted: Picovoice Porcupine docs (quick-start/API, Android SDK), alphacephei.com/vosk
(features/models), github.com/dscripka/openWakeWord (README, performance, training),
dontkillmyapp.com/xiaomi (HyperOS/MIUI survival), Android SDK references for AccessibilityService,
SpeechRecognizer, DeviceAdminReceiver, AlarmClock, CameraManager torch APIs.
