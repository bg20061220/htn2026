# human-checklist.md — what the four of you must do, in order

Claude writes the code. This list is everything Claude **can't** do: the physical, account, setup, testing, and pitch tasks. Work top to bottom. Check each box before moving on.

**What the chassis purchase changed (B0C6XQVB58: 4WD kit, L298N, HC-SR04, SG90, battery box):**
- plan.md §4 (sourcing), §5 (3D-printed chassis) and §10 step 1 (firmware to drive the motors) are **done or dropped** — the kit supplies the body, and the ESP32 firmware is already written, compiled and flash-ready (`OpenBot/firmware/openbot/openbot.ino`, configured for ESP32 + L298N + 4 motors).
- plan.md §11.1/§11.2 (custom firmware + binary BLE packet protocol) are **superseded**. We use the firmware's ASCII protocol — `c<left>,<right>` frames — because it is already running and you can debug it by hand in a serial monitor.
- You therefore start at the **BLE smoke test** (Hours 2–10), not at firmware.

*Claude writes the test harnesses and the signed-off commands; humans do wiring, flashing, accounts, testing, pitch.*

---

## PART A — Before the hackathon (prep day/week)

Doing this before the clock starts saves ~4 hours and a lot of panic. **The build pipeline must be proven before you walk in.**

### A1 — Laptop & app pipeline (CS1)
- [ ] Install **Android Studio** on the build laptop and let it finish downloading the **Android SDK + Gradle** (big — never do it at the event).
- [ ] Check the SDK path is real: `ANDROID_HOME` (or `sdk.dir` in the project's `local.properties`) must point at a folder that contains `platforms/` and `build-tools/`. **This laptop was broken here**: the variable pointed at `C:\Users\aravm\AppData\Local\Android\Sdk`, which did not exist. It now does (Android command-line tools + `platforms;android-34` + `build-tools;34.0.0` + `platform-tools` are installed there), and JDK 17 lives at `C:\Program Files\Java\jdk-17`. Only **Android Studio itself is still missing.**
- [ ] Create a blank Android project, hit Run, and confirm it appears on the S21. This proves the pipeline — do not skip it because "it worked last week".
- [ ] Confirm the S21 is on Google's **ARCore supported devices** list (it is) and install/update **Google Play Services for AR** from the Play Store.
- [ ] (Depth stretch only) Run Google's free ARCore / Scene Viewer demo on the S21 to confirm motion tracking + depth work on your unit.

### A2 — Phone in developer mode (CS1)
- [ ] On the S21: Settings → About phone → Software information → tap **Build number ×7**.
- [ ] Settings → Developer options → turn on **USB debugging**.
- [ ] Plug the S21 into the laptop with a **data-carrying USB cable** (test it — many cables are charge-only).
- [ ] Approve the "Allow USB debugging?" popup, check "Always allow."
- [ ] Confirm the S21 shows up by name in Android Studio's device dropdown.
- [ ] (Windows only) If the phone isn't detected, install the Samsung USB driver.
- [ ] Bring **2 known-good data cables** to the event.

### A3 — Accounts & API keys (CS2)
- [ ] Create a **Groq** account, generate an API key.
- [ ] Create an **ElevenLabs** account, pick a voice, copy its **voice ID**, generate an API key.
- [ ] Create a **Google Maps Platform** account and enable **two** APIs on the key: **Routes API** (routing) and **Maps SDK for Android** (the LIVE MAP screen) — or a free **OpenRouteService** key for routing only.
- [ ] Paste the key into `local.properties` as `MAPS_API_KEY=...` (gitignored) and rebuild — the route feature reads it from `BuildConfig`, the map from the manifest.
- [ ] Keep all keys in one place the team can reach; they will be pasted into the app project's gitignored `local.properties` at the event (never committed, never hardcoded).
- [ ] Confirm each key works with a quick test call (ask Claude for a curl command per service).

### A4 — Hardware (EE1 + EE2)
- [ ] Assemble the **4WD kit** as the kit intends (acrylic plates, 4 motors, 4 wheels, battery box) — but **do not fit the Arduino/Uno** it came with. The ESP32 replaces it.
- [ ] Confirm the ESP32 flashes: `.tools\arduino.cmd board list` for the port, then `.tools\flash.cmd COM5`.
- [ ] Collect: ESP32 dev board, HC-SR04 (one is enough), VL53L1X (drop-off sensor), phone mount (clamp / velcro / zip ties), leash + anchor point, power bank for the phone.
- [ ] Charge every battery. Bring a spare for the car.

### A5 — Mounting (EE1 + EE2)
- [ ] Mount the phone on the top plate: rigid, **flat (screen up) in landscape**, so one of its long edges points down the road. Which one is the robot's forward direction is fixed by the single `ROBOT_HEADING_OFFSET_DEGREES` constant in `Heading.kt` — `+90` for the right edge, `-90` for the left — and confirmed with the Compass readout on the Configure Robot page.
- [ ] Add the leash anchor point — it has to take the pull of a blindfolded walker.
- [ ] Dry-fit everything, then photograph the assembled robot so re-assembly at the event is fast.

### A6 — Demo & pitch prep (whole team)
- [ ] Agree on the exact demo script: what will judges literally see, step by step?
- [ ] Pack: leash/rope, blindfold, cones/props, chargers, power strip, tape, zip ties, multi-tool.
- [ ] Assign the **pitch speaker** now so they can start thinking about delivery.
- [ ] Note the ARCore depth overlay as a *potential* pitch visual — show it only if the depth stretch lands.

---

## PART B — During the hackathon (48 hours)

### Hours 0–2 · Setup (everyone)
- [ ] Re-confirm the demo script as a team before touching anything.
- [ ] CS1: re-prove the S21 build pipeline on-site (blank app → Run).
- [ ] CS2: paste API keys into the app project's `local.properties` and re-sync Gradle.
- [ ] EE: lay out parts, continue chassis assembly.

### Hours 2–10 · Bring-up (the spine, in this order)
- [ ] **EE — wire L298N → ESP32** (see *Hardware reference* below). Keep the ENA/ENB jumpers in place.
- [ ] **EE — prove the motors**: wheels off, `.tools\arduino.cmd monitor -p COM5 -c baudrate=115200`, send `c128,128`, `c0,0`, `c128,-128`. Swap a side's two motor leads if that side drives backwards.
- [ ] **CS1 — BLE smoke test, before writing any app code**: install the prebuilt **OpenBot robot app** on the S21 (skip any sign-in), connect to `OpenBot: DIY_ESP32`, drive with the FreeRoam sliders. This is a bring-up tool only — it is *not* the app you demo. Killing it must stop the car: that proves the ESP32-side dea-man switch.
- [ ] **CS2 — conversation loop**: talk → SpeechRecognizer → Groq → ElevenLabs speaks back, under ~2 s round trip. Pure app work, no robot needed.
- [ ] **Checkpoint:** motors obey the serial monitor, the phone drives the car over BLE, the phone holds a spoken conversation.

### Hours 10–20 · Integration (start sleep rotation)
- [ ] **CS1 — your own app drives the car**: BLE connect, on-screen drive buttons. The shipped loop sends `c<left>,<right>` at 20 Hz, drops to 5 Hz while the assistant has the microphone (a 20 Hz stream plus the serial reader starves the on-device recognizer on this phone), and sends `h500` on **every** tick, so the firmware's 500 ms cutoff holds at either rate. The heartbeat *is* the BLE-loss cutoff — no heartbeat, no motion.
- [ ] **CS1 — voice → motion**: say "goose, go forward" / "goose, turn left" / "goose, stop" / "goose, take me to the library". "go forward" and the turns drive under the depth layer, so they need the camera view (which they open themselves); "stop" must halt the car in under half a second and be spoken **once**; "backward" is refused out loud, because nothing measures behind the robot.
- [ ] **EE — wire the HC-SR04** (TRIG GPIO5, ECHO GPIO18 through a 1k/2k divider to 3.3 V), set `HAS_SONAR 1` in `firmware/openbot/openbot.ino`, reflash. The firmware now auto-stops at 10 cm.
- [ ] **EE — wire the VL53L1X** (3V3, SDA GPIO21, SCL GPIO22) and add the drop-off emergency stop to the firmware.
- [ ] **EE — the safety test that matters:** robot on a real table edge → it must stop. Then cut the BLE link mid-drive → motors must cut within 500 ms.
- [ ] **CS2 — ML Kit OCR**: camera reads a sign aloud.
- [ ] Sleep: half the team H14–H20, the other half H20–H26.
- [ ] **Checkpoint:** robot moves under voice, stops for obstacles and drop-offs, reads a sign.

### Hours 20–32 · Make it a guide dog
- [ ] **CS1 — GPS + routing**: "take me to X" produces spoken turn cues (FusedLocationProvider + Routes/OpenRouteService). Do this only after the core run works.
- [ ] **CS2 — persona**: tune the Groq system prompt into a warm, sensor-aware guide-dog assistant ("there's an obstacle ahead"), fed with live sonar/ToF state.
- [ ] **EE** — strap down every wire so it survives a walking demo; measure how long a full run lasts on one battery.
- [ ] **Checkpoint:** full run — speak destination → leads → talks → stops at hazards → reads signs.

### Hours 32–40 · Stretch + FILM
- [ ] **Film the hero demo now, while it works.** Good light, blindfolded teammate, leash, full run. That footage is your insurance.
- [ ] **EE** — second charged battery ready for judging.
- [ ] (Stretch) vibration motor on the handle — buzz on turns. Drop it if the core isn't rock-solid.

**ARCore depth layer (CS1 — stretch, only once core is rock-solid):**
- [ ] Enable the ARCore session + **Depth API** in the app.
- [ ] Confirm a live depth map renders on the S21 screen so you can *see* it working.
- [ ] Feed depth into gap-seeking: the three boxes now do this directly (left / centre / right, floor excluded), so this item is **done** — see *Three boxes* above.
- [ ] If depth is flaky or eats time, **turn it off and fall back to sensors** — it must never destabilize the core demo.

### Hours 40–46 · Pitch
- [ ] Build the deck (plan.md Section 9); write the script.
- [ ] Set up the live demo course with cones.
- [ ] Rehearse the pitch **3+ times, timed.**
- [ ] **Code freeze at H44** — bug fixes only, no new features.

### Hours 46–48 · Rest
- [ ] Charge everything (car battery, phone, laptop).
- [ ] One final timed rehearsal.
- [ ] Sleep / rest. Walk in calm.

---

## PART C — Demo & judging day

- [ ] Robot fully charged; phone fully charged; backup battery ready.
- [ ] Filmed demo loaded and cued on the laptop, ready to cut to instantly.
- [ ] Phone hotspot ready as Wi-Fi backup for the LLM.
- [ ] Cones/course set; leash and blindfold on hand.
- [ ] Pitch speaker knows the script cold; others know their silent demo roles.
- [ ] Deliver: hook → gap → demo → architecture → cost-contrast close.
- [ ] If the live robot flakes: **cut to the film immediately.** Don't fight it on stage.

---

## Hardware reference (as built)

**Board: Freenove ESP32-S3-WROOM (camera board).** This is an **ESP32-S3**, not a classic ESP32 — the
S3 has no GPIO22–GPIO25 at all, so the original ESP32 pin map could never have worked. The L298N is
wired the classic way (ENA/ENB = PWM speed, IN1–IN4 = direction) and the firmware has been changed to
drive exactly that: `ledcWrite` on the enable pins, `digitalWrite` on the direction pins. **Nothing
needs rewiring.**

**S3 pin limits, so nobody re-derives them at 3am:**

| Range | Why it is off limits |
|---|---|
| GPIO22–GPIO25 | do not exist on the S3 (the chip has GPIO0–21 and GPIO26–48) |
| GPIO26–GPIO37 | wired to the flash/PSRAM |
| GPIO19 / GPIO20 | native USB D− / D+ |
| GPIO43 / GPIO44 | UART0 to the on-board CH343 bridge |
| GPIO0, GPIO3, GPIO45, GPIO46 | strapping pins |
| GPIO2 | on-board LED |
| GPIO4–13, GPIO15–18 | the camera connector (free again if you unplug the camera module) |

That leaves GPIO1, GPIO14, GPIO21, GPIO38–GPIO42, GPIO47, GPIO48 — and the six motor wires use
GPIO1, GPIO14, GPIO21, GPIO41, GPIO42 and GPIO47.

**Wiring — L298N to ESP32-S3 (as built, do not change)**

| L298N | ESP32-S3 | what it does |
|---|---|---|
| ENA | **GPIO14** | left speed (PWM) |
| IN1 | **GPIO21** | left direction |
| IN2 | **GPIO47** | left direction |
| ENB | **GPIO1** | right speed (PWM) |
| IN3 | **GPIO42** | right direction |
| IN4 | **GPIO41** | right direction |
| OUT1 + OUT2 | the two left motors | |
| OUT3 + OUT4 | the two right motors | |
| +12V / GND | battery box | |
| 5V | **not connected to the ESP32** | the phone powers the board over USB |
| GND | **keep this wire** | ESP32 and L298N must share ground |

No jumpers on ENA/ENB — both are driven from the ESP32. Forward on the left channel = IN1 high,
IN2 low. The **right channel is inverted in firmware** (`RIGHT_MOTORS_INVERTED 1` in `openbot.ino`,
next to the L298N pins) because that pair's leads are mirrored on this chassis — electrically the
same as swapping the two leads on OUT3/OUT4. If you ever swap the leads in hardware, set that flag
to 0 so the two fixes don't stack.

Two motors on one L298N channel can run hot. If it does: unplug the rear pair (runs 2WD, no code change).

**Coasting vs braking:** `coast_mode` is 1, so a command of 0 releases the channel and the car
free-wheels. If you want it to hold position on a slope, set `coast_mode = 0` in `openbot.ino` — the
firmware then keeps both inputs high with the enable on, which brakes.

**No peripherals are fitted** — no sonar, ToF, encoders, LEDs or battery divider; all their flags are 0
and no pins are wired for them. Pins still free if that changes: GPIO38, GPIO39, GPIO40, GPIO48, plus
the whole 4–13 / 15–18 range if you unplug the camera module.

**Drive calibration — one place.** FORWARD/LEFT/RIGHT on the **Configure Robot** page are what both
*manual* and *automatic* driving use: the follower reads the same values every tick, so the straight
line it drives is the tuned FORWARD pair and the rotation it makes is the tuned LEFT/RIGHT pair for
that direction, and the depth-avoidance layer derives everything it sends from the same pair (its
cruise *is* the tuned FORWARD pair, its cautious state is that pair scaled down with the ratio kept,
its arcs are a transfer between the two sides, and its pivots are the tuned turn pairs). Change a
number and the next route frame uses it — no rebuild, no reflash.

Each command carries **both** wheel speeds, because this chassis runs crooked at equal values — the
right pair is weaker — so "forward" is a pair of numbers, not one, and a right turn is not the mirror
of a left one. The defaults are the values measured on this chassis: forward `180 / 128`, turns
`-205 / 190` and `205 / -195` (`MotorTuning.MANUAL_*`).

The only wheel constants left in code are those `MotorTuning.MANUAL_*` defaults and the
`MIN_EFFECTIVE_*` motor floors in `MotorSettings.kt`, plus the single avoidance shaping constant
described under *Camera view* below. Nothing else decides a PWM number.

The trim lives in the app, not the firmware, so raw-frame tools (the laptop teleop, the OpenBot app)
will still pull slightly to one side. That is expected: `c<left>,<right>` stays honest PWM.

**Right side ran backwards** for `c128,128`. Handled in the firmware, not by rewiring: the flag
`RIGHT_MOTORS_INVERTED 1` (next to the L298N pins in `openbot.ino`) inverts that channel's direction
levels, which is electrically the same as swapping the pair's leads on OUT3/OUT4. If you ever swap the
leads in hardware, set the flag to 0 so the two fixes don't stack — otherwise the right side inverts
twice and you are back to square one.

**Arduino IDE settings for the next flash:**

| Setting | Value | Why |
|---|---|---|
| Board | **`ESP32S3 Dev Module`** | it is an S3, not a classic ESP32 |
| ESP32 core | **2.0.17** | the sketch uses `ledcSetup`/`ledcAttachPin`, removed in core 3.x |
| Partition Scheme | **Huge APP (3MB No OTA/1MB SPIFFS)** | headroom for the sensors |
| USB CDC On Boot | **Disabled** (default) | keeps `Serial` on UART0 → the CH343 socket |
| Upload Speed | 921600, or 115200 if uploads fail | — |

The build is **1,023,905 bytes** = 32% of the 3 MB Huge-APP partition. Our `.tools\flash.cmd` already
uses `esp32:esp32:esp32s3:PartitionScheme=huge_app`; in the IDE you set the same by hand.

**Do not set `HAS_SONAR 1` before the HC-SR04 is physically wired.** An unconnected ECHO pin
floats and produces phantom distance readings, and the firmware stops the motors whenever the
distance reads under 10 cm — the car would twitch or refuse to move while you are testing the link.

Two motors on one L298N channel can run hot. If it does: unplug the rear pair (runs 2WD, no code change) or add a second L298N with one motor per channel (`OpenBot/firmware/openbot/openbot.ino:486-488`).

**Phone ↔ ESP32: one USB-C to USB-C cable, carrying power and data.** The phone is the USB host;
the ESP32 is powered from the phone's port and appears as a serial device at **115200 baud**.

- **Use the UART socket** (the one next to the CH343 bridge), not the native-USB socket: with
  `USB CDC On Boot` left disabled, the firmware's `Serial` is UART0, which is what the CH343 carries.
  The CH343 is a standard CDC/ACM device, so Android needs no driver for it. The other socket stays
  free for a laptop if you want to watch the serial monitor while the phone drives.
- **Remove the L298N 5V → ESP32 5V wire.** With the phone powering the board over USB, the L298N's
  own 5V regulator would be a second source on the same rail. Keep the GND wire.
- Android asks for USB permission the first time; tick "always allow". Plugging the cable in also
  offers to open the app (declared via `USB_DEVICE_ATTACHED` + `res/xml/device_filter.xml`).
- **The phone cannot charge while it is the host.** It is also feeding the ESP32, so a run is
  battery-limited. For a long demo day use a USB-C hub with **PD passthrough** (charge + host at
  once) or plan phone swaps; verify the hub actually does both before relying on it.
- The board's DTR/RTS lines go to its reset/boot circuit. The app drives both low (the "running"
  state) and never toggles them — that is why the board is not held in reset.

