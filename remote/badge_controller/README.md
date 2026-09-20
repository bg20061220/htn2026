# Badge RC car controller

The 2026 Hacker Badge as an RC transmitter: four buttons drive the car, an ESP-NOW link carries them,
the badge's own screen and LEDs say what is going on. Built to `remote/custom-firmware-hal.md` — its
pin map (section 2), button protocol (section 3), screen notes (section 4), LED caution (section 6)
and console advice (section 9) are followed rather than reinterpreted.

```
 Up     hold to drive forward
 Left   hold to pivot left          Down   stop
 Right  hold to pivot right         Start  stop (the badge's dedicated button)
```

Hold-to-drive, not latch: releasing a direction sends STOP, and the car also stops on its own if the
badge stops talking (see *Pairing* below). Both stops are always available — `Down`, `Start`, the
phone app's STOP, and the car's own deadman.

## Files

| file | what it is |
|---|---|
| `badge_controller.ino` | the app: buttons → maneuver, ESP-NOW send/pair, screen, LEDs, console |
| `badge_hal.h` / `badge_hal.cpp` | the HAL: 74HC165 buttons, ST7789 display, WS2812 LEDs, accelerometer probe |
| `badge_pins.h` | the guide's pin map and every tunable in one place |
| `badge_proto.h` | the badge ↔ car wire format, over ESP-NOW |
| `badge_font.h` | the status-line font (generated once, verified by rendering its own bytes) |

Nothing here needs a library: the display driver, the LED driver and the font are in the sketch, and
the only dependencies are the ones that ship with the ESP32 core (`SPI`, `Wire`, `WiFi`, `esp_now`).

## Build and flash

The guide's toolchain is ESP-IDF v5.5.3, and its section 1.6 adds that "Arduino-ESP32 also works (same
GPIOs)". This is the Arduino path, on the ESP32 core the repository already has installed in
`.tools/arduino15` — the same core that builds the car — so both ends of this link can be built and
checked on one machine. Every pin, register and protocol step still comes from the guide.

```sh
# compile (from the repository root)
.tools\arduino.cmd compile --fqbn "esp32:esp32:esp32c3:CDCOnBoot=cdc,FlashMode=dio,FlashFreq=80,FlashSize=4M,PartitionScheme=no_ota" remote/badge_controller

# flash over USB-C (find the port in Device Manager, or: arduino-cli board list)
.tools\arduino.cmd upload -p COM5 --fqbn "esp32:esp32:esp32c3:CDCOnBoot=cdc,FlashMode=dio,FlashFreq=80,FlashSize=4M,PartitionScheme=no_ota" remote/badge_controller

# console (the guide: the console is USB-Serial-JTAG, not UART0)
.tools\arduino.cmd monitor -p COM5 -c baudrate=115200
```

Why those options: `CDCOnBoot=cdc` is what puts the console on USB-Serial-JTAG (guide §1.3);
`FlashMode=dio, FlashFreq=80` matches the flash layout notes (guide §0); 4 MB is the module's flash;
`no_ota` puts the app at `0x10000` with storage after it, which is the shape the guide documents
(`huge_app` also fits — this sketch is 725 KB of a 2 MB partition — but it moves the storage region).

**If the port does not appear or esptool reports "No serial data received"**: hold the Start button
(GPIO9) while plugging the USB-C cable in. That is download mode and it is normal on this board —
native USB has no auto-reset (guide §1.4). A blank screen in that state is expected.

## Pairing (ESP-NOW, no router, no menu)

