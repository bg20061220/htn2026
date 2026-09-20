// Badge HAL implementation. See badge_hal.h for what each piece is for and remote/custom-firmware-hal.md
// for where every constant came from.

#include "badge_hal.h"

#include <Arduino.h>
#include <SPI.h>
#include <Wire.h>
#include <driver/rmt.h>

#include "badge_font.h"
#include "badge_pins.h"

// ---------------------------------------------------------------------------
// Buttons: 74HC165, bit-banged exactly as section 3 of the guide describes.
// ---------------------------------------------------------------------------

BadgeButtons halReadRawButtons() {
  BadgeButtons b = {};

  // 1. Latch: LOAD low, then high.
  digitalWrite(PIN_BTN_LOAD, LOW);
  delayMicroseconds(1);
  digitalWrite(PIN_BTN_LOAD, HIGH);

  // 2. Shift out eight bits: sample, then pulse CLK.
  uint8_t bits = 0;
  for (uint8_t i = 0; i < 8; i++) {
    bits = (uint8_t)((bits << 1) | (digitalRead(PIN_BTN_DATA) == HIGH ? 1 : 0));
    digitalWrite(PIN_BTN_CLK, HIGH);
    delayMicroseconds(1);
    digitalWrite(PIN_BTN_CLK, LOW);
    delayMicroseconds(1);
  }

  // A shifts out first, so the first bit sampled is the high bit of `bits`, and Aux1 - the last - is
  // the low bit. Every input is active-low: a sampled 0 means pressed.
  b.a = ((bits >> 7) & 1) == 0;
  b.b = ((bits >> 6) & 1) == 0;
  b.home = ((bits >> 5) & 1) == 0;
  b.down = ((bits >> 4) & 1) == 0;
  b.left = ((bits >> 3) & 1) == 0;
  b.right = ((bits >> 2) & 1) == 0;
  b.up = ((bits >> 1) & 1) == 0;
  b.aux1 = ((bits >> 0) & 1) == 0;

  // Start is its own GPIO and is not behind the shift register.
  b.start = digitalRead(PIN_BTN_START) == LOW;
  return b;
}

/** The debounced state, the raw state, and how many polls each input has agreed with itself. */
static BadgeButtons gStable = {};
static BadgeButtons gCandidate = {};
static uint8_t gAgree[BADGE_BUTTON_COUNT] = {};
static uint32_t gHeldSince[BADGE_BUTTON_COUNT] = {};

static bool &buttonField(BadgeButtons &b, uint8_t index) {
  switch (index) {
    case 0: return b.a;
    case 1: return b.b;
    case 2: return b.home;
    case 3: return b.down;
    case 4: return b.left;
    case 5: return b.right;
    case 6: return b.up;
    case 7: return b.aux1;
    default: return b.start;
  }
}

void halPollButtons() {
  BadgeButtons raw = halReadRawButtons();
  for (uint8_t i = 0; i < BADGE_BUTTON_COUNT; i++) {
    if (buttonField(raw, i) == buttonField(gCandidate, i)) {
      if (gAgree[i] < 255) gAgree[i]++;
    } else {
      buttonField(gCandidate, i) = buttonField(raw, i);
      gAgree[i] = 0;
    }
    if (gAgree[i] < BUTTON_DEBOUNCE_SAMPLES) continue;

    bool now = buttonField(gCandidate, i);
    if (now == buttonField(gStable, i)) continue;
    buttonField(gStable, i) = now;
    gHeldSince[i] = now ? millis() : 0;
  }
}

const BadgeButtons &halButtons() { return gStable; }

uint32_t halButtonHoldMs(uint8_t index) {
  if (index >= BADGE_BUTTON_COUNT || gHeldSince[index] == 0) return 0;
  return millis() - gHeldSince[index];
}

const char *BadgeButtons::label(char *scratch, size_t bytes) const {
  struct Named {
    bool down;
    const char *name;
  } named[] = {
    {up, "UP"},       {down, "DOWN"}, {left, "LEFT"},   {right, "RIGHT"}, {start, "START"},
    {a, "A"},         {b, "B"},       {home, "HOME"},   {aux1, "AUX1"},
  };
  scratch[0] = '\0';
  size_t used = 0;
  for (const Named &entry : named) {
    if (!entry.down) continue;
    size_t len = strlen(entry.name);
    if (used + len + 2 >= bytes) break;
    if (used > 0) scratch[used++] = '+';
    memcpy(scratch + used, entry.name, len);
    used += len;
    scratch[used] = '\0';
  }
  return scratch;
}