**Supported USB bridge chips** (listed in `app/src/main/res/xml/device_filter.xml` so Android offers
the app on plug-in): CP210x `0x10C4:0xEA60`, CH340 `0x1A86:0x7523`, CH9102 `0x1A86:0x55D4`,
**CH343 `0x1A86:0x55D3`** (this board), FTDI `0x0403:0x6001`/`0x6015`, Espressif native USB `0x303A`,
Arduino `0x2341`. The driver library detects CDC/ACM by interface type, so even an unlisted bridge
still works — you just tap CONNECT ROBOT instead of getting the auto-open prompt.

**Commands the firmware understands** (newline-terminated ASCII; USB serial at 115200, or BLE):

| Send | Meaning |
|---|---|
| `c<left>,<right>` | drive, each −255…255 (negative = reverse) |
| `h<ms>` | heartbeat — the motors stop if it is not refreshed within `<ms>` |
| `f` | report robot type + feature list |
| `w<ms>` / `v<ms>` / `s<ms>` | telemetry intervals: wheel rpm / battery / sonar |

The sketch arms that cutoff **at boot**, with a 750 ms default (`heartbeat_interval` in
`openbot.ino`), so an app that dies before it ever sends an `h` cannot leave the car rolling — the
motors stop 750 ms after the last command, no heartbeat required. This app then asks for 500 ms
(`h500`) and sends it on every tick of its transmit loop — 20 Hz while driving, 5 Hz while the
microphone is live — and the OpenBot app asks for 750 ms (`h750`, every 250 ms). All of them sit
inside the cutoff they asked for.

