# plan.md — Guide Dog Robot
### 48-hour hackathon build bible · 4-person team · pitch-to-win

---

## 0. The one-sentence pitch

**A guide dog robot that leads you, talks with you, and reads the world aloud to you — built for ~1/50th the cost of a real guide dog, on a phone you already own.**

The winning story is NOT "it avoids obstacles better than a cane." Plenty of smart canes already do obstacle avoidance. Our story is **companionship + communication + comprehension**: it leads on a leash, holds a real conversation, and reads signs, room numbers, and hazards out loud. Aim every hour of the build and every second of the pitch at that story.

---

## 1. Locked decisions (do not re-litigate mid-build)

| Decision | Locked choice | Why |
|---|---|---|
| Robot brain | **Samsung Galaxy S21**, native Android app | Owned already; ARCore-supported; no Mac needed |
| Language (app) | **Kotlin** | Native Android APIs (camera, ARCore, BLE, STT) live here |
| Language (firmware) | **C++ (Arduino)** | The ESP32 speaks this |
| Navigation strategy | **Sensor-first** (ultrasonic + ToF). ARCore depth = stretch | 2 devs new to mobile; sensors are demo-proof and EE-owned |
| Voice input | Android on-device `SpeechRecognizer` | Fast, offline-capable, zero setup |
| Conversation brain | **Groq** (fast LLM inference) | Lowest latency — critical for live voice demo |
| Voice output | **ElevenLabs** TTS | Best natural voice; makes the pitch land emotionally |
| Sign reading (OCR) | **ML Kit** Text Recognition | On-device, one API call, no setup |
| Phone ↔ ESP32 | **Bluetooth Low Energy (BLE)** | Wireless, low latency, no tether on moving robot |
| Harness | **Loose leash**, audio-primary leading | Low build effort; puts voice center-stage |
| Haptic buzz | **1 vibration motor on handle** — STRETCH | Cheap, pitches well, 20-min EE job |
| Duration | **48 hours** | — |
| Demo user | Blindfolded teammate on the leash + pre-filmed footage | Safe, controlled, reliable |

---

## 2. Software opinion (asked directly)

- **Kotlin + C++ are load-bearing.** Everything the robot does live runs in these two. Non-negotiable — it's where the hardware APIs are.
- **Python — prep only, never in the live loop.** Fine for pre-event prototyping (e.g. testing gap-seeking logic on recorded frames on a laptop). Do NOT put a laptop + Python in the live demo path — it adds a network hop and a failure point a 48h demo can't afford.
- **JavaScript — pitch visuals only.** Use it for the slide deck or a live "robot's-eye-view" dashboard mirrored to a laptop screen during the pitch. Never in the robot's critical path.
- **Bottom line:** Kotlin does the thinking, C++ does the moving, Groq/ElevenLabs are cloud calls from Kotlin, JS is showbiz. Clean separation, minimum failure surface.

---

## 3. Full technology stack

| Layer | Technology | Owner |
|---|---|---|
| App shell | Android Studio, Kotlin, CameraX | CS |
| Depth (stretch) | ARCore Depth API | CS |
| Obstacle detection (core) | Ultrasonic (HC-SR04) + ToF (VL53L1X) via ESP32 | EE |
| Voice input | Android `SpeechRecognizer` | CS |
| Conversation | Groq API (Llama-class model) | CS |
| Voice output | ElevenLabs API | CS |
| Sign reading | Google ML Kit Text Recognition | CS |
| Navigation | GPS (`FusedLocationProvider`) + compass + OpenRouteService/Google Routes | CS |
| Comms | BLE (Android BluetoothLeGatt ↔ ESP32 NimBLE) | CS + EE |
| Motor control | ESP32 + motor driver + firmware | EE |
| Safety logic | ESP32 firmware (auto-stop on drop-off / BLE loss) | EE |
| Pitch deck / dashboard | HTML/JS (laptop) | CS + whole team |

---

## 4. Parts list (exact models)