// ---------------------------------------------------------------------------
// Screen: ST7789 over SPI2, raw RGB565, two-stripe style chunked writes.
// ---------------------------------------------------------------------------

// 64 pixels per chunk: enough to keep the SPI busy, small enough to live on the stack.
static uint16_t gPixelChunk[64];

static void lcdCommand(uint8_t command) {
  digitalWrite(PIN_LCD_DC, LOW);
  digitalWrite(PIN_LCD_CS, LOW);
  SPI.write(command);
  digitalWrite(PIN_LCD_CS, HIGH);
}

static void lcdData(const uint8_t *data, size_t bytes) {
  digitalWrite(PIN_LCD_DC, HIGH);
  digitalWrite(PIN_LCD_CS, LOW);
  SPI.writeBytes(data, bytes);
  digitalWrite(PIN_LCD_CS, HIGH);
}

static void lcdData8(uint8_t value) {
  digitalWrite(PIN_LCD_DC, HIGH);
  digitalWrite(PIN_LCD_CS, LOW);
  SPI.write(value);
  digitalWrite(PIN_LCD_CS, HIGH);
}

static uint8_t lcdMadctl() {
  uint8_t madctl = 0;
  if (PANEL_SWAP_XY) {
    // swap_xy(true) on a ST7789 is MADCTL's MV bit.
    madctl |= 0x20;
    // With MV set the guide's mirror(true, false) lands on the panel's other axis.
    if (PANEL_MIRROR_X) madctl |= 0x80;
    if (PANEL_MIRROR_Y) madctl |= 0x40;
  } else {
    if (PANEL_MIRROR_X) madctl |= 0x40;
    if (PANEL_MIRROR_Y) madctl |= 0x80;
  }
  if (PANEL_BGR) madctl |= 0x08;
  return madctl;
}

/**
 * One addressing window in *logical* pixels.
 *
 * With MV set, the panel's column driver counts along the logical Y axis, so the two address ranges
 * swap - which is the whole reason swap_xy is more than a MADCTL bit to whoever sets the window. Both
 * orders are here so PANEL_SWAP_XY can be flipped without touching this again.
 */
static void lcdSetWindow(int16_t x0, int16_t y0, int16_t x1, int16_t y1) {
  int16_t cas0 = PANEL_SWAP_XY ? y0 : x0;
  int16_t cas1 = PANEL_SWAP_XY ? y1 : x1;
  int16_t ras0 = PANEL_SWAP_XY ? x0 : y0;
  int16_t ras1 = PANEL_SWAP_XY ? x1 : y1;

  uint8_t window[4];
  lcdCommand(0x2A);  // CASET
  window[0] = (uint8_t)(cas0 >> 8); window[1] = (uint8_t)(cas0 & 0xFF);
  window[2] = (uint8_t)(cas1 >> 8); window[3] = (uint8_t)(cas1 & 0xFF);
  lcdData(window, 4);

  lcdCommand(0x2B);  // RASET
  window[0] = (uint8_t)(ras0 >> 8); window[1] = (uint8_t)(ras0 & 0xFF);
  window[2] = (uint8_t)(ras1 >> 8); window[3] = (uint8_t)(ras1 & 0xFF);
  lcdData(window, 4);

  lcdCommand(0x2C);  // RAMWR: everything written next is pixels
}

/** Push `count` copies of one colour, chunked so nothing large is ever on the stack. */
static void lcdPushColor(uint16_t rgb565, uint32_t count) {
  for (uint16_t i = 0; i < 64; i++) gPixelChunk[i] = rgb565;
  digitalWrite(PIN_LCD_DC, HIGH);
  digitalWrite(PIN_LCD_CS, LOW);
  while (count > 0) {
    uint16_t n = (uint16_t)(count > 64 ? 64 : count);
    SPI.writePixels(gPixelChunk, n * 2);
    count -= n;
  }
  digitalWrite(PIN_LCD_CS, HIGH);
}

