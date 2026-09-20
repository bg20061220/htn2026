// Badge RC car controller.
//
// Four buttons drive a car, one ESP-NOW link carries the commands, the badge's own screen and LEDs
// say what is happening. Built to remote/custom-firmware-hal.md: the pin map (section 2), the button
// protocol and shift order (section 3), the screen (section 4), the LED caution (section 6) and the
// console helpers (section 9) are all that guide's rules, not this file's inventions.
//
//   Up     hold to drive forward        (the car's tuned forward pair)
//   Left   hold to pivot left
//   Right  hold to pivot right
//   Down   stop
//   Start  stop too - the badge's dedicated button, kept as the stop that is always under a finger
//
// Nothing here needs a phone, a router or a pairing menu: both ends sit on one Wi-Fi channel and are
// each other's ESP-NOW peer (see badge_proto.h). The badge broadcasts until it hears the car, then
// unicasts to the MAC it learned; the car stops itself if the badge goes quiet, because the badge's
// packets are the car's heartbeat.
//
// Console (USB-Serial-JTAG, see the guide's section 9): f l r s x c a ? h - one character each.
// Those inject buttons so the whole controller can be exercised with no badge buttons and no car.

#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

#include "badge_hal.h"
#include "badge_pins.h"
#include "badge_proto.h"

// ---------------------------------------------------------------------------
// The drive pairs. These mirror the phone app's tuned manual pairs
// (app/src/main/java/com/example/guidedogtest/MotorSettings.kt) so the badge and the app ask the car
// for the same thing; they are the tuned values, not a guess, and the car's units are the same signed
// PWM the 'c' serial frame uses.
// ---------------------------------------------------------------------------
#define DRIVE_FORWARD_LEFT 180
#define DRIVE_FORWARD_RIGHT 128
#define DRIVE_LEFT_LEFT -205
#define DRIVE_LEFT_RIGHT 190
#define DRIVE_RIGHT_LEFT 205
#define DRIVE_RIGHT_RIGHT -195

enum class Maneuver : uint8_t { Stop, Forward, Left, Right, EmergencyStop };

static const char *maneuverLabel(Maneuver m) {
  switch (m) {
    case Maneuver::Forward: return "FWD";
    case Maneuver::Left: return "LEFT";
    case Maneuver::Right: return "RIGHT";
    case Maneuver::EmergencyStop: return "E-STOP";
    default: return "STOP";
  }
}

// ---------------------------------------------------------------------------
// Link state
// ---------------------------------------------------------------------------

