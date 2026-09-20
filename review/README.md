# review/ — integration review of the voice/LLM side against the ESP32 side

Nothing here is production code. `review/` is the only permanent addition; the JVM harnesses are
copied into `app/src/test/java/com/example/guidedogtest/review/` to run and deleted afterwards (their
headers carry the copy/run/delete sequence), and the one instrumented harness is copied into
`app/src/androidTest/java/com/example/guidedogtest/` the same way — it needs a phone and a live route
call, so its log is the artifact that stays.

The badge's own firmware (`remote/badge_controller/`, which drives this car over ESP-NOW) is checked
the same way rather than by assertion: `review/harness/badge_check.py` reads both firmwares' wire
format field by field and renders the badge's font back from its own bytes, and
`review/logs/badge.log` records that plus the three builds it was written against.

## Cross-examination of the other branches

Four lines of work were compared: this one (the review's fixes), `origin/integration` (`a2dbb55`),
`origin/mahathir` (`f2bde65`) and `origin/eleven-lab-fixes` (`15ead6d`, plus its parent `c36c3d9`).
Taken:

| from | what | why it was correct on this base |
|---|---|---|
| `eleven-lab-fixes` `c36c3d9` | one spoken stop acknowledgement instead of two | `halt()`'s emergency announcement and `ConversationManager`'s own ack were both firing; `EMERGENCY_STOP` priority meant each interrupted the other, so a stop sounded like a failed utterance |
| `eleven-lab-fixes` `15ead6d` | `wakeSent` per-utterance guard; transmit loop slows to 5 Hz while listening; heartbeat every tick; frame logging | the trailing `onResults` after `stopListening()` re-fired the wake word; and a 20 Hz stream plus the serial reader starves the recognizer on this phone, so the wake word landed before the user could speak |
| `mahathir` `f2bde65` | inline wake-word commands ("Goose, stop"); destination fast path ("take me to X" without the model); 650 ms partial debounce; wake-word `isActive` guard; `Backward` command refused with a spoken reason; `ObstacleAlertManager` announces distance-less detections above 0.65 confidence and is null-safe; obstacle alerts on by default; "Turning left/right." wording | each is a defect or a latency win independent of the controller design |

Rejected, with reasons:

| from | what | why not |
|---|---|---|
| `integration` `a2dbb55` | `CREEP` state and opening creep | built on the old constant-based controller; on this base a 75-PWM creep is raised to the 110 floor by `wheels()`, so it is not a creep at all |
| `integration` `a2dbb55` | `forwardToward()` lean toward the more open side | superseded: `chooseCorridor` already scores candidate corridors by width and clearance, with a deadband and switch hysteresis, and the route bias rides on top |
| `integration` `a2dbb55` | opening the camera view arms avoidance and drives | unsafe: tapping a viewer must never command motion; the explicit AUTO / START FOLLOWING gesture is kept |
| `mahathir` `f2bde65` | `AvoidancePhase` state machine and its "Path clear. Continuing." / "Moving forward." speech | a redesign of the safety path built on the superseded constants; the corridor latch and hysteresis already carry the intent, and it cannot be validated without the robot |
| `mahathir` `f2bde65` | `returnToWakeWordListening()` after a *destination confirmation* and after a *model reply* | kept as a follow-up window on purpose: "yes" → route summary → "go" is one conversation, and an answer should not need "goose" again. The change was taken for *command* acks only, which are the case where an open microphone buys nothing |

## Fixes carried in this tree

Every log below was captured against the fixed tree.

| seam | before | now |
|---|---|---|
| command down-link | every branch of the autonomous loop stored `Drive.STOP_FRAME`, so the follower's tuned pairs never reached the wire | `DriveArbiter.resolve` decides, in one place: a refusal from the depth controller stops the car (whether it is unsafe *or* blind), a pivot it needs is its own tuned pair, and otherwise the driving pair goes out with the controller's corridor steering laid on as a bias |
| telemetry / sensor state | the model was fed a constant `SensorSnapshot` | the depth scene reaches the model through `SceneSnapshot.toSensorSnapshot`, with `hazardsKnown=false` when there is no depth frame |
| obstacle sensing lost | silent stop, screen still said "following" | a spoken warning plus an on-screen line, once per walk |
| yes/no confirmation | a refusal containing "right" was read as agreement | denial is tested first |
| stale stop matcher | `containsStopWord` pinned by a test, called by nothing | deleted, with its assertions |
| firmware | deadman armed only after the first `h`; unbounded message buffers | armed at boot (750 ms); both buffers bounded |
| hallway steering | the corridor was the nine-column grid's free run, so one column of asymmetry on a flat wall produced a steering bias and the robot drifted towards the wider side | the walls are the centre line: the robot holds the middle of a corridor and ignores a difference smaller than a fifth of the hallway's width (capped at 0.45 m), with a bounded correction past that |
| obstacle detection | a nine-column by four-row occupancy grid, plus a corridor search over its free runs, decided the drive - and the floor filled its lower cells | three boxes (left / centre / right) judged against a fitted ground plane: floor is support and never an obstacle, a nearer reading is an obstacle, a far one is a drop. The grid, the corridor search and its scoring constants are gone |
| obstacle reaction time | the boxes were read **behind** the object detector and OCR on one thread, at 4/s after two throttles, and the frame waited for the next 20 Hz (or 200 ms, while listening) transmit tick | the boxes are read **before** the heavy work, outside its busy gate, from every depth frame (8/s, measured); a decision costs 0.06 ms; the frame is sent in the composition frame the decision changes in, with the heartbeat on a 5 Hz timer. Depth-frame copy 6-19 ms -> 0.35 ms; app CPU 344 % -> 220 % |
| who owns the wheels | the controller came second to the human's command, and its *silence* (switched off, still waking) was read as a stop | **one-way split**: the controller's stop beats everything, a route or a live command drives otherwise, and nobody driving leaves the controller driving. Both halves of that failed on the floor - a car that drove on with an obstacle in front, and a car that said "moving forward" while the wheels stayed still - so it is pinned in `DriveArbiterTest` |
| the decision itself | corridor offset plus scoring decided forward/slow/pivot | **the depth model is asked one question**: is something in the middle box? Something (yellow or red) -> **stop**; empty -> drive on the calibrated straight pair; nothing readable -> do what was asked. The side boxes are not consulted at all: "which side has room" is a second question to get right, and getting it wrong steers into a wall |
| turning | the middle box chose a side, held it, and the robot pivoted into it | **the obstacle layer never turns.** A turn is only ever asked for: a route pivot, or the follower's lean while it holds a bearing. Steering comes from the route, never from a box |
| drop handling | *any* box finding a hole stopped the car, announced as "Drop ahead"; then centre-only | **ignored entirely**: it false-fires on this floor (a glossy surface reads past the ground plane), so it stops nothing, closes no side, says nothing and is not told to the model. The plumbing stays for the ToF sensor |
| pivot strength | scaled every automatic turn to 0.6 of the tuned pair to soften it - and the robot stopped rotating altogether (the motor floor turns a free wheel, not the chassis) | `MotorTuning.PIVOT_FRACTION = 1.0`: the tuned pair, one constant, with the configure page left as the place to retune if it over-turns |
| a decision every frame | the boxes were re-decided 8x a second and any flicker took the wheel: forward, one red side frame, turn, forward again - "it barely gets to move" | a driving decision is held for 900 ms (a red *middle* box and every stop still act immediately), one unjudgeable frame is not losing sight (250 ms grace), and a route keeps walking and steering up to 45 degrees of bearing error instead of stopping to pivot at 25 |
| starting a walk with no depth | a stationary robot got no depth at all (depth-from-motion needs camera movement) and refused to move, so it could never get any - a deadlock | **do the action that was asked for and ignore the frames that read nothing**: go forward, turn, or lean on the tuned pair (open loop) until a box loads, then the obstacle logic takes over immediately. `AvoidanceDecision.obstacleSensingAvailable` reports it, so the panel and the voice say obstacle detection is off rather than pretending |
| depth acquisition order | the depth image was requested *after* the camera image (which can block on a buffer) on a frame that had already moved on - ARCore answers with a valid, empty image: `nonzero 0/14400`, "Depth API: active" | depth is acquired first, from the frame's own intrinsics, so `sampled 201, depth returned 201` |
| the drawn boxes | three tall boxes, mostly over pavement | the box is drawn to the **floor line** the analyzer reports, with the floor rows as a dim strip under it - the sampling still covers those rows (the ground plane and every step-down live there), the drawing no longer pretends they are detection area |
| spoken destination | the route fetch ran on `Dispatchers.Main.immediate` (the voice path's `viewModelScope.launch`) while `RoutesApi.fetchRoute` is a blocking `HttpURLConnection` POST, so the phone threw `NetworkOnMainThreadException` into the caller's `catch (e: Exception)` and every confirmed destination was answered "Sorry, I couldn't calculate the route" - and even a route that loaded only started on a second "go" | the dispatcher lives inside `fetchRoute` (`suspend` + `withContext(Dispatchers.IO)`), so no call site can forget it; the confirmation speaks the summary and then starts the walk through the app's own Go path; a repeat destination re-emits (`VoiceRoute.requestId`) and the tick loop is keyed on the follower, so a route swapped mid-walk is the one actually driven |

## Logs (review/logs/) and the exact command that produced each

| log | command |
|---|---|
| `android-unit-tests.log` | `./gradlew.bat :app:testDebugUnitTest --console=plain` |
| `firmware-compile.log` | `powershell -NoProfile -Command "& '.tools\arduino.cmd' compile --fqbn esp32:esp32:esp32s3:PartitionScheme=huge_app OpenBot\firmware\openbot"` |
| `protocol-conformance.log` | `python review/harness/protocol_conformance.py` |
| `telemetry-units.log` | `python review/harness/telemetry_check.py` |
| `route-follower-sim.log` | the JVM harness below (see its header for the copy/run/delete sequence) |
| `groq-contract.log` | `python review/harness/live_api_check.py` (ONE request per run) |
| `elevenlabs-contract.log` | `python review/harness/live_api_check.py --tts-only` (ONE request per run) |
| `confirmation-match.log` | `python review/harness/confirmation_match_check.py` |
| `stop-phrase.log` | `python review/harness/stop_phrase_check.py` |
| `middle-box-stop.log` | the JVM harness below (see its header for the copy/run/delete sequence) |
| `voice-route.log` | `python review/harness/voice_route_check.py` (ONE live `computeRoutes` request) |
| `route-threading.log` | `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.guidedogtest.RouteFetchThreadingTest --console=plain`, run twice on the attached SM-G991W: once before and once after the dispatcher moved inside `fetchRoute` |
| `badge-hardware.log` | the badge and the car on the bench over USB: the flash, the brown-out reset loop the badge started with and the reset reason that named it, the stable badge console session, and the car's Wi-Fi/BLE coexistence abort, its fix and its clean boot |
| `badge.log` | `python review/harness/badge_check.py`, plus the three firmware builds it is written against: `.tools\arduino.cmd compile` for the badge (`esp32c3`, `CDCOnBoot=cdc,FlashMode=dio,FlashFreq=80,FlashSize=4M,PartitionScheme=no_ota`) and for the car (`esp32s3`, `PartitionScheme=huge_app`) with and without `-DHAS_ESPNOW=0` |

`elevenlabs-sample.mp3` is the audio the one live TTS call returned (77,785 bytes, `ID3` header),
kept as proof the configured voice really serves MP3 on this account.

`phone-install.log` is not a harness output: it is the capture of the first build onto the actual
phone (Samsung SM-G991W), including the debug-keystore conflict that forced a reinstall, the
no-crash launch check, and the two screenshots next to it (`phone-controls-screen.png`,
`phone-configure-robot.png`).

## Harnesses (review/harness/)

| file | what it proves | command |
|---|---|---|
| `protocol_conformance.py` | every frame the app can write means the same thing to `openbot.ino`'s parser; the boot-armed deadman; the bounded receive buffer | `python review/harness/protocol_conformance.py` |
| `telemetry_check.py` | which telemetry frames this board can emit, the unit each consumer sees, and (from source) that the model is no longer fed a constant | `python review/harness/telemetry_check.py` |
| `RouteFollowerSimHarness.kt` | the real `RouteFollower` on the JVM, tick by tick, and what the real `DriveArbiter` puts on the wire | see the file header |
| `floor_check.py` | not a harness: a phone-side check. Reads the live depth lines off the S21 and says whether the floor is found from where the phone is mounted, with the fix for each of the four failure states (`--from-file` replays captured logs) |
| `MiddleBoxStopHarness.kt` | the real controller over a walk, frame by frame: an obstacle arriving, the frames flickering clear after it, the frames going unreadable because the robot is standing still, and the walker answering the stop | see the file header |
| `GroqParseHarness.kt` | the real private `GroqClient.parseReply` against realistic model output | see the file header |
| `live_api_check.py` | the live Groq and ElevenLabs contracts, one request each, with a real hazard in `sensor_state` | `python review/harness/live_api_check.py [--tts-only]` |
| `confirmation_match_check.py` | the yes/no matcher in `ConversationManager` against real refusals | `python review/harness/confirmation_match_check.py` |
| `stop_phrase_check.py` | which phrases the always-listening loop really stops on, and that the stale helper is gone | `python review/harness/stop_phrase_check.py` |
| `voice_route_check.py` | the three seams of the spoken-destination path, read out of the source: the dispatcher inside `fetchRoute` (and that no call site wraps it again), the order of route summary → wake word → Go, the per-request id and the tick-loop key; then ONE live `computeRoutes` request whose body, travel mode and field mask are transcribed from `RoutesApi.kt` | `python review/harness/voice_route_check.py` |
| `badge_check.py` | the badge controller and the car agree on every ESP-NOW constant and struct field (mirrored definitions, compared field by field), the packet layout byte by byte, the badge's font rendered back from its own bytes, the 74HC165 shift order and active-low polarity from the guide, the four drive buttons, the drive pairs against the app's tuned `MotorSettings`, and the peer-registration and single-deadman invariants | `python review/harness/badge_check.py` |

Every harness reads its source literals out of the repository (or calls the real Kotlin class), and
each carries a header comment naming the exact source lines it transcribes. Two of them read the
firmware's own defaults rather than a copy of them (`protocol_conformance.py` reads
`heartbeat_interval` out of the sketch; `telemetry_check.py` reads the app's wiring out of
`MainActivity.kt`/`OcrScreen.kt`), so a later change to either shows up as a failed check instead of a
stale log.

## Note on the live logs

`elevenlabs-contract.log` records the second of two single TTS requests: the first run was made to
confirm the voice serves audio, the second to expose the `content-type` header and the `ID3` bytes (a
case-sensitive header lookup in the harness hid it the first time). `groq-contract.log` was re-run
once after the sensor-state fix, with the current prompt and a real hazard in the request — one
request, no looping.