static void lcdPushPixels(const uint16_t *pixels, uint32_t count) {
  digitalWrite(PIN_LCD_DC, HIGH);
  digitalWrite(PIN_LCD_CS, LOW);
  SPI.writePixels(pixels, count * 2);
  digitalWrite(PIN_LCD_CS, HIGH);
}

void lcdFill(uint16_t rgb565) {
  lcdSetWindow(0, 0, LCD_LOGICAL_W - 1, LCD_LOGICAL_H - 1);
  lcdPushColor(rgb565, (uint32_t)LCD_LOGICAL_W * LCD_LOGICAL_H);
}

/**
 * One glyph, in its own window: rows 0..7 of the cell, columns 0..advance-1.
 *
 * The cell is built in a static buffer (max 10 columns x 8 rows x scale 3 = 720 pixels) and pushed in
 * one go, which keeps the number of CS pulses per character at two instead of one per pixel.
 */
#define GLYPH_BUFFER_PIXELS (10 * 3 * 8 * 3)

static void lcdGlyph(int16_t x, int16_t y, char ch, uint16_t fg, uint16_t bg, uint8_t scale) {
  if (scale == 0) scale = 1;
  char upper = ch;
  if (upper >= 'a' && upper <= 'z') upper = (char)(upper - 'a' + 'A');
  if (upper < BADGE_FONT_FIRST || upper > BADGE_FONT_LAST) upper = ' ';

  uint8_t index = (uint8_t)(upper - BADGE_FONT_FIRST);
  uint8_t advance = BADGE_FONT_ADVANCE[index];
  if (advance == 0 || advance > 10) advance = 6;

  uint8_t width = (uint8_t)(advance * scale);
  uint8_t height = (uint8_t)(BADGE_FONT_ROWS * scale);
  if (x + width > LCD_LOGICAL_W || y + height > LCD_LOGICAL_H) return;

  static uint16_t cell[GLYPH_BUFFER_PIXELS];
  for (uint8_t column = 0; column < advance; column++) {
    uint8_t bits = BADGE_FONT_COLS[index][column];
    for (uint8_t row = 0; row < BADGE_FONT_ROWS; row++) {
      uint16_t color = (bits >> row) & 1 ? fg : bg;
      for (uint8_t dy = 0; dy < scale; dy++) {
        uint16_t *line = cell + ((size_t)(row * scale + dy) * width) + (size_t)column * scale;
        for (uint8_t dx = 0; dx < scale; dx++) line[dx] = color;
      }
    }
  }

  lcdSetWindow(x, y, (int16_t)(x + width - 1), (int16_t)(y + height - 1));
  lcdPushPixels(cell, (uint32_t)width * height);
}

uint16_t lcdTextWidth(const char *text, uint8_t scale) {
  if (scale == 0) scale = 1;
  uint16_t width = 0;
  for (const char *p = text; *p; p++) {
    char upper = (*p >= 'a' && *p <= 'z') ? (char)(*p - 'a' + 'A') : *p;
    if (upper < BADGE_FONT_FIRST || upper > BADGE_FONT_LAST) upper = ' ';
    uint8_t advance = BADGE_FONT_ADVANCE[(uint8_t)(upper - BADGE_FONT_FIRST)];
    width = (uint16_t)(width + (advance ? advance : 6) * scale);
  }
  return width;
}

void lcdText(int16_t x, int16_t y, const char *text, uint16_t fg, uint16_t bg, uint8_t scale) {
  if (scale == 0) scale = 1;
  for (const char *p = text; *p; p++) {
    uint8_t advance = 6;
    char upper = (*p >= 'a' && *p <= 'z') ? (char)(*p - 'a' + 'A') : *p;
    if (upper >= BADGE_FONT_FIRST && upper <= BADGE_FONT_LAST) {
      advance = BADGE_FONT_ADVANCE[(uint8_t)(upper - BADGE_FONT_FIRST)];
      if (advance == 0) advance = 6;
    }
    lcdGlyph(x, y, *p, fg, bg, scale);
    x = (int16_t)(x + advance * scale);
    if (x >= LCD_LOGICAL_W) return;
  }
}

// ---------------------------------------------------------------------------
// LEDs: WS2812B via RMT, 10 MHz resolution (0.1 us per tick) as the guide specifies.
// ---------------------------------------------------------------------------