static const uint8_t BROADCAST_MAC[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

static uint8_t gCarMac[6] = {};
static bool gHasCarMac = false;
static uint32_t gLastStatusMs = 0;   // 0 = nothing heard yet
static uint32_t gFirstStatusMs = 0;
static uint16_t gCarSeq = 0;
static uint16_t gCarUptime100ms = 0;
static int16_t gCarLeft = 0;
static int16_t gCarRight = 0;
static uint8_t gCarFlags = 0;
static uint32_t gStatusCount = 0;

static uint16_t gDriveSeq = 0;
static uint32_t gTxCount = 0;
static uint32_t gTxFailCount = 0;
static esp_now_send_status_t gLastTxStatus = ESP_NOW_SEND_FAIL;
static bool gHaveTxResult = false;

static bool carLinked(uint32_t now) {
  return gLastStatusMs != 0 && (now - gLastStatusMs) < ESPNOW_LINK_TIMEOUT_MS;
}

static void addPeer(const uint8_t *mac) {
  if (esp_now_is_peer_exist(mac)) return;
  esp_now_peer_info_t peer = {};
  memcpy(peer.peer_addr, mac, 6);
  peer.channel = ESPNOW_CHANNEL;
  peer.encrypt = false;
  if (esp_now_add_peer(&peer) != ESP_OK) {
    Serial.println("[espnow] add_peer failed");
  }
}

// Core 2.x callback shape. On Arduino core 3.x this becomes (const esp_now_recv_info_t *info).
static void onEspNowRecv(const uint8_t *mac, const uint8_t *data, int len) {
  if (len != (int)sizeof(BadgeCarStatus)) return;

  BadgeCarStatus status;
  memcpy(&status, data, sizeof(status));
  if (status.magic[0] != ESPNOW_MAGIC_0 || status.magic[1] != ESPNOW_MAGIC_1) return;
  if (status.version != ESPNOW_VERSION || status.kind != ESPNOW_KIND_STATUS) return;
  // Same channel, same key, or it is somebody else's car: at a hackathon that is a real neighbour.
  if (status.pairKey != ESPNOW_PAIR_KEY) return;

  uint32_t now = millis();
  if (!gHasCarMac || memcmp(gCarMac, mac, 6) != 0) {
    memcpy(gCarMac, mac, 6);
    gHasCarMac = true;
    gFirstStatusMs = now;
    // Unicast needs the peer registered: ESP-NOW refuses to send to an address it has not been told
    // about, so without this line the link would go dead at the exact moment it paired. (And the peer
    // table is not touched from a receive callback on the car for the same reason reversed - there it
    // is staged and applied in loop().)
    addPeer(gCarMac);
#if BADGE_DEBUG
    Serial.printf("[pair] car %02X:%02X:%02X:%02X:%02X:%02X\n", mac[0], mac[1], mac[2], mac[3], mac[4],
                  mac[5]);
#endif
  }
  if (gLastStatusMs == 0) {
#if BADGE_DEBUG
    Serial.println("[pair] first status: link up, switching to unicast");
#endif
  }
  gLastStatusMs = now;
  gCarSeq = status.seq;
  gCarUptime100ms = status.uptime100ms;
  gCarLeft = status.left;
  gCarRight = status.right;
  gCarFlags = status.flags;
  gStatusCount++;
}

static void onEspNowSent(const uint8_t *mac, esp_now_send_status_t status) {
  gLastTxStatus = status;
  gHaveTxResult = true;
  if (status != ESP_NOW_SEND_SUCCESS) gTxFailCount++;
}

/**
 * Bring the radio up, once, as cheaply as the driver allows.
 *
 * Every line here is about current, because this badge is powered through a dongle and its rail sags:
 * the ESP-NOW start-up calibration is the largest current event in the firmware, and a dip below the
 * brown-out threshold resets the chip - which is what a bare ESP-NOW start did, over and over.
 * `esp_wifi_set_max_tx_power` is the only one of these the API offers (8.5 dBm, the lowest it
 * accepts); the rest is done by starting the radio before the panel and the LED strip draw anything
 * (see setup()) and by the CPU running at 80 MHz.
 *
 * Returns false if the driver refused, so the badge can boot as a button/LCD/LED device and say why
 * instead of looking dead.
 */
static bool gRadioReady = false;

static bool espnowBegin() {
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();  // no access point: ESP-NOW is a direct link, not a network
  esp_wifi_set_channel(ESPNOW_CHANNEL, WIFI_SECOND_CHAN_NONE);
  esp_wifi_set_ps(WIFI_PS_NONE);  // no modem sleep: 20 packets a second is not a battery project

  // Peak current is what this board has to survive, not average: every TX burst pulls hundreds of
  // milliamps, and on this supply that is the difference between a badge and a brown-out loop. 2 dBm
  // (8 in the API's 0.25 dBm units - the bottom of the range it accepts) is short range, and short
  // range is exactly what a hand-held controller driving a car three metres away needs.
  esp_wifi_set_max_tx_power(8);
  int8_t txPower = 0;
  if (esp_wifi_get_max_tx_power(&txPower) == ESP_OK) {
    Serial.printf("[espnow] max tx power set to %d (%.1f dBm)\n", txPower, txPower * 0.25f);
  }

  if (esp_now_init() != ESP_OK) {
    Serial.println("[espnow] esp_now_init failed - no link");
    return false;
  }
  esp_now_register_recv_cb(onEspNowRecv);
  esp_now_register_send_cb(onEspNowSent);
  addPeer(BROADCAST_MAC);
  gRadioReady = true;
  Serial.printf("[espnow] channel %d, pair key 0x%08X, badge mac %s\n", ESPNOW_CHANNEL, ESPNOW_PAIR_KEY,
                WiFi.macAddress().c_str());
  return true;
}

static void sendDrive(Maneuver maneuver) {
  // No radio (skipped at boot, or the driver refused): there is nothing to send to, and this is also
  // what keeps a build with BADGE_RADIO_ON_BOOT=0 free of driver calls it never initialised.
  if (!gRadioReady) return;

  BadgeDrivePacket packet = {};
  packet.magic[0] = ESPNOW_MAGIC_0;
  packet.magic[1] = ESPNOW_MAGIC_1;
  packet.version = ESPNOW_VERSION;
  packet.kind = ESPNOW_KIND_DRIVE;
  packet.pairKey = ESPNOW_PAIR_KEY;
  packet.seq = ++gDriveSeq;

  switch (maneuver) {
    case Maneuver::Forward:
      packet.left = DRIVE_FORWARD_LEFT;
      packet.right = DRIVE_FORWARD_RIGHT;
      break;
    case Maneuver::Left:
      packet.left = DRIVE_LEFT_LEFT;
      packet.right = DRIVE_LEFT_RIGHT;
      break;
    case Maneuver::Right:
      packet.left = DRIVE_RIGHT_LEFT;
      packet.right = DRIVE_RIGHT_RIGHT;
      break;
    default:
      packet.left = 0;
      packet.right = 0;
      break;
  }
  if (maneuver == Maneuver::Stop || maneuver == Maneuver::EmergencyStop) {
    packet.flags |= ESPNOW_FLAG_STOP;
  }

  const uint8_t *destination = gHasCarMac ? gCarMac : BROADCAST_MAC;
  gTxCount++;
  esp_now_send(destination, (const uint8_t *)&packet, sizeof(packet));
}

// ---------------------------------------------------------------------------
// Input: the four drive buttons, plus the console's button injection
// ---------------------------------------------------------------------------

static bool gInjectActive = false;
static Maneuver gInjectManeuver = Maneuver::Stop;

static Maneuver padManeuver() {
  const BadgeButtons &buttons = halButtons();
  // Stop beats everything else, so a stop is never a race with a held direction; Start is the same
  // stop with its own name on the screen.
  if (buttons.start) return Maneuver::EmergencyStop;
  if (buttons.down) return Maneuver::Stop;
  if (buttons.up) return Maneuver::Forward;
  if (buttons.left) return Maneuver::Left;
  if (buttons.right) return Maneuver::Right;
  return Maneuver::Stop;
}

static Maneuver currentManeuver() { return gInjectActive ? gInjectManeuver : padManeuver(); }

static void consoleHelp() {
  Serial.println("[help] f forward  l left  r right  s stop  x release  c clear  a accel  w radio  p pair  ? state  h help");
  Serial.println("[help] injection replaces the physical pad until 'c'");
}

static void consoleState(Maneuver maneuver) {
  char scratch[BADGE_BUTTON_LABEL_BYTES];
  uint32_t now = millis();
  Serial.printf("[state] cmd=%s inject=%s buttons=%s\n", maneuverLabel(maneuver),
                gInjectActive ? maneuverLabel(gInjectManeuver) : "no",
                halButtons().label(scratch, sizeof(scratch)));
  Serial.printf("[state] radio=%s car=%s age=%lums rx=%lu tx=%lu fails=%lu",
                gRadioReady ? "up" : "DOWN", gHasCarMac ? "paired" : "none",
                gLastStatusMs == 0 ? 0 : (unsigned long)(now - gLastStatusMs), (unsigned long)gStatusCount,
                (unsigned long)gTxCount, (unsigned long)gTxFailCount);
  if (gHasCarMac) {
    Serial.printf(" mac=%02X:%02X:%02X:%02X:%02X:%02X", gCarMac[0], gCarMac[1], gCarMac[2], gCarMac[3],
                  gCarMac[4], gCarMac[5]);
  }
  Serial.printf(" carcmd=%d,%d flags=0x%02X seq=%u uptime=%us\n", gCarLeft, gCarRight, gCarFlags, gCarSeq,
                (unsigned)(gCarUptime100ms / 10));
  if (gHaveTxResult) {
    Serial.printf("[state] last send %s\n",
                  gLastTxStatus == ESP_NOW_SEND_SUCCESS ? "acknowledged" : "not acknowledged");
  }
}

static void pollConsole(Maneuver maneuver) {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\r' || c == '\n' || c == ' ') continue;
    switch (c) {
      case 'f': gInjectActive = true; gInjectManeuver = Maneuver::Forward; break;
      case 'l': gInjectActive = true; gInjectManeuver = Maneuver::Left; break;
      case 'r': gInjectActive = true; gInjectManeuver = Maneuver::Right; break;
      case 's': gInjectActive = true; gInjectManeuver = Maneuver::Stop; break;
      case 'e': gInjectActive = true; gInjectManeuver = Maneuver::EmergencyStop; break;
      case 'x':
      case 'c':
        gInjectActive = false;
        gInjectManeuver = Maneuver::Stop;
        break;
      case 'a':
        accelProbe();
        break;
      case 'w':
        if (gRadioReady) {
          Serial.println("[espnow] radio already up");
        } else {
          Serial.println("[espnow] starting radio...");
          Serial.flush();
          espnowBegin();
        }
        break;
      case 'p':
        consoleState(maneuver);
        break;
      case '?':
        consoleState(maneuver);
        break;
      case 'h':
        consoleHelp();
        break;
      default:
        Serial.printf("[help] unknown '%c'\n", c);
        consoleHelp();
        break;
    }
    if (c != 'a' && c != 'p' && c != '?' && c != 'h') {
      Serial.printf("[inject] %s\n", gInjectActive ? maneuverLabel(gInjectManeuver) : "cleared");
    }
  }
}

