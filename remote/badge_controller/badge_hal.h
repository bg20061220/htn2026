// The badge's hardware abstraction: buttons, screen, LEDs, accelerometer.
//
// Everything the controller firmware needs to touch the badge, and nothing that knows what a car is.
// Each piece follows the matching section of remote/custom-firmware-hal.md, and the pin numbers come
// from badge_pins.h so there is one place to change them.
//
// Deliberately dependency-free: no LVGL, no display library, no WS2812 library. The guide allows
// either "esp_lcd + esp_lvgl_port" or "raw RGB565 frames", and this is the raw-frame half - the whole
// display driver is ~150 lines, which is less code than the include list of a graphics stack, and it
// leaves the RMT peripheral free for the LEDs.
#pragma once

#include <stddef.h>
#include <stdint.h>

/** The eight shift-register inputs plus the dedicated Start button, as *pressed* booleans. */
struct BadgeButtons {
  bool a, b, home, down, left, right, up, aux1, start;

  /** One line for the console and the LCD: "UP", "LEFT+START", "" for nothing. */
  const char *label(char *scratch, size_t bytes) const;
};

#define BADGE_BUTTON_COUNT 9
#define BADGE_BUTTON_LABEL_BYTES 40  // worst case "DOWN+LEFT+RIGHT+UP+AUX1+START+B+HOME+A,"

void halBegin();
void halPollButtons();              // every BUTTON_POLL_MS; debounces internally
const BadgeButtons &halButtons();   // the debounced state
BadgeButtons halReadRawButtons();   // one latched read, undebounced (serial injection, bench tests)
uint32_t halButtonHoldMs(uint8_t index);  // how long button `index` has been held, 0 when up

// Screen. lcdText draws one line at (x, y) in logical pixels; y is the top of the 8-row cell.
void lcdFill(uint16_t rgb565);
void lcdText(int16_t x, int16_t y, const char *text, uint16_t fg, uint16_t bg, uint8_t scale);
uint16_t lcdTextWidth(const char *text, uint8_t scale);

// LEDs. The strip is written in one RMT burst, so ledsShow() is the only function that costs time.
void ledsFill(uint8_t r, uint8_t g, uint8_t b);
void ledsSet(uint8_t index, uint8_t r, uint8_t g, uint8_t b);
void ledsShow();

/**
 * One bounded accelerometer transaction: WHO_AM_I, the two enable registers, and one mg reading.
 * Prints what it found. Returns false if the bus did not answer - the guide warns that the NFC chip
 * sharing this bus can wedge it, so this never waits longer than I2C_TIMEOUT_MS and never loops.
 */
bool accelProbe();