#define LED_RMT_CHANNEL RMT_CHANNEL_0
#define LED_TICKS_PER_US 10

static uint8_t gLedRgb[LED_COUNT][3] = {};
static rmt_item32_t gLedItems[LED_COUNT * 24];

static void ledsEncode(uint8_t r, uint8_t g, uint8_t b, rmt_item32_t *out) {
  // GRB order, data line idle high-to-low pulses: 0 = short high, long low; 1 = long high, short low.
  uint8_t bytes[3] = {g, r, b};
  uint16_t bit = 0;
  for (uint8_t byte = 0; byte < 3; byte++) {
    for (int8_t shift = 7; shift >= 0; shift--) {
      bool one = (bytes[byte] >> shift) & 1;
      out[bit].level0 = 1;
      out[bit].duration0 = one ? (uint16_t)(0.8f * LED_TICKS_PER_US) : (uint16_t)(0.4f * LED_TICKS_PER_US);
      out[bit].level1 = 0;
      out[bit].duration1 = one ? (uint16_t)(0.45f * LED_TICKS_PER_US) : (uint16_t)(0.85f * LED_TICKS_PER_US);
      bit++;
    }
  }
}

void ledsFill(uint8_t r, uint8_t g, uint8_t b) {
  for (uint8_t i = 0; i < LED_COUNT; i++) {
    gLedRgb[i][0] = r;
    gLedRgb[i][1] = g;
    gLedRgb[i][2] = b;
  }
}

void ledsSet(uint8_t index, uint8_t r, uint8_t g, uint8_t b) {
  if (index >= LED_COUNT) return;
  gLedRgb[index][0] = r;
  gLedRgb[index][1] = g;
  gLedRgb[index][2] = b;
}

void ledsShow() {
  for (uint8_t i = 0; i < LED_COUNT; i++) {
    ledsEncode(gLedRgb[i][0], gLedRgb[i][1], gLedRgb[i][2], gLedItems + (size_t)i * 24);
  }
  rmt_write_items(LED_RMT_CHANNEL, gLedItems, LED_COUNT * 24, true);
  // The latch: the strip needs the line low for >50 us between frames, and rmt_write_items returns as
  // soon as the last item is out.
  delayMicroseconds(60);
}

// ---------------------------------------------------------------------------
// Accelerometer: one bounded probe, no polling loop (I2C is shared with the NFC chip).
// ---------------------------------------------------------------------------

static bool accelRead(uint8_t reg, uint8_t *out, size_t bytes) {
  Wire.beginTransmission(ACCEL_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((uint8_t)ACCEL_ADDR, (uint8_t)bytes) != bytes) return false;
  for (size_t i = 0; i < bytes; i++) out[i] = (uint8_t)Wire.read();
  return true;
}

bool accelProbe() {
  uint8_t who = 0;
  if (!accelRead(ACCEL_WHO_AM_I, &who, 1)) {
    Serial.println("[i2c] accelerometer did not answer WHO_AM_I (bus wedged or not fitted)");
    return false;
  }
  if (who != ACCEL_WHO_AM_I_VALUE) {
    Serial.printf("[i2c] WHO_AM_I = 0x%02X, expected 0x%02X\n", who, ACCEL_WHO_AM_I_VALUE);
    return false;
  }

  // 100 Hz, all axes on; block-data-update, little-endian, +/-2 g.
  Wire.beginTransmission(ACCEL_ADDR);
  Wire.write((uint8_t)0x20);
  Wire.write((uint8_t)0x57);
  if (Wire.endTransmission(true) != 0) return false;
  Wire.beginTransmission(ACCEL_ADDR);
  Wire.write((uint8_t)0x23);
  Wire.write((uint8_t)0x80);
  if (Wire.endTransmission(true) != 0) return false;

  // 0x28 | 0x80: OUT_X_L with auto-increment over all six output bytes.
  uint8_t raw[6] = {};
  if (!accelRead((uint8_t)0xA8, raw, 6)) {
    Serial.println("[i2c] accelerometer output read failed");
    return false;
  }
  // 12-bit left-justified: shift down 4, then 1 count = 1 mg at +/-2 g.
  int16_t x = (int16_t)(((raw[1] << 8) | raw[0]) >> 4);
  int16_t y = (int16_t)(((raw[3] << 8) | raw[2]) >> 4);
  int16_t z = (int16_t)(((raw[5] << 8) | raw[4]) >> 4);
  if (x > 2047) x -= 4096;
  if (y > 2047) y -= 4096;
  if (z > 2047) z -= 4096;
  Serial.printf("[i2c] SC7A20 ok, %d %d %d mg (WHO_AM_I 0x%02X)\n", x, y, z, who);
  return true;
}