// ---------------------------------------------------------------------------
// Feedback: screen and LEDs
// ---------------------------------------------------------------------------

#define LCD_LINE1_Y 16
#define LCD_LINE2_Y 56
#define LCD_LINE3_Y 110
#define LCD_LINE4_Y 140
#define LCD_LINE5_Y 180
#define LCD_BOOT_Y 200

// The guide's checklist wants a fill-screen test; the cheapest honest one is this line, held long
// enough to be seen before the status screen takes over.
#define BOOT_SPLASH_MS 700

#define COLOR_BG 0x0000
#define COLOR_TITLE 0xCE79   // light grey
#define COLOR_LINK 0x07E0    // green
#define COLOR_WAIT 0x061F    // blue
#define COLOR_STOP 0xF800    // red
#define COLOR_DRIVE 0xFFE0   // amber
#define COLOR_BODY 0xFFFF    // white

static void drawScreen(Maneuver maneuver, uint32_t now) {
  bool linked = carLinked(now);

  lcdText(8, LCD_LINE1_Y, "RC CAR", COLOR_TITLE, COLOR_BG, 2);
  lcdText(160, LCD_LINE1_Y, linked ? "PAIRED" : "SEARCH", linked ? COLOR_LINK : COLOR_WAIT, COLOR_BG, 2);

  char line2[24];
  switch (maneuver) {
    case Maneuver::Forward: snprintf(line2, sizeof(line2), "FWD %d,%d", DRIVE_FORWARD_LEFT, DRIVE_FORWARD_RIGHT); break;
    case Maneuver::Left: snprintf(line2, sizeof(line2), "LEFT %d,%d", DRIVE_LEFT_LEFT, DRIVE_LEFT_RIGHT); break;
    case Maneuver::Right: snprintf(line2, sizeof(line2), "RIGHT %d,%d", DRIVE_RIGHT_LEFT, DRIVE_RIGHT_RIGHT); break;
    case Maneuver::EmergencyStop: snprintf(line2, sizeof(line2), "E-STOP"); break;
    default: snprintf(line2, sizeof(line2), "STOP"); break;
  }
  uint16_t commandColor = maneuver == Maneuver::Stop || maneuver == Maneuver::EmergencyStop
                              ? COLOR_STOP
                              : (maneuver == Maneuver::Forward ? COLOR_BODY : COLOR_DRIVE);
  lcdText(8, LCD_LINE2_Y, line2, commandColor, COLOR_BG, 3);

  char line3[48];
  if (gHasCarMac) {
    if (gLastStatusMs == 0) {
      snprintf(line3, sizeof(line3), "CAR %02X:%02X:%02X WAIT", gCarMac[0], gCarMac[1], gCarMac[2]);
    } else {
      snprintf(line3, sizeof(line3), "CAR %02X:%02X:%02X AGE %luS", gCarMac[0], gCarMac[1], gCarMac[2],
               (unsigned long)((now - gLastStatusMs) / 1000));
    }
  } else if (!gRadioReady) {
    snprintf(line3, sizeof(line3), "NO RADIO  TX %lu", (unsigned long)gTxCount);
  } else {
    snprintf(line3, sizeof(line3), "NO CAR YET  RX %lu", (unsigned long)gStatusCount);
  }
  lcdText(8, LCD_LINE3_Y, line3, linked ? COLOR_LINK : COLOR_WAIT, COLOR_BG, 1);

  char line4[48];
  snprintf(line4, sizeof(line4), "CAR %d,%d %s", gCarLeft, gCarRight,
           (gCarFlags & ESPNOW_STATUS_DEADMAN) ? "DEADMAN" : ((gCarFlags & ESPNOW_STATUS_ESPNOW_DRIVING) ? "BADGE" : "IDLE"));
  lcdText(8, LCD_LINE4_Y, line4, COLOR_BODY, COLOR_BG, 1);

  char scratch[BADGE_BUTTON_LABEL_BYTES];
  char line5[56];
  const char *buttons = halButtons().label(scratch, sizeof(scratch));
  if (gInjectActive) {
    snprintf(line5, sizeof(line5), "INJECT %s  TX %lu FAIL %lu", maneuverLabel(gInjectManeuver),
             (unsigned long)gTxCount, (unsigned long)gTxFailCount);
  } else {
    snprintf(line5, sizeof(line5), "%s  TX %lu FAIL %lu", buttons[0] ? buttons : "PAD IDLE",
             (unsigned long)gTxCount, (unsigned long)gTxFailCount);
  }
  lcdText(8, LCD_LINE5_Y, line5, COLOR_TITLE, COLOR_BG, 1);
}