**Telemetry coming back:** `s<cm>` sonar · `w<rpmL>,<rpmR>` wheels · `v<volts>` battery · `b<id>` bumper · `f<type>:…` features · `r` on boot.

**BLE (fallback link, when the cable is unplugged):** advertises as `OpenBot: DIY_ESP32`; service `61653dc3-4021-4d1e-ba83-8b4eec61d613`; RX (phone → car, write-without-response) `06386c14-86ea-4d71-811c-48f97c58f8c9`; TX (car → phone, notify) `9bf1103b-834c-47cf-b149-c9e4bcf778a7`. The app uses USB when a device is attached and only scans BLE otherwise.

**Flash / debug / drive from a laptop:**
- `.tools\flash.cmd COM5` — build + upload the firmware (already passes `PartitionScheme=huge_app`)
- `.tools\arduino.cmd monitor -p COM5 -c baudrate=115200` — talk to the car by hand
- `python OpenBot\controller\esp32_teleop\teleop.py --ble` — drive it from a laptop over BLE
  (bring-up tool, never the demo path). `--port COM5` does the same over USB. Keys: `w/a/s/d`,
  space = stop, `x` = quit.

## Voice control (as built)

**Wake word: "goose".** The wake-word loop is the always-on listener: an on-device `SpeechRecognizer`
restarting in a cycle, scanning partial and final transcripts for the word. Two things it does that
are worth knowing before you debug it:

- **A command said in the same breath is acted on directly.** "Goose, stop" is one utterance: the
  text after the wake word goes straight to the command path, with no "Hi, how can I help?" in
  between and no second round of listening. A *partial* transcript carrying a complete local command
  is used after a 650 ms debounce, so a half-recognised word cannot fire a command; phrases with a
  place name wait for the final transcript, because place names truncate easily.
- **The wake word fires once per utterance.** `stopListening()` still delivers one trailing
  `onResults` for the same utterance, which used to fire the wake word a second time — resuming the
  wake loop exactly as the one-shot command recognizer started, so the two fought over the
  microphone and the command capture lost. Both a per-utterance flag and an `isActive` check stop it.

**Stop words need no wake word.** "stop", "halt", "cancel" and the fixed variants in
`EmergencyStopMatcher` stop the car the moment they are heard, from either recognizer, with no model
round trip and no network. The spoken acknowledgement comes from exactly **one** place (`halt()`'s
emergency announcement): a second ack used to interrupt the first, which sounded like Goose failing
to speak at all.

**Motion commands run under the depth layer.** "go forward" and "turn left/right" arm the camera view
and steer with the obstacle-avoidance layer rather than latching a wheel pair — the person saying them
cannot see the car, so the same sensing that guards a route guards a spoken command. If depth is not
live the car halts and says so (see *Camera view* above); the **Configure Robot** page's buttons remain
the unguarded bench path. **"backward" is refused** out loud: only the forward corridor is measured,
so a reverse command cannot be checked against anything.

**Destinations in fixed phrasings skip the model.** "take me to X", "go to X", "navigate to X",
"bring me to X", "walk to X" are extracted locally and resolved by Places — no round trip, works with
no data, and the confirmation ("Did you mean X? Say yes or no") is the same one the model path
produces. The model still handles everything else, and a refusal is never read as agreement.

