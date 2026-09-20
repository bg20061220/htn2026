// The badge's hardware map, transcribed from section 2 of remote/custom-firmware-hal.md.
//
// Nothing here is inferred: every pin, the button shift order and the shift-register protocol come
// from that guide's "Pin map" and "Buttons" sections, and the panel knobs from "Screen". If the guide
// is ever updated, this file and badge_hal.cpp are the two places to follow it.
#pragma once

// ---- Display: ST7789, 320x240, RGB565, SPI2 @ 40 MHz, mode 0 -----------------
#define PIN_LCD_MOSI 10
#define PIN_LCD_CLK 1
#define PIN_LCD_CS 2
#define PIN_LCD_DC 0
#define PIN_LCD_RST 4
#define LCD_SPI_HZ 40000000
#define LCD_LOGICAL_W 320
#define LCD_LOGICAL_H 240

// The guide's init sequence is "reset, init, invert_color(true), swap_xy(true), mirror(true, false)".
// These are the two knobs behind those three calls, kept as switches because a panel that comes up
// rotated or with red and blue swapped is fixed by exactly one of them and nothing else.
#define PANEL_SWAP_XY 1    // swap_xy(true): the 240x320 panel driven as the badge's 320x240 screen
#define PANEL_MIRROR_X 1   // mirror(true, false)
#define PANEL_MIRROR_Y 0
#define PANEL_INVERT_COLORS 1  // invert_color(true)
#define PANEL_BGR 1            // set 0 if red and blue come out swapped

// ---- Buttons: 74HC165 shift register + the dedicated Start button ------------
#define PIN_BTN_DATA 7   // shift register serial out
#define PIN_BTN_LOAD 20  // pulse low to latch
#define PIN_BTN_CLK 21
#define PIN_BTN_START 9  // active-low, own GPIO, and the board's boot strap pin

// Poll rate and debounce, per the guide: "Poll at ~10 ms and debounce".
#define BUTTON_POLL_MS 10
#define BUTTON_DEBOUNCE_SAMPLES 2  // equal reads in a row before a press counts

// ---- LEDs: 6x WS2812B-2020 on one RMT line -----------------------------------
#define PIN_LED_DIN 3
#define LED_COUNT 6
// "Keep brightness modest - 6 LEDs at full white can brown-out the board on AA power." This is the
// ceiling every pattern is scaled to, out of 255.
#define LED_MAX_BRIGHTNESS 28

// ---- I2C: accelerometer (0x19) and NFC (0x26) share this bus ------------------
#define PIN_I2C_SDA 5
#define PIN_I2C_SCL 6
#define I2C_HZ 400000
#define ACCEL_ADDR 0x19
#define ACCEL_WHO_AM_I 0x0F
#define ACCEL_WHO_AM_I_VALUE 0x11
// The guide: "use bounded I2C timeouts and retry - never wait forever."
#define I2C_TIMEOUT_MS 50

// ---- Power -------------------------------------------------------------------
// How long the rail is given to recover before the radio is started, and how fast the CPU runs. This
// badge is powered through a dongle: its brown-out detector trips when the radio's start-up
// calibration lands on a rail that is still recovering from the panel's own init, so the radio is
// started first, against the lightest load this firmware can present (see setup()).
#define RADIO_SETTLE_MS 400
#define CPU_MHZ 80  // the controller needs nothing faster: 20 packets a second, a 10 Hz status screen

// Whether the radio is started during setup. 1 is the controller people want; 0 is for a supply that
// cannot carry the radio at boot, where the alternative is a brown-out reset loop and no badge at all:
// with 0 the badge comes up as a working button/LCD/LED device, and 'w' on the console starts the
// radio when the power is there. Overridable from the build:
//   arduino-cli compile --build-property compiler.cpp.extra_flags=-DBADGE_RADIO_ON_BOOT=0 \
//       --fqbn "esp32:esp32:esp32c3:CDCOnBoot=cdc,FlashMode=dio,FlashFreq=80,FlashSize=4M,PartitionScheme=no_ota" \
//       remote/badge_controller
#ifndef BADGE_RADIO_ON_BOOT
#define BADGE_RADIO_ON_BOOT 1
#endif

// ---- The controller itself ---------------------------------------------------
#define SERIAL_BAUD 115200  // USB-Serial-JTAG: the console the guide's section 9 asks for
#define BADGE_DEBUG 1       // state prints and the console's button-inject path

// Console helpers, in the spirit of the guide's section 9 ("keep at least a button-inject + print path
// over USB-Serial-JTAG ... being able to drive buttons and read state over USB is what makes HAL
// development fast"). One character each, and it overrides the physical pad until 'c' clears it:
//   f forward   l left   r right   s stop   x release   c clear   a accel   ? state   h help
#define CONSOLE_LINE_END '\n'  // '\r' is accepted too: the guide notes bare CR on this console