// Physical order from the guide's section 6: 0 upper-left, 1 upper-right, 2 middle-right,
// 3 bottom-right, 4 bottom-left, 5 middle-left.
static const uint8_t LED_LEFT_SIDE[3] = {0, 5, 4};
static const uint8_t LED_RIGHT_SIDE[3] = {1, 2, 3};

static void drawLeds(Maneuver maneuver, uint32_t now) {
  bool linked = carLinked(now);
  uint8_t bright = LED_MAX_BRIGHTNESS;

  if (!linked) {
    // Searching: blue, blinking at 2 Hz so it is obvious the badge is still looking.
    bool on = ((now / 500) % 2) == 0;
    ledsFill(0, 0, on ? bright : 0);
    ledsShow();
    return;
  }

  switch (maneuver) {
    case Maneuver::Forward:
      ledsFill(bright, bright, bright);
      break;
    case Maneuver::Left:
      ledsFill(0, 0, 0);
      for (uint8_t i = 0; i < 3; i++) ledsSet(LED_LEFT_SIDE[i], bright, bright / 2, 0);
      break;
    case Maneuver::Right:
      ledsFill(0, 0, 0);
      for (uint8_t i = 0; i < 3; i++) ledsSet(LED_RIGHT_SIDE[i], bright, bright / 2, 0);
      break;
    case Maneuver::EmergencyStop:
      ledsFill(bright, 0, 0);
      break;
    default:
      // Linked and stopped: a calm green.
      ledsFill(0, bright / 2, 0);
      break;
  }
  ledsShow();
}