// ---------------------------------------------------------------------------
// Bring-up
// ---------------------------------------------------------------------------

void halBegin() {
  // Shift register: LOAD and CLK are outputs, DATA is the input the register drives.
  pinMode(PIN_BTN_LOAD, OUTPUT);
  pinMode(PIN_BTN_CLK, OUTPUT);
  pinMode(PIN_BTN_DATA, INPUT);
  pinMode(PIN_BTN_START, INPUT_PULLUP);
  digitalWrite(PIN_BTN_LOAD, HIGH);
  digitalWrite(PIN_BTN_CLK, LOW);

  // Screen: reset, then the guide's init sequence.
  pinMode(PIN_LCD_CS, OUTPUT);
  pinMode(PIN_LCD_DC, OUTPUT);
  pinMode(PIN_LCD_RST, OUTPUT);
  digitalWrite(PIN_LCD_CS, HIGH);

  // SS is left to us: -1 keeps Arduino from driving GPIO2 on every transaction.
  SPI.begin(PIN_LCD_CLK, -1, PIN_LCD_MOSI, -1);
  SPI.beginTransaction(SPISettings(LCD_SPI_HZ, MSBFIRST, SPI_MODE0));

  digitalWrite(PIN_LCD_RST, LOW);
  delay(20);
  digitalWrite(PIN_LCD_RST, HIGH);
  delay(120);

  lcdCommand(0x01);  // SWRESET
  delay(150);
  lcdCommand(0x11);  // SLPOUT
  delay(120);
  lcdCommand(0x3A);  // COLMOD
  lcdData8(0x55);    // 16 bits per pixel, RGB565
  lcdCommand(0x36);  // MADCTL
  lcdData8(lcdMadctl());
  lcdCommand(0xB2);  // PORCTRL
  { const uint8_t v[] = {0x0C, 0x0C, 0x00, 0x33, 0x33}; lcdData(v, sizeof(v)); }
  lcdCommand(0xB7);  // GCTRL
  lcdData8(0x35);
  lcdCommand(0xBB);  // VCOMS
  lcdData8(0x19);
  lcdCommand(0xC0);  // LCMCTRL
  lcdData8(0x2C);
  lcdCommand(0xC2);  // VDVVRHEN
  lcdData8(0x01);
  lcdCommand(0xC3);  // VRHS
  lcdData8(0x12);
  lcdCommand(0xC4);  // VDVS
  lcdData8(0x20);
  lcdCommand(0xC6);  // FRCTRL2
  lcdData8(0x0F);
  lcdCommand(0xD0);  // PWCTRL1
  { const uint8_t v[] = {0xA4, 0xA1}; lcdData(v, sizeof(v)); }
  if (PANEL_INVERT_COLORS) lcdCommand(0x21);  // INVON - the guide's invert_color(true)
  lcdCommand(0x13);                            // NORON
  lcdCommand(0x29);                            // DISPON
  delay(20);

  lcdFill(0x0000);  // dark background, and the fill-screen test the guide's checklist asks for

  // LEDs: one RMT TX channel at 10 MHz (0.1 us per tick).
  rmt_config_t rmt = RMT_DEFAULT_CONFIG_TX((gpio_num_t)PIN_LED_DIN, LED_RMT_CHANNEL);
  rmt.clk_div = 8;  // 80 MHz APB / 8 = 10 MHz
  rmt_config(&rmt);
  rmt_driver_install(LED_RMT_CHANNEL, 0, 0);
  ledsFill(0, 0, 0);
  ledsShow();

  // I2C, shared by the accelerometer and the NFC chip. Bounded timeout: the guide is explicit that
  // this bus can wedge on droopy battery power and that nothing may wait forever on it.
  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL, I2C_HZ);
  Wire.setTimeOut(I2C_TIMEOUT_MS);
}