**A confirmed destination is a walk, not a route on the map.** Saying yes loads the route *and starts
it*: the summary ("Okay, heading to the library…) is spoken, and the moment it finishes the app's own
Go runs — no second utterance, because the walker has already agreed to this destination. Two details
the order matters for:

- the microphone goes back to the wake word *before* the Go, because a plain announcement is dropped
  while a conversation turn is still open — and the one announcement that must never be dropped is
  "I'm not connected to the robot, so I can't walk there." Stopping needs no wake word, so closing the
  follow-up window here costs nothing.
- asking for the same place twice is a new walk. `MutableStateFlow` conflates equal values, so the
  route carries a per-request id; without it the second request would leave the finished follower in
  place and the robot would answer by standing still.

**After a command finishes, the microphone goes back to the wake word.** A completed command's ack
does not keep the one-shot recognizer open — that costs battery, CPU and the occasional mistaken
command. The exception is a destination *proposal*, which stays listening so "yes"/"no" can follow.

## Route following (Google Routes → motors)

**Setup (once):** create a Google Maps Platform key with the **Routes API** enabled, paste it into
`local.properties` as `MAPS_API_KEY=...`, and rebuild. The key is injected into `BuildConfig` at build
time; `local.properties` is gitignored, so it never lands in the repo. The app says
"add MAPS_API_KEY to local.properties" if it is missing.

**Using it:** type a destination in the box → **GET ROUTE** (WALK mode, from the current GPS fix) →
the screen shows the step count, total distance and the step being executed → **START FOLLOWING** hands
the motors to the follower. Any manual button (FORWARD/LEFT/STOP/RIGHT) or STOP FOLLOWING takes them
back: the follower's frame is dropped in the same tick the command is set, so there is no moment where
two things are driving, and STOP sends `c0,0` immediately rather than waiting for the next tick.

**The route fetch leaves the main thread inside `RoutesApi.fetchRoute`.** The call is a blocking
`HttpURLConnection` POST, so `fetchRoute` is `suspend` and switches itself to `Dispatchers.IO`. That is
deliberate: a caller that does not switch threads compiles fine and throws
`NetworkOnMainThreadException` on the phone, inside whatever `try` is around it — which is exactly how
the spoken-destination path used to answer every confirmed destination with "Sorry, I couldn't
calculate the route", while the typed-destination button, which happened to wrap the same call in
`withContext(Dispatchers.IO)`, worked. One place now owns the dispatcher, so no call site can forget
it (`review/harness/voice_route_check.py` fails if a manual wrapper comes back).

**Who is driving while a route is running:** the follower, unless the depth-avoidance layer objects.
One function decides it — `DriveArbiter.resolve` in `DriveArbiter.kt` — and the order is: a stop from
that layer (a drop, no corridor, or a depth feed that has gone quiet) stops the car outright; a pivot
it needs around an obstacle is its own tuned pair; otherwise the follower's frame goes out with the
controller's corridor steering laid on top of it as a bias, so the route keeps the direction and the
obstacle only bends the arc. Nothing else writes a wheel frame, and there is no mode to switch back:
an obstacle bends or interrupts the route, and the route resumes the moment the corridor is clear.

**How a route becomes motion:** each Routes step is "go this way for this far", and *this way* is a
bearing, not Google's maneuver word. The robot points itself at the step's `endLocation` — a closed
loop on the compass: if the heading is more than `alignStartDegrees` off, it stops and rotates in
place slowly until the error is inside `alignStopDegrees`, then drives at the step, steering on the
bearing error, until it is within the arrival radius. Two thresholds (not one) stop it chattering
between driving and turning, and the slow ramped pivot is what keeps a 45° turn from becoming a 60°
one.

The heading comes from the phone's **compass** (`HeadingSource`, rotation-vector sensor), not from the
GPS course: a receiver that is standing still reports no course at all, and the robot turns on the
spot. The phone is flat on the plate in landscape, so Android's compass — which reports the phone's
portrait top edge — is 90° off the robot's forward direction; `HeadingSource` turns it by the single
`ROBOT_HEADING_OFFSET_DEGREES` constant in `Heading.kt`, so everything downstream (route following,
the map arrow, the readouts) sees the robot's heading. Local magnetic declination is added from the
fix, so the compass reads true north like the GPS bearings do.

**Camera view and the automatic layer.** **CAMERA VIEW** on the controls screen opens the depth view;
the **AUTO AVOIDANCE** switch opens the same view and turns the driving layer on.

**Three boxes, and the floor is not an obstacle in any of them.** The depth frame is reduced to three
rectangles — left, centre, right, each a third of the region the camera is trusted over, drawn on the
preview with their state colour and the distance they found. The drawn box is the part **above the
floor line** (the analyzer reports where the floor ends, per frame); below it a thin, dim strip marks
the floor those rows are for. The floor rows are still *sampled* — they are what fits the ground
plane, and a step down or a low obstacle only ever shows up in the bottom rows — but they are never
an obstacle, and the drawing now says so. Before anything is classified, the bottom
rows of all three boxes are fitted with a **ground plane** (inverse depth against image row, which is
what a flat floor looks like in a perspective projection). Every sample is then judged against that
plane: matching it is *floor* and is support, **nearer than it is an obstacle**, and **farther than it
by 0.45 m is a drop**. That is the whole difference between a robot that walks down a corridor and one
that reports an obstacle at arm's length from a flat carpet, and it is why a box holding nothing but
floor reports no distance at all rather than a hazard.

**What the robot does about them — the rule, in the words we asked for it:**

| the boxes | the robot |
|---|---|
| centre **green** | drive on (cruising on the tuned FORWARD pair, leaning off a wall that is hard against one side, or holding the middle of a hallway) |
| centre **red**, a side **green or yellow** | turn in place towards that side, on the tuned LEFT/RIGHT pair, and **hold** that side while the middle stays red so a near-tie cannot rock it left and right |
| centre **yellow** | creep past: the cautious pair, steering off the nearer side |
| **all three red** | stop, and say *"Path blocked. Stopping."* |
| a **drop in the centre** box | stop, and say *"Drop ahead. Stopping."* |
| a **drop in a side** box | **keep going** — that side is closed off, exactly like a wall, and the car leans away from it. A hole beside the robot is not in its path, and stopping for it is how a drain cover ends a walk |

**The colours come from the floor, and the floor has to be inside ARCore's depth picture.** Measured
on the S21 holding the phone **upright** (so its rear camera points at the horizon): the depth image
held nothing but corridor — a column down its middle read 6.4, 6.4, 6.6 … 6.8 m top to bottom, no near
field at all, because ARCore's depth covers only the **middle ~74 % of the camera picture** (the
depth-image corners map to texture v 0.13–0.87). The floor, plainly visible along the bottom of the
preview, is *below* the measured region, and ARCore fills that strip with the far edge value. Result:
no floor model, every box grey, and the robot refusing to move — correctly, because a robot that
cannot see the floor in front of it cannot tell floor from stairwell.