### Have already
- Samsung Galaxy S21 (robot brain)
- ESP32 dev board(s)

### Electronics to source
| Part | Model / spec | Qty | Notes |
|---|---|---|---|
| Microcontroller | ESP32 (any dev board, WROOM-32 or S3) | 1 | Have it |
| Motor driver | L298N or TB6612FNG H-bridge | 1 | TB6612 is more efficient; L298N is fine |
| Drive motors | 6V–12V TT gear motors (yellow hobby) | 2 | Cheap, plenty of torque |
| Wheels | Fit TT motor shaft | 2 | + 1 caster wheel |
| Caster | Ball caster / swivel | 1 | Third contact point |
| Ultrasonic sensor | HC-SR04 | 2–3 | Forward + angled L/R |
| ToF sensor (drop-off) | VL53L1X | 1 | Front edge, faces DOWN — catches curbs/stairs |
| Vibration motor (stretch) | Coin/ERM 3V | 1 | On leash handle |
| Battery | 2S/3S LiPo or 6×AA holder | 1 | Motors + ESP32; keep phone on its own battery |
| Buck converter | 12V→5V step-down | 1 | If powering ESP32 from motor battery |
| Breadboard + jumpers | — | — | Assembly at event |
| Phone mount | 3D-printed bracket | 1 | Faces forward, knee height |

### Chassis (3D print BEFORE event — printing allowed, assembly not)
- Base plate with motor mounts + caster mount
- Phone bracket
- Handle/leash anchor point
- Print, label, and bag all parts before arrival. Assembly happens at the event.