// ---------------------------------------------------------------------------
// Main loop
// ---------------------------------------------------------------------------

static uint32_t gLastPollMs = 0;
static uint32_t gLastSendMs = 0;
static uint32_t gLastScreenMs = 0;
static uint32_t gLastLedsMs = 0;
static uint32_t gLastLedState = 0;
static Maneuver gLastManeuver = Maneuver::Stop;

/**
 * Why the chip is running at all, in words. On a board that shares one USB connector with its
 * batteries, "it restarted" has three very different answers - brownout (the rail dipped, usually
 * when a radio starts), panic (a bug) and watchdog (a stall) - and the console is the only place any
 * of them can be read. First line after the banner on every boot, so a reset loop says what it is.
 */
static const char *resetReasonName(esp_reset_reason_t reason) {
  switch (reason) {
    case ESP_RST_POWERON: return "power-on";
    case ESP_RST_EXT: return "external pin";
    case ESP_RST_SW: return "software";
    case ESP_RST_PANIC: return "PANIC";
    case ESP_RST_INT_WDT: return "interrupt watchdog";
    case ESP_RST_TASK_WDT: return "task watchdog";
    case ESP_RST_WDT: return "watchdog";
    case ESP_RST_DEEPSLEEP: return "deep sleep wake";
    case ESP_RST_BROWNOUT: return "BROWNOUT (supply dipped)";
    case ESP_RST_SDIO: return "SDIO";
    default: return "unknown";
  }
}