**And depth needs motion — this is the one that will bite first.** The S21 has no ToF sensor, so
ARCore's Depth API is *depth-from-motion*: it estimates depth from the camera moving over time
(parallax). A stationary phone gets coarse, empty or degenerate depth, which is exactly what every
measurement above shows — all of them were taken with the phone sitting still, and the result was
either nothing at all (`nonzero 0/14400`) or a flat far-field slab (`6.4 … 6.8 m`, slope 0.00, no
floor at any row).

That creates a real deadlock to know about before the floor test: **the robot needs to move to see the
floor, and it needs to see the floor to move.** What that looks like in practice:

- Before refusing anything, the robot **swivels in place** to make the motion itself: it turns slowly
  one way, then the other, in 400 ms swings for up to 2.5 s. A pivot sweeps the camera along an arc —
  which is the parallax depth-from-motion needs — and it *advances* the robot into nothing it cannot
  see. It uses the tuned turn pair scaled down to 60 %, it will not swing towards a side whose raw
  distance reads nearer than 0.45 m, and it stays silent while doing it (this is the robot looking
  around, not avoiding anything).
- If that budget runs out with no floor model, it stops and the panel says why. **If the robot is
  standing still and grey on the floor test, watch the swivel: it should sweep, the plane should fit
  within a swing or two, and the boxes should colour in.** A hand or the leash on the handle does the
  same thing if the swivel is not enough.
- **Test this on the floor before anything else:** hold the robot still, watch `adb logcat -s Boxes`
  (the `depth not ready N` count in the `DepthCamera` line is this exact case), then push it slowly
  forward and watch the column profile change from a flat far slab to a real floor (something like
  `0.8, 1.1, 1.5, 2.0 …` down the image) and `fit ok` appear.

So the mounting rule is: **tilt the phone down until the floor sits in the middle of the preview, not
just along its bottom edge**, and expect to nudge the robot to get the first depth frame. On the robot (screen up, flat on the top plate) the rear camera already
looks down at the floor, which is the configuration the depth module wants. The panel says which of
the three states it is in — `TRACKED`, `NO DEPTH …` (ARCore returning empty frames) or `NO FLOOR IN THE
DEPTH IMAGE …` (aim) — and prints the raw distances either way, so "the camera sees 6.8 m of corridor"
can never be mistaken for "the camera sees nothing".

**It keeps moving: a decision is held for `DECISION_HOLD_MS` (900 ms).** The boxes flicker — ARCore's
depth shifts by centimetres frame to frame on a flat wall, and a person walking past changes a box for
one frame — and with a decision every 125 ms that flicker *was* the robot's behaviour: forward, a red
side box for one frame, turn, the turn changes the view, forward again, and it crossed the room a
hand's width at a time. Now:

- a **drive is held for 900 ms**: a flickering side box, a slowdown or a turn request waits, while the
  middle box stays green;
- a **red middle box still takes the wheel at once** — that is the thing pivoting is for;
- a **stop is never held back** (a drop, no safe path, or losing sight all act on the frame they arrive);
- **one unjudgeable frame does not count as losing sight** (`PLANE_LOSS_GRACE_MS` 250 ms): depth
  flickers, and reacting to a single empty frame was triggering the warm-up swivel mid-walk.
- and while following a route, the bearing error at which the car *stops to rotate* went from 25° to
  **`alignStartDegrees` 45°**: up to that it keeps walking and steers hard (1.2 PWM per degree, clamped
  at 60) instead of pivoting every time the compass or GPS heading wobbles.

**One knob for how hard it turns: `MotorTuning.PIVOT_FRACTION` (0.6).** Every *automatic* pivot — the
obstacle turn, a route step's turn, a spoken "turn left/right" and the warm-up swivel — uses that
share of the pair on the Configure Robot page (205/190 becomes 123/114). Raise it towards 1.0 for
sharper turns; **do not go below ~0.55**, because the pair is floored at the motor minimum and past
that point both wheels sit at 110, the tuned left/right asymmetry (which is what makes this chassis
rotate on the spot instead of curving) disappears, and the easing ramp stops showing. The manual
LEFT/RIGHT buttons on the page are deliberately *not* scaled — they are the bench test.

**A spoken turn lasts [VOICE_TURN_MS] 1.2 s**, then the robot goes back to driving on what the boxes
say — a turn is a moment, not a mode. Say "turn left" again to turn further. (Before this, the pivot
request sat in the intent until something else cleared it, so a clear middle never got to drive
again.)

Two cases the rule does not name, and what it does: a **yellow** middle with both sides red creeps
forward instead of stopping (something 1.5 m ahead is not a collision yet — the stop arrives as soon
as the middle turns red), and a **green** middle with a wall in one side box keeps driving while
leaning away from it. A route asking for a left/right lean is not an obstacle decision at all: the car
creeps and steers, which is what the follower got before the boxes existed.