Both ends sit on Wi-Fi channel `ESPNOW_CHANNEL` (1) and share `ESPNOW_PAIR_KEY` (in `badge_proto.h`,
mirrored into the car's sketch). Those two values *are* the pairing, plus a magic/version check so
frames from another project's ESP-NOW traffic are ignored — at an event, two teams on channel 1 is a
real possibility, and the key is what stops a stray packet from driving your car.

* The badge starts by **broadcasting**, so it does not need to know the car's MAC in advance.
* The car reports in every 500 ms (`BadgeCarStatus`: its own command, uptime, flags).
* The first report teaches the badge the car's MAC, and the badge switches to **unicast**. ESP-NOW
  acknowledges unicast frames and never acknowledges broadcasts, so that switch is what makes the
  badge's `TX`/`FAIL` counters and the `PAIRED` state mean something.
* The car learns the badge's MAC the same way and unicasts its reports back.
* **The badge's drive packets are the car's heartbeat.** The car's existing 750 ms cutoff zeroes the
  motors when nothing has spoken, so a badge that crashes, runs out of battery or walks out of range
  leaves a car that stops by itself. That is the same cutoff the phone app's `h` heartbeat uses; there
  is one deadman, not two.

Change `ESPNOW_CHANNEL` or `ESPNOW_PAIR_KEY` and change both ends — `review/harness/badge_check.py`
fails if they drift apart.

## Screen and LEDs

```
RC CAR  PAIRED          <- link state: PAIRED, or SEARCH while blinking blue
FWD 180,128             <- the maneuver the badge is asking for, with the pair it sends
CAR 3A:4B:5C AGE 0.2S   <- the paired car, and how long since its last report
CAR 180,128 BADGE       <- what the car says it is driving: BADGE, IDLE, or DEADMAN
UP  TX 412 FAIL 0       <- buttons held (or INJECT <cmd>), packets sent, packets not acknowledged
```

LEDs, dim on purpose (AA cells brown out on six LEDs at full white, guide §6): blue blink = searching,
green = paired and idle, white = forward, amber on the left or right three = turning that way, red =
stop or emergency stop.

## Console (the guide's section 9 helper)

One character each, and they override the physical pad until `c`:

| key | effect | key | effect |
|---|---|---|---|
| `f` | inject forward | `x` / `c` | release / clear the injection |
| `l` | inject left | `a` | accelerometer probe (WHO_AM_I + one mg reading) |
| `r` | inject right | `p` / `?` | dump state (link, counters, car's own command) |
| `s` | inject stop | `h` | help |
| `e` | inject emergency stop | | |

`[cmd]`, `[pair]`, `[state]` and `[i2c]` lines print as things change, so the whole controller can be
exercised — and the link debugged — with no buttons and no car. The console accepts `\r` or `\n`
(guide §1.5 notes bare CR), and every line is far under the 256-byte USB-Serial-JTAG chunk limit
(guide §9).

## The car side

`OpenBot/firmware/openbot/openbot.ino` gains an ESP-NOW receiver, switched by one define:

```sh
# badge can drive the car (default)
.tools\arduino.cmd compile --fqbn "esp32:esp32:esp32s3:PartitionScheme=huge_app" OpenBot/firmware/openbot

# phone only, the firmware this project shipped - ESP-NOW compiled out entirely
.tools\arduino.cmd compile --build-property "compiler.cpp.extra_flags=-DHAS_ESPNOW=0" \
    --fqbn "esp32:esp32:esp32s3:PartitionScheme=huge_app" OpenBot/firmware/openbot
```

* A badge command writes the same `ctrl_left` / `ctrl_right` the phone's `c` frame writes, and stamps
  the same `heartbeat_time` its `h` frame stamps. Whichever controller spoke last is the one driving;
  there is no mode switch and no second behaviour to keep in step.
* The receive callback runs on the Wi-Fi task, so it stages the command and `loop()` applies it — no
  motor state is written from two tasks.
* The car only *starts* Wi-Fi when `HAS_ESPNOW` is 1. Wi-Fi and BLE coexist on this board, but this is
  the one change that could affect the phone demo, hence the switch. If the phone link ever misbehaves
  while the badge is on, compile with `-DHAS_ESPNOW=0` and the demo is back exactly as it was.
* **The car must keep Wi-Fi modem sleep on.** It runs BLE for the phone app at the same time, and with
  both radios enabled the Wi-Fi driver aborts (`Should enable WiFi modem sleep when both WiFi and
  Bluetooth are enabled`, then `abort()` on core 0, then a reset loop) if `WIFI_PS_NONE` is set - which
  is what the first version did, copying the badge's own setting. The car sets `WIFI_PS_MIN_MODEM`; the
  badge, having no BLE, can keep `PS_NONE`. Verified on the bench: clean boot, ESP-NOW up, BLE still
  advertising (`review/logs/badge-hardware.log`).
* The badge's packet definition is mirrored in the car's sketch (arduino-cli copies a sketch into its
  build directory, so a shared relative include cannot work). The mirror is compared field for field
  by `review/harness/badge_check.py`, which fails on any drift.

## Power: read this before wondering why the link resets

This badge is powered through a dongle. ESP-NOW needs the Wi-Fi radio, and a disconnected ESP-NOW
station **stays awake** in this build of the core - so the firmware asks for roughly 100 mA of extra
current, continuously. That rail dips below the brown-out detector and the chip resets, forever, if the
supply cannot carry it.

What the badge itself reported, over USB (`review/logs/badge-hardware.log`):

* Boot, panel, LEDs, buttons, console, accelerometer: all stable, radio skipped at boot.
* The accelerometer answers properly: `WHO_AM_I 0x11` and `z ~ 1030 mg` on a flat board.
* The 74HC165 read works, and `AUX1` reads pressed - the guide's "Aux1 is a maintained side switch,
  not momentary - don't assume it boots released".
* The radio initialises on demand and reports up (`2.0 dBm`, channel 1, the badge's MAC).
* **Transmitting resets it**: one injected direction and the board disappears within a few seconds.
* Reset reason, read from the chip: `BROWNOUT (supply dipped)`.

Everything cheap has already been spent on this: TX power at the API floor (2 dBm), 5 packets a second
(the car's deadman is 750 ms), CPU at 80 MHz, panel at 4 Hz, LEDs dark until the link is up, and the
radio started before the panel draws anything. What remains is the receiver, and the SDK rules out the
one fix for it:

```
esp_now.h  : "Only when ESP_WIFI_STA_DISCONNECTED_PM_ENABLE is enabled, this configuration could work"
             (attention 1 to esp_now_set_wake_window)
sdkconfig  : "# CONFIG_ESP_WIFI_STA_DISCONNECTED_PM_ENABLE is not set"   (this core's ESP32-C3 libs)
```

So the wake window is unavailable and the receiver cannot be duty-cycled. The ways forward are power (a
port that can source ~500 mA peaks, or charged cells in the AA holder) or a lighter radio - the car
already speaks BLE for the phone app, which is the obvious candidate, at the cost of a Bluedroid stack
this board's heap may not like (guide section 8).

Until then, flash the dongle-safe build and the badge is a working device:

```sh
# boots without the radio: buttons, screen, LEDs, console (send 'w' to start the radio when you want it)
.tools\arduino.cmd compile -u -p COM8 --build-property "compiler.cpp.extra_flags=-DBADGE_RADIO_ON_BOOT=0" \
    --fqbn "esp32:esp32:esp32c3:CDCOnBoot=cdc,FlashMode=dio,FlashFreq=80,FlashSize=4M,PartitionScheme=no_ota" \
    remote/badge_controller
```

The badge on the bench is currently flashed with exactly that build.

## What has been verified, and what has not

Verified on the badge itself:

* Flashes over USB-Serial-JTAG with no manual download-mode hold, four times in a row: 743,856 bytes at
  `0x10000`, hash verified by the ROM bootloader every time.
* Boots and runs: banner, `reset reason`, the SC7A20 probe, `[state] ready`, the console.
* The console's whole surface answers: `p`/`?` state dumps, `a` accelerometer, `f`/`l`/`r`/`s`/`x`/`c`
  injection, `w` radio start, `h` help.
* The radio comes up on demand (`[espnow] channel 1, pair key 0x5A17BAD6, badge mac ...`, `2.0 dBm`).
* Transmitting is what the dongle cannot carry (see *Power* above) - so send/receive has never been
  seen working, and neither the car's status line nor a pairing has ever happened on hardware.

Verified off-hardware:

* Both firmwares compile with the repository's own toolchain: the badge for `esp32c3`
  (725,104 bytes, 34% of the `no_ota` app partition) and the car for `esp32s3` with `HAS_ESPNOW=1`
  (1,284,541 bytes) and with `HAS_ESPNOW=0` (the phone-only firmware still builds).
* `python review/harness/badge_check.py` (`review/logs/badge.log`): the two firmwares agree on every
  constant and every struct field, the packet layout is printed byte by byte, the font is rendered back
  from its own bytes, every character the badge's own text contains has a glyph, the shift order and
  active-low polarity match the guide, the four drive buttons map to forward / left / right / stop with
  stop winning, and the drive pairs equal the phone app's tuned pairs in `MotorSettings.kt`.

Not verified:

* Nothing has run on hardware: the sketch has never booted in front of anyone, so the panel may come up rotated or with red and blue
  swapped on the real board — that is what `PANEL_SWAP_XY`, `PANEL_MIRROR_X/Y` and `PANEL_BGR` in
  `badge_pins.h` are for (guide §4: "if your image appears rotated/mirrored, those three calls are the
  fix").
* The `I2C` accelerometer probe has never met the real SC7A20; it is bounded and prints why it failed.
* LED brightness/colour order and the WS2812 timing constants are the values this core uses for its own
  `neopixelWrite`, but they have not been seen on the badge's 6-LED strip.
* BLE + Wi-Fi coexistence on the car is a supported configuration, not a measured one.