### Human/demo
- Data-carrying USB cable (test it — charge-only cables are the #1 setup killer)
- Laptop with Android Studio installed (prep day)
- Leash / rope
- Blindfold
- Cones or props to mark the demo course

---

## 5. Architecture (subsystem map)

```
                    Samsung S21 (Kotlin app)
                            │
     ┌──────────┬──────────┼──────────┬───────────┐
   Voice in   Groq LLM   ML Kit     GPS/route   BLE out
 (Speech-      (convo)    (OCR)    (directing)     │
  Recognizer)    │          │          │           ▼
     │           └── ElevenLabs ───────┘        ESP32 (C++)
     │              (voice out)                     │
     └──────────────────────────────────┐   ┌───────┴────────┐
                                         │  Motors        Sensors
                                    (confirm back)  (drive)  (HC-SR04 + VL53L1X)
                                                              │
                                                        Safety cutoff
                                                     (drop-off / BLE loss)
```

- **Sensors → ESP32 → BLE → phone:** obstacle + drop-off data flows UP.
- **Phone → BLE → ESP32 → motors:** steer/stop commands flow DOWN.
- **Safety is on the ESP32, not the phone:** if BLE drops or the ToF sees a drop-off, the ESP32 cuts motors in <500ms on its own. Never let the phone be the only thing standing between a blindfolded person and a staircase.

---

## 6. Human vs Claude split

**Claude (Pro / Claude Code) writes:** all Kotlin (app, CameraX, ARCore, BLE, STT wiring, Groq calls, ElevenLabs calls, ML Kit OCR, GPS/routing, UI) and all ESP32 C++ firmware.

**Humans only (Claude cannot do):**
- All physical build: soldering, wiring, chassis assembly, phone mounting — **EE**
- Running the app on the S21 (USB, dev mode, hit Run) — **CS**
- Creating accounts + pasting API keys (Groq, ElevenLabs, Google Maps) — **CS**
- Real-world testing: walking the robot, reporting what it actually did — **all**
- Reading errors from Logcat and feeding them to Claude — **CS**
- Pitch delivery — **one designated speaker**

**The core loop for CS:** Claude writes Kotlin → paste into Android Studio → Run → test on S21 → copy Logcat error → paste to Claude → fix → Run. The Logcat-to-Claude loop is how new-to-mobile devs move fast.

---

## 7. 48-hour timeline

Roles: **EE1, EE2** (hardware/firmware) · **CS1, CS2** (app/integration). Sleep is scheduled — a wrecked team pitches badly.

### Phase 0 — Hours 0–2 · Setup & alignment (everyone)
- Team: agree on the demo script FIRST (what exactly will the judges see?). Build backward from it.
- EE1/EE2: unpack, lay out parts, verify 3D-printed chassis fits, start assembling base + motors.
- CS1: create Android Studio project, confirm S21 runs a "hello world" build (should be pre-done — verify).
- CS2: create Groq, ElevenLabs, Google Maps accounts; paste keys into a `secrets` file.

### Phase 1 — Hours 2–10 · Parallel foundations
- **EE:** wire motors + driver + ESP32. Get motors spinning from firmware. Then wire HC-SR04 + VL53L1X, print sensor readings over serial.
- **CS1:** BLE — get phone talking to ESP32 (send "hello", blink an LED). This is the spine; do it early.
- **CS2:** Voice loop — SpeechRecognizer → Groq → ElevenLabs. Get the phone to *converse* out loud. This is the star feature; get it working first.
- **Milestone @ H10:** motors move on command; phone holds a spoken conversation; BLE link proven.

### Phase 2 — Hours 10–20 · Integration (sleep rotation begins)
- **EE:** mount everything on chassis. Firmware: sensor-triggered auto-stop + BLE-loss cutoff. Test the drop-off sensor on a real table edge.
- **CS1:** wire voice commands → BLE (say "stop" → motors stop; "go" → motors go). Voice now drives the robot.
- **CS2:** ML Kit OCR — point camera at a sign, speak the text aloud.
- **Sleep:** half the team sleeps H14–H20, other half H20–H26. Never all-nighter the whole team.
- **Milestone @ H20:** robot moves under voice control, stops for obstacles, reads a sign aloud.

### Phase 3 — Hours 20–32 · Make it a guide dog
- **EE:** add vibration motor to handle (stretch); tidy wiring so it survives a demo walk; battery test.
- **CS1:** GPS + routing — "take me to X" produces turn-by-turn voice cues.
- **CS2:** the conversational polish — give Groq a system prompt so it acts like a warm, helpful guide dog assistant with awareness of sensor state ("there's an obstacle to your left").
- **CS1 (once GPS is stable):** scaffold the ARCore depth session and render a live depth map on screen — on its own branch, no navigation wiring yet. Getting it scaffolded now means the H32–40 integration is a bolt-on, not a from-scratch scramble.
- **Milestone @ H32:** full end-to-end run: speak a destination → robot leads → talks → stops at hazards → reads a sign.

### Phase 4 — Hours 32–40 · Stretch + film
- ARCore depth → gap-seeking **integration** (session was scaffolded in Phase 3; wire depth into steering ONLY if core is rock-solid) — CS1.
- **Film the hero demo NOW**, while everything works, in good light, blindfolded teammate on the leash. Do NOT wait for the live demo — filmed footage is your insurance.
- EE: build a second battery/charge plan so the robot is fully juiced for judging.

### Phase 5 — Hours 40–46 · Pitch build (whole team)
- Build the deck (Section 9). Write the script. Rehearse **3+ times, timed.**
- Prepare the live demo course with cones. Have the filmed backup ready to cut to if live fails.
- Freeze code at H44. No new features after this — only bug fixes. Discipline wins.

### Phase 6 — Hours 46–48 · Rest & final rehearsal
- Sleep or rest. Charge everything. One final timed rehearsal. Walk in calm.

---

## 8. Risk & fallback table

| Risk | Likelihood | Fallback |
|---|---|---|
| ARCore depth eats time | HIGH | It's already a stretch — cut it, sensors carry nav |
| BLE flaky | MED | Simplify to fewer, larger packets; keep phone near ESP32 |
| Live demo robot misbehaves | MED | **Cut to pre-filmed footage** (filmed at H32–40) |
| Venue Wi-Fi drops (LLM needs it) | LOW (confirmed reliable) | Phone hotspot as backup; pre-record one convo clip |
| Motor battery dies mid-judging | MED | Second charged battery on standby |
| Charge-only USB cable | MED | Test cables on prep day; bring 2 known-good |
| Team burnout | HIGH | Enforced sleep rotation; code freeze at H44 |

---

## 9. The pitch (this wins or loses it)

**Structure (aim ~3 min + demo):**
1. **Hook (20s):** the human problem. A real guide dog costs ~$50,000 and has multi-year waitlists; 85–90% of visually impaired people live where that's unthinkable. Say it looking at the judges, not the slides.
2. **The gap (20s):** existing smart canes just warn you. They don't *lead*, don't *talk*, don't *read the world*. Ours does.
3. **Live demo / film (90s):** blindfolded teammate says "take me to the exit." Robot leads on the leash, talks them through it, stops at a hazard, reads a sign aloud. Let the moment breathe — the ElevenLabs voice carries the emotion.
4. **How (30s):** one clean architecture slide (use the subsystem map). Emphasize: runs on a phone people already own, ~1/50th the cost, on-device where it counts.
5. **Close (20s):** the vision — accessible mobility for the 253 million people who can't afford a guide dog. End on the cost contrast. Land it.

**Pitch rules:**
- One speaker leads; others run the demo silently.
- Never apologize for what it doesn't do. Show what it does, confidently.
- The filmed demo is insurance — cut to it the instant live flakes, don't fight a broken robot on stage.
- Rehearse timed. Over-time pitches get cut off and it reads as unprepared.

---

## 10. Claude Code kickoff order

Build in this exact order — each proves the spine before adding limbs:
1. ESP32 firmware: motors move over serial.
2. ESP32 + BLE: phone toggles an LED.
3. Kotlin: SpeechRecognizer → Groq → ElevenLabs (spoken conversation).
4. Kotlin: voice command "stop"/"go" → BLE → motors.
5. ESP32: sensor auto-stop + drop-off cutoff.
6. Kotlin: **scaffold the ARCore session + Depth API** — render a live depth map on screen, no navigation yet. Build it on its own branch/toggle so it can't destabilize core.
7. Kotlin: ML Kit OCR reads a sign aloud.
8. Kotlin: GPS + routing → voice turn cues.
9. Groq system prompt: warm guide-dog persona with sensor awareness.
10. Kotlin: feed the depth map into gap-seeking, blended with sensor data (stretch — stays behind the sensor fallback).
11. (Stretch) handle haptics + sprung-leash tuning.

Give Claude Code this file, then say: *"Start with step 1 — write the ESP32 firmware to drive two TT motors via an L298N. Here are my pins: [fill in]."*

---

## 11. Technical spec (for Claude Code — the code-ready layer)

This section exists so Claude Code can write correct code with minimal back-and-forth. `[FILL IN]` = a value only the team knows; Claude Code should ask for these, not guess. Anything marked *(verify current)* may have changed since this was written — Claude Code should confirm current API details at build time.

### 11.1 ESP32 firmware
- Board: `[FILL IN — e.g. ESP32-WROOM-32 devkit / XIAO ESP32-S3]`
- Motor driver: `[FILL IN — L298N or TB6612FNG]`
- **Pin map (fill before step 1):**
  - Left motor: IN1 `[FILL IN]`, IN2 `[FILL IN]`, ENA/PWM `[FILL IN]`
  - Right motor: IN3 `[FILL IN]`, IN4 `[FILL IN]`, ENB/PWM `[FILL IN]`
  - HC-SR04 #1: TRIG `[FILL IN]`, ECHO `[FILL IN]` (use a voltage divider on ECHO → 3.3V)
  - HC-SR04 #2/#3: `[FILL IN]`
  - VL53L1X (drop-off): I²C SDA `[FILL IN]`, SCL `[FILL IN]`
  - Vibration motor (stretch): `[FILL IN]` (via transistor, not direct)
- BLE stack: NimBLE-Arduino.
- **Safety behavior (must be in firmware, not the phone):** cut motor PWM to 0 within 500ms if (a) BLE disconnects, (b) VL53L1X reads a drop-off (distance jumps beyond floor threshold), or (c) no forward-progress/stop command received. Ramp motor changes (leaky integrator) so motion is smooth, not jerky.

### 11.2 BLE protocol (phone ↔ ESP32)
- Roles: ESP32 = peripheral/server, phone = central/client.
- Service UUID: `[Claude Code: generate a fixed custom 128-bit UUID and reuse it both sides]`
- Command characteristic (phone→ESP32, write-without-response): compact fixed-size packet — suggested `{ int8 speed (-100..100), int8 turn (-100..100), uint8 mode, uint8 flags }`. Keep it ≤12 bytes.
- Telemetry characteristic (ESP32→phone, notify): `{ uint16 frontDist_mm, uint8 dropoffFlag, uint8 status }`.
- Write-without-response for lowest latency. Document the exact byte layout in a shared header comment used by both the Kotlin and C++ sides.

### 11.3 Android app
- Language: Kotlin. Min SDK: 26+ *(verify S21 target; S21 runs Android 11+)*. Use CameraX, not the deprecated Camera API.
- **Permissions:** CAMERA, RECORD_AUDIO, ACCESS_FINE_LOCATION, BLUETOOTH_SCAN, BLUETOOTH_CONNECT (runtime-request all on Android 12+).
- **Key dependencies:** CameraX, ARCore (`com.google.ar:core`) *(verify current)*, ML Kit Text Recognition (`com.google.mlkit:text-recognition`) *(verify current)*, a BLE library or raw `android.bluetooth.le`, Retrofit/OkHttp for API calls.
- Architecture: one central coordinator class (holds state, owns the BLE link) + separate managers per subsystem (Voice, Convo, OCR, Nav, BLE, Depth). Mirrors the subsystem map in Section 5.
- Depth on its own module/branch behind a runtime toggle, so it can be disabled without touching core paths.

### 11.4 External APIs
- **Groq:** chat-completions endpoint; model `[Claude Code: confirm a current fast Groq-hosted model at build time]`. Send conversation history + a system prompt (the guide-dog persona) + current sensor state injected as context. Key from `secrets`.
- **ElevenLabs:** text-to-speech endpoint; pick a warm voice ID. Stream audio back and play it. Key from `secrets`.
- **Routing:** Google Routes API *or* OpenRouteService — pedestrian/walking mode; returns polyline + step instructions. Key from `secrets`.
- **STT:** Android on-device `SpeechRecognizer` — no key, no network needed.
- Never hardcode keys; load from a gitignored `secrets` file / `local.properties`.

### 11.5 Per-step acceptance criteria (definition of "done")
1. Motors: both wheels drive forward/back/turn from serial commands; smooth ramp.
2. BLE LED: phone connects and toggles the ESP32 LED reliably.
3. Conversation: speak → transcript → Groq reply → ElevenLabs speaks it, under ~2s round-trip.
4. Voice→motor: "stop" halts within 500ms; "go" resumes.
5. Safety: table-edge test — robot stops at a real drop-off; BLE unplug → motors cut.
6. Depth scaffold: a live depth map renders on the S21 screen.
7. OCR: point at a printed sign → correct text spoken aloud.
8. Nav: "take me to [place]" → spoken turn-by-turn cues that update with GPS.
9. Persona: replies sound like a calm guide-dog assistant and reference sensor state.
10. Depth integration: depth widens obstacle avoidance vs sensors alone (stretch).
11. Haptic/leash (stretch): handle buzzes on turns; sprung leash smooths the tug.

### 11.6 How to drive the build with Claude Code
- Work one numbered step at a time (Section 10). Don't ask for everything at once.
- After each step: run it, test on the S21, paste any Logcat/serial error back to Claude Code, iterate until the acceptance criterion passes, then move on.
- Keep depth and haptics on separate branches so they never block the core path.