**Speed — what the decision path actually costs, measured on the S21** (`adb logcat -s DepthCamera
Boxes Avoidance`):

| | before | now |
|---|---|---|
| boxes read | 4 /s (a 250 ms analysis throttle, on top of a 150 ms frame gate) | **8 /s** — every depth frame is read; throttled 0, failed 0 |
| depth frame copied out | 6–19 ms a frame, on the GL thread | **0.35 ms** (a row-at-a-time bulk read; the old loop re-computed a strided buffer offset per pixel) |
| one decision | — | **0.06 ms** |
| frame on the wire | waited for the next tick: up to 50 ms driving, **200 ms while the microphone was live** | sent in the same composition frame the decision changes in |
| app CPU, camera view open | ~344 % | **~220 %** |
| obstacle → motors | ~300 ms typical, ~600 ms worst | **~70–150 ms**, and what is left is ARCore's own depth frame interval (100–133 ms) |

The two structural fixes behind that: the boxes are read **before** the heavy vision work and outside
its busy gate (they used to run on the same single thread as the object detector and the text
recognizer, behind them, with every frame arriving in the meantime simply dropped), and the motor
frame is sent **when it changes** with the heartbeat on a 5 Hz timer behind it, instead of polling at
20 Hz and letting a decision wait for the next tick.

