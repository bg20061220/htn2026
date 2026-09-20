// The badge <-> car wire format, over ESP-NOW.
//
// One header, two firmwares: the badge controller (remote/badge-controller/badge_controller.ino) and
// the car's OpenBot sketch (OpenBot/firmware/openbot/openbot.ino, which reaches it with a relative
// include). A wire format that lives in two copies drifts, and the symptom of that drift is a paired
// badge whose car ignores it - so there is exactly one definition of every field, magic byte and
// flag, and review/harness/badge_check.py reads the bytes out of this file rather than restating them.
//
// ESP-NOW is the "ESP32 peer-to-peer direct network": no access point, no router, no pairing menu.
// Both ends sit on the same Wi-Fi channel, hold the same pair key, and are each other's peer. The
// badge starts out broadcasting, so it can find a car whose MAC it does not know; once the car's
// status arrives, the badge learns that MAC and switches to unicast, which is what gives each send a
// real delivery result (ESP-NOW acknowledges unicast frames, and never acknowledges broadcasts) -
// that ACK is what the badge's LINK line reports.
//
// Endianness and padding: every ESP32 is little-endian, both structs are packed, and both are compiled
// by GCC from this file, so the memory image is the same on both ends. Add fields at the end only.
#pragma once

#include <stdint.h>

#define ESPNOW_MAGIC_0 'B'
#define ESPNOW_MAGIC_1 'C'
#define ESPNOW_VERSION 1

#define ESPNOW_KIND_DRIVE 1
#define ESPNOW_KIND_STATUS 2

// Change these and change them on both ends: the channel and the key are the whole of "pairing", and
// the key is what keeps another team's badge on the same channel from driving this car.
#define ESPNOW_CHANNEL 1
#define ESPNOW_PAIR_KEY 0x5A17BAD6u

// Badge -> car flags.
#define ESPNOW_FLAG_STOP 0x01  // Down or Start is held: stop now, whatever else is pressed.

// Car -> badge flags.
#define ESPNOW_STATUS_ESPNOW_DRIVING 0x01  // the last command the car acted on came from a badge
#define ESPNOW_STATUS_DEADMAN 0x02         // the car zeroed the motors on its own: nobody was talking

// Cadence. The car's deadman is the car's own heartbeat_interval (openbot.ino), so a badge that stops
// transmitting - crash, flat battery, out of range - leaves a car that stops itself.
// 5 packets a second is still five times the car's 750 ms deadman, and it costs a fifth of the radio
// duty cycle - which is what matters on a badge running from a dongle or AA cells, where every extra
// transmit burst is another chance to dip the rail below the brown-out threshold.
#define ESPNOW_DRIVE_HZ 5                  // packets per second while a direction is held
#define ESPNOW_STOP_REPEAT_MS 400         // how long a released button keeps repeating STOP
#define ESPNOW_STATUS_INTERVAL_MS 500     // how often the car reports in
#define ESPNOW_LINK_TIMEOUT_MS 3000       // badge: no status for this long and the car is gone

typedef struct __attribute__((packed)) {
  uint8_t magic[2];   // ESPNOW_MAGIC_0, ESPNOW_MAGIC_1
  uint8_t version;    // ESPNOW_VERSION
  uint8_t kind;       // ESPNOW_KIND_DRIVE
  uint32_t pairKey;   // ESPNOW_PAIR_KEY
  int16_t left;       // -255..255, the same signed units as the 'c' serial frame
  int16_t right;
  uint16_t seq;       // wraps; the car does not use it, the badge prints it
  uint8_t flags;      // ESPNOW_FLAG_STOP
  uint8_t reserved;   // spare, so a new flag does not change the packet size
} BadgeDrivePacket;

typedef struct __attribute__((packed)) {
  uint8_t magic[2];
  uint8_t version;
  uint8_t kind;       // ESPNOW_KIND_STATUS
  uint32_t pairKey;
  uint16_t seq;       // the car's own counter, so the badge can see skipped reports
  uint16_t uptime100ms;  // car uptime, 100 ms units, wraps after ~1.8 h
  int16_t left;       // what the car is actually driving right now
  int16_t right;
  uint8_t flags;      // ESPNOW_STATUS_*
  uint8_t reserved;   // spare, so a new flag does not change the packet size
} BadgeCarStatus;

// The wire sizes are asserted, not assumed: a field added without a thought about the layout is a
// silent mismatch between the two firmwares, and this is the only place that could catch it.
#define ESPNOW_DRIVE_BYTES 16
#define ESPNOW_STATUS_BYTES 18

#if defined(__cplusplus)
static_assert(sizeof(BadgeDrivePacket) == ESPNOW_DRIVE_BYTES, "BadgeDrivePacket changed size");
static_assert(sizeof(BadgeCarStatus) == ESPNOW_STATUS_BYTES, "BadgeCarStatus changed size");
#else
_Static_assert(sizeof(BadgeDrivePacket) == ESPNOW_DRIVE_BYTES, "BadgeDrivePacket changed size");
_Static_assert(sizeof(BadgeCarStatus) == ESPNOW_STATUS_BYTES, "BadgeCarStatus changed size");
#endif