void setup() {
  Serial.begin(SERIAL_BAUD);
  delay(300);  // USB-Serial-JTAG needs a moment before the first line lands
  Serial.println();
  Serial.println("badge rc controller: 4 buttons -> ESP-NOW -> car");
  Serial.printf("reset reason: %s (%d)\n", resetReasonName(esp_reset_reason()),
                (int)esp_reset_reason());

  // Power plan, in order of effect. The radio's calibration is the biggest current event in this
  // firmware and the rail behind it is a dongle, so it is started before the panel and the LED strip
  // ask for anything: nothing but the serial console is drawing when the PHY calibrates.
  setCpuFrequencyMhz(CPU_MHZ);
  Serial.printf("cpu %u MHz, settling %d ms before the radio\n", getCpuFrequencyMhz(), RADIO_SETTLE_MS);
  delay(RADIO_SETTLE_MS);
  // This is the line a brown-out ends on, if one does; the reset reason above then says so on the next
  // boot. Both together are what turned "it keeps restarting" into "the supply dips at radio start".
  Serial.println("[espnow] starting radio...");
  Serial.flush();
#if BADGE_RADIO_ON_BOOT
  if (!espnowBegin()) {
    Serial.println("[espnow] badge is usable as a button/LCD/LED device; send 'w' to retry the radio");
  }
#else
  Serial.println("[espnow] radio skipped at boot (BADGE_RADIO_ON_BOOT=0); send 'w' to start it");
#endif

  halBegin();
  // A dark screen plus this line is the fastest way to tell "the panel came up" from "the sketch is
  // not running" (the guide's checklist asks for the fill-screen test).
  lcdText(8, LCD_BOOT_Y, "BOOTING", COLOR_BODY, COLOR_BG, 2);
  accelProbe();
  Serial.println("[state] ready");
  gLastManeuver = currentManeuver();
  consoleHelp();
}

void loop() {
  uint32_t now = millis();

  if (now - gLastPollMs >= BUTTON_POLL_MS) {
    gLastPollMs = now;
    halPollButtons();
  }

  pollConsole(currentManeuver());

  Maneuver maneuver = currentManeuver();
  bool changed = maneuver != gLastManeuver;
  if (changed) {
    char scratch[BADGE_BUTTON_LABEL_BYTES];
    Serial.printf("[cmd] %s (buttons %s)\n", maneuverLabel(maneuver),
                  halButtons().label(scratch, sizeof(scratch)));
    gLastManeuver = maneuver;
    gLastSendMs = 0;  // a change goes out on this tick, not on the next 50 ms boundary
  }

  // Drive packets stream while something is held, and STOP keeps repeating for a moment after a
  // release so a dropped packet cannot leave the car rolling on the last command. Once that window
  // passes the badge goes quiet and the car stops itself on its own deadman.
  bool driving = maneuver == Maneuver::Forward || maneuver == Maneuver::Left || maneuver == Maneuver::Right;
  static uint32_t releasedAt = 0;
  if (driving) {
    releasedAt = now;
  } else if (releasedAt == 0) {
    releasedAt = now;
  }
  bool inStopWindow = !driving && (now - releasedAt) < ESPNOW_STOP_REPEAT_MS;

  if (driving || inStopWindow || changed) {
    uint32_t interval = 1000 / ESPNOW_DRIVE_HZ;
    if (gLastSendMs == 0 || now - gLastSendMs >= interval) {
      gLastSendMs = now;
      sendDrive(maneuver);
    }
  }

  // 4 Hz, not 10: the panel is the second-biggest load on the rail behind the radio, and a status
  // screen nobody reads faster than this is not worth a brown-out.
  if (now >= BOOT_SPLASH_MS && now - gLastScreenMs >= 250) {
    gLastScreenMs = now;
    drawScreen(maneuver, now);
  }

  // The LEDs only need repainting when something changes, except while searching, where the blink is
  // the point.
  if (now - gLastLedsMs >= 100) {
    gLastLedsMs = now;
    uint32_t state = (uint32_t)maneuver * 16 + (carLinked(now) ? 1 : 0);
    if (state != gLastLedState || !carLinked(now)) {
      gLastLedState = state;
      drawLeds(maneuver, now);
    }
  }
}