Three bench logs, one line a second each, are switched on in the camera view:
`DepthCamera` (depth frame rate, camera copy, depth copy), `Boxes` (how many times the boxes were
actually read, throttled, failed, and why the floor model did or did not fit) and `Avoidance` (each
box's state and distance, the decision, and the decide cost).

One constant shapes the cautious state, in `ObstacleAvoidanceController`:

| Constant | Default | What it does |
|---|---|---|
| `AUTO_SLOW_FRACTION` | 0.88 | how much of the tuned FORWARD pair the cautious state commands — scaled on *both* sides so the ratio, and therefore the straight line, survives |

Everything else it sends is derived from the Configure Robot page: the cruise *is* the tuned FORWARD
pair, the pivots *are* the tuned LEFT/RIGHT pairs, and an arc is `MotorTuning.steered` — a transfer of
PWM from the giving side to the far one, with each wheel floored at its `MIN_EFFECTIVE_*` so no wheel
is ever commanded into the deadband.

**Hallways are centred on the walls.** The middle of a hallway is where the two side distances are
equal, and that is a continuous measure — so a slightly uneven wall (a skirting board, a door frame,
depth noise on a flat surface) must not bend the path. The difference between the two sides is
therefore **ignored up to a deadband** — a fifth of the hallway's own width, capped at 0.45 m — before
any correction is asked for; past it, a bounded arc brings the robot back towards the middle. Both
sides more than 2 m away is a room, not a hallway, and one measurable side is not a centre line, so
neither produces a correction. A wall hard against *one* box while the other is open is not a hallway
either, but it still gets the minimum nudge away from it.

| Constant | Default | What it does |
|---|---|---|
| `CORRIDOR_SIDE_MAX_METERS` | 2.0 | both sides nearer than this = a hallway, not a room |
| `CORRIDOR_DEADBAND_FRACTION` / `_MIN_` / `_MAX_` | 0.20 / 0.20 m / 0.45 m | how uneven a hallway may be before the robot steers to correct |
| `CORRIDOR_CENTERING_PWM_PER_METER` | 45 | PWM of correction per metre off the middle, past the deadband |
| `SIDE_HOLD_MARGIN_METERS` | 0.20 | how much more room the other side needs before a held side is given up |

The analyzer's own thresholds (in `SceneAwarenessAnalyzer`) are worth knowing before tuning anything
by feel: `blockedBelowMeters` 0.8, `clearAboveMeters` 1.5, `GROUND_PLANE_TOLERANCE_METERS` 0.14,
`DROP_DEPTH_DELTA_METERS` 0.45, `DROP_CHECK_ROWS` 2 (a hole can only appear in the near rows — the
floor in front of the robot — so counting the whole box would dilute a step edge until it never
crossed the fraction).

The semantic detector (EfficientDet labels and their depth distances) does **not** drive: it draws the
boxes and speaks "Person ahead, 1.2 metres" — *Obstacle Voice Alerts* is **on by default**, and a
detection with no depth behind it is still announced (without a distance) once its confidence passes
0.65, because a label the walker cannot see is worth more than silence. Drive safety comes from box depth alone — that separation is deliberate, so a wrong label can never send the car anywhere.

**Live location screen:** **LIVE MAP** on the main screen opens a full-screen **Google map** that
follows the phone: a green arrow for the car rotated to its heading, a ring for the GPS accuracy, one
line of text (lat, long, ±accuracy, heading, speed) and BACK. It is deliberately bare — the phone is
strapped to the robot and nobody reads it while the robot walks, so this is the bring-up and pitch
view, not the user's interface. A drag hands the camera to whoever holds the phone; a tap gives it
back to the car. BACK returns to the controls — the robot link, the route and the follower all keep
running while the map is up.

The map comes from the **Maps SDK for Android**, so the key in `local.properties` needs **Maps SDK for
Android** enabled on it *as well as* **Routes API** — same key, two APIs. It is injected into the
manifest at build time (`manifestPlaceholders["mapsApiKey"]` → `com.google.android.geo.API_KEY`),
because that is where the SDK looks; `BuildConfig.MAPS_API_KEY` is only for the Routes HTTP call. A key
without Maps SDK enabled shows a grey map with the Google logo and an authorization failure in
logcat — check there first if the map ever comes up blank.

**What to calibrate on the floor.** The wheel values themselves are not here — they are on the
**Configure Robot** page, because the follower drives the tuned pairs (see *Drive calibration*
above). What is left in `RouteFollower.Config`
(`app/src/main/java/com/example/guidedogtest/RouteFollower.kt`) is how the follower *uses* them:

| Knob | Default | How to set it |
|---|---|---|
| `alignStartDegrees` | 25° | heading error that stops the car and starts a rotation — lower it if the car veers off the line, raise it if it keeps stopping |
| `alignStopDegrees` | 10° | error that ends the rotation; keep it well below `alignStartDegrees` or it chatters |
| `turnCreepFraction` | 0.75 | how much of the tuned turn is used at `alignStopDegrees` (full effort at `alignStartDegrees`). Raise towards 1.0 if the car stalls instead of creeping in the last few degrees |
| `arriveRadiusMeters` | 8 m | how close counts as "reached this step" — GPS is coarse, keep it generous |
| `steerGain` / `maxSteer` | 1.2 / 60 | the driving-phase correction; raise if it wanders, lower if it oscillates |
| `maxAccuracyMeters` | 30 m | the robot refuses to drive on a fix worse than this |

**Theme: the app is deliberately light-only.** `MainActivity` calls `GuideDogTestTheme(darkTheme =
false)` and paints a `Surface` from the scheme. The window theme is `android:Theme.Material.Light`
(a fixed light theme), so if Compose followed the phone into dark mode the two disagree: the dynamic
dark scheme's `onSurface` is white, and `OutlinedTextField` draws its text in `onSurface` — white
text on a white window, which is exactly what the destination field looked like (an empty box with a
cursor). Plain `Text` hid the problem because it uses `LocalContentColor`, which defaults to black.
To go dark properly, change the XML theme to a `DayNight` parent *and* let Compose follow the system.

**Rotation and the keyboard.** `configChanges="orientation|screenSize|screenLayout|…"` on the
activity keeps rotation from recreating it, so a running route, the robot link and a typed
destination all survive being turned. The controls column is scrollable, and it scrolls to the bottom
when the destination field takes focus — the window resize is the reliable trigger, because the
keyboard covers the field otherwise and `bringIntoView` did not move it.

**Configure Robot page:** **CONFIGURE ROBOT** on the main screen opens the manual drive rig — the
FORWARD/LEFT/RIGHT commands, each next to the two wheel speeds it will send, plus STOP. It is a page
rather than part of the controls screen because it is for the floor, not the demo: the phone is
mounted on the car and nobody reads it while the robot walks.

- Values are raw PWM, −255…255, one box per side, and are **saved as they are typed**
  (`MotorSettingsStore` → SharedPreferences), so a tuning session survives closing the app.
- **These are the values routes and the depth-avoidance layer drive with.** The follower reads them
  every tick, so the tuned FORWARD pair is the straight line a route drives and the tuned LEFT/RIGHT
  pair is the rotation it makes — a number changed mid-route applies on the next tick — and the
  avoidance layer derives its cruise, its cautious state and its pivots from the same pairs.
- A command **stays latched** until another is pressed, across pages: set the numbers while the car
  is rolling, press STOP when it is where you want it. The main screen keeps showing
  `Robot Command: …` so a latched command is never invisible.
- STOP has no values: it is both wheels released, and it stays that way.
- The page says **"Connect the robot to drive"** when there is no link, because the frames only reach
  the car through one — a button that changes the on-screen command but moves nothing looks broken.

**Check the compass before trusting it.** The heading maths is pinned by `HeadingTest`, so what is
left to check is the phone, and there are three traps:

1. **The mount is one constant, and it is the only assumption in the code:**
   `ROBOT_HEADING_OFFSET_DEGREES` in `Heading.kt` — `+90` if the phone's right edge points down the
   road, `-90` if its left edge does, `0` for a portrait mount. Raw readings stay raw
   (`rawHeadingDegrees`); the offset is applied once, in `robotHeading`, before anything compares a
   heading to a bearing. How to decide the sign on the physical robot is below.
2. **Calibrate the sign on the robot, once.** Open **CONFIGURE ROBOT** — the Compass block shows
   `raw phone heading`, `robot heading` and `mounting offset`. Point the robot's nose at a heading you
   know (a compass held against its side, or due north by any means) and read `robot heading`:

   | What you see | What it means |
   |---|---|
   | `robot heading` ≈ the heading you pointed at | sign is right, done |
   | `robot heading` ≈ 180° away from it | flip the sign: `+90` ↔ `-90`, rebuild |
   | `robot heading` ≈ 90° away from it | the phone is mounted portrait, not landscape — set `0` |

   Because the two landscape candidates are exactly 180° apart, this is a binary check: it can never
   be "sort of right". The `raw phone heading` on the same screen should sit 90° off `robot heading`
   in a landscape mount, which is the cross-check that the mount is what you think it is.

3. **Keep the phone away from magnets and motors.** A phone sitting next to a motor or a laptop read
   **618–727 µT** here (Earth is ~50 µT), and *any* compass jumps around in a field that dirty —
   ours went 261° → 128° as the disturbance settled. If the arrow wanders while the car is still,
   move the phone, don't recalibrate the code.

`HeadingSource` publishes a new value only when it moves by a degree, so a jittering sensor does not
recompose the screens 16 times a second.

**Safety behaviour built in:** it will not move without a GPS fix or on a fix worse than 30 m; it stops
if it has not closed the distance to the current target in 15 s; STOP FOLLOWING, any manual button,
leaving the screen or losing the link all send `c0,0`; and the firmware's heartbeat still cuts the
motors if the app dies.

**Test ladder:** bench — GET ROUTE, check the step list matches Google Maps, START FOLLOWING with the
wheels off the ground and watch the frames (the status line names the turn and the angle); car park —
a 50 m two-turn route, walking behind it; then the demo course. Do not go straight to a blindfolded
walk.

---

## The golden rules
1. Prove the pipeline before the event — SDK, cable, flash, BLE. Don't debug USB at hour 1.
2. Get the conversation working first — it's the star.
3. Safety cutoff lives on the ESP32, never only on the phone.
4. Film the demo while it works — footage is insurance.
5. Sleep in rotation. Freeze code at H44. A calm, rested pitch beats a broken feature.
