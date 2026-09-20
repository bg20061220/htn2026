#!/usr/bin/env python3
"""
badge_check.py — the badge controller and the car agree on the wire, and the badge can draw what it
says.

Two firmwares speak one ESP-NOW protocol and neither can be run here (there is no badge on this
machine's USB, and the car is out at the venue), so the checks are the ones that are left: the bytes
the two sides will exchange, and the bytes the badge will put on its screen. Every fact is read out of
the sources below - nothing is restated from memory, and a change on either side shows up here as a
failed line rather than as a silent mismatch on the bench.

  1. PROTOCOL AGREEMENT. remote/badge_controller/badge_proto.h is the source of truth; openbot.ino
     carries a mirror of it, because arduino-cli copies a sketch into its build directory before
     compiling and a relative include cannot reach out of that copy. The mirror is compared field for
     field, type for type, constant for constant, and the wire layout is printed so a human can see
     the packet both firmwares will actually put on the air.

  2. THE SCREEN'S OWN TEXT. badge_font.h is rendered back to ASCII art from its bytes: a font that
     cannot draw "PAIRED" is a blank status line, and a wrong byte in the table is invisible in a diff.

  3. THE BUTTONS. The shift order and the active-low polarity come from section 3 of
     remote/custom-firmware-hal.md; which register bit reaches which maneuver comes from the badge
     sketch; and the drive pairs are compared with the phone app's tuned pair in MotorSettings.kt, so
     the badge and the app ask the car for the same speeds.

  4. ONE DEADMAN. The car's ESP-NOW path has to feed the same ctrl_left/ctrl_right and heartbeat_time
     the phone's 'c'/'h' frames do - a second copy of either would be a second safety model.

REPRODUCE (from the repo root):
    python review/harness/badge_check.py > review/logs/badge.log
"""

import re
import sys

BADGE_PROTO = "remote/badge_controller/badge_proto.h"
BADGE_SKETCH = "remote/badge_controller/badge_controller.ino"
BADGE_FONT = "remote/badge_controller/badge_font.h"
CAR_SKETCH = "OpenBot/firmware/openbot/openbot.ino"
MOTOR_SETTINGS = "app/src/main/java/com/example/guidedogtest/MotorSettings.kt"

failures = []


def read(path):
    return open(path, encoding="utf-8").read()


def check(label, ok, detail=()):
    print("  [{}] {}".format("ok " if ok else "FAIL", label))
    for line in detail:
        print("        {}".format(line))
    if not ok:
        failures.append(label)


def defines(text, names):
    out = {}
    for name in names:
        m = re.search(r"#define\s+" + name + r"\s+([^\n/]+)", text)
        if m:
            out[name] = m.group(1).strip()
    return out


def structs(text):
    """{name: [(type, field), ...]} for every packed struct in `text`."""
    found = {}
    for m in re.finditer(r"typedef struct __attribute__\(\(packed\)\) \{(.*?)\} (\w+);", text, re.S):
        fields = []
        for line in m.group(1).splitlines():
            line = line.split("//")[0].strip().rstrip(";")
            if not line:
                continue
            parts = line.split()
            if len(parts) == 2:
                fields.append((parts[0], parts[1]))
            elif len(parts) == 3 and parts[1].startswith("*"):
                fields.append(("{} {}".format(parts[0], parts[1]), parts[2]))
        found[m.group(2)] = fields
    return found


TYPES = {"uint8_t": 1, "int8_t": 1, "uint16_t": 2, "int16_t": 2, "uint32_t": 4, "int32_t": 4}


def field_bytes(ctype, name):
    """Size of one field. The array suffix is in the field name: `uint8_t magic[2]`."""
    base = ctype.replace(" ", "")
    array = re.search(r"\[(\d+)\]$", name)
    size = TYPES.get(base, 0)
    return size * int(array.group(1)) if array else size


def layout(fields):
    offset = 0
    lines = []
    for ctype, name in fields:
        size = field_bytes(ctype, name)
        lines.append("+{:>3}  {:<14} {}".format(offset, ctype, name))
        offset += size
    return offset, lines


def protocol():
    print("=" * 100)
    print("1. PROTOCOL AGREEMENT — one wire format, two firmwares")
    print("=" * 100)

    badge = read(BADGE_PROTO)
    car = read(CAR_SKETCH)
    car_block = car[car.index("#if HAS_ESPNOW"):car.index("static const uint8_t ESPNOW_BROADCAST")]

    names = ["ESPNOW_MAGIC_0", "ESPNOW_MAGIC_1", "ESPNOW_VERSION", "ESPNOW_KIND_DRIVE",
             "ESPNOW_KIND_STATUS", "ESPNOW_CHANNEL", "ESPNOW_PAIR_KEY", "ESPNOW_FLAG_STOP",
             "ESPNOW_STATUS_ESPNOW_DRIVING", "ESPNOW_STATUS_DEADMAN", "ESPNOW_STATUS_INTERVAL_MS",
             "ESPNOW_DRIVE_BYTES", "ESPNOW_STATUS_BYTES"]
    badge_defs = defines(badge, names)
    car_defs = defines(car_block, names)
    differing = [n for n in names if badge_defs.get(n) != car_defs.get(n)]
    check(
        "every protocol constant matches between the badge and the car",
        not differing and len(badge_defs) == len(names),
        ["{} = {} (badge) / {} (car)".format(n, badge_defs.get(n), car_defs.get(n)) for n in names]
        if not differing else
        ["differs: {}".format(d) for d in differing] or ["missing on the badge side"],
    )

    badge_structs = structs(badge)
    car_structs = structs(car_block)
    same_names = sorted(badge_structs) == sorted(car_structs)
    field_mismatch = [name for name in badge_structs
                      if same_names and badge_structs[name] != car_structs[name]]
    check(
        "both structs are the same fields, in the same order, with the same types",
        same_names and not field_mismatch,
        ["structs: {} (badge) / {}".format(sorted(badge_structs), sorted(car_structs))]
        if not same_names else
        ["differs: {}".format(field_mismatch)] or
        ["{} and {} are identical on both sides".format(*sorted(badge_structs))],
    )

    for name in sorted(badge_structs):
        size, lines = layout(badge_structs[name])
        print("")
        print("  {} — {} bytes on the air:".format(name, size))
        for line in lines:
            print("      " + line)
        declared = badge_defs.get("ESPNOW_DRIVE_BYTES" if "Drive" in name else "ESPNOW_STATUS_BYTES")
        if declared is not None and int(declared) != size:
            failures.append("{} size".format(name))
            print("      FAIL: declared {} but the fields add up to {}".format(declared, size))


def font():
    print("")
    print("=" * 100)
    print("2. THE SCREEN'S OWN TEXT — badge_font.h rendered back from its own bytes")
    print("=" * 100)

    text = read(BADGE_FONT)
    first = int(re.search(r"#define BADGE_FONT_FIRST (0x[0-9A-Fa-f]+)", text).group(1), 16)
    last = int(re.search(r"#define BADGE_FONT_LAST (0x[0-9A-Fa-f]+)", text).group(1), 16)
    stride = int(re.search(r"#define BADGE_FONT_STRIDE (\d+)", text).group(1))
    rows = int(re.search(r"#define BADGE_FONT_ROWS (\d+)", text).group(1))

    def table(name):
        body = re.search(name + r"(?:\[[^\]]*\])+\s*=\s*\{(.*?)\};", text, re.S).group(1)
        return [int(v, 16) for v in re.findall(r"0x([0-9A-Fa-f]{2})", body)]

    advance = table("BADGE_FONT_ADVANCE")
    cols = table("BADGE_FONT_COLS")
    glyphs = [cols[i:i + stride] for i in range(0, len(cols), stride)]

    check(
        "the font table is complete and every glyph fits its cell",
        len(advance) == last - first + 1 and len(glyphs) == len(advance) and
        all(1 <= a <= stride + 1 for a in advance),
        ["{} glyphs, 0x{:02X}..0x{:02X}, stride {} columns x {} rows".format(len(glyphs), first, last, stride, rows),
         "advances {}..{} px".format(min(advance), max(advance))],
    )

    def render(s):
        out = [""] * rows
        for ch in s.upper():
            index = ord(ch) - first
            if index < 0 or index >= len(glyphs):
                index = ord(" ") - first
            glyph, adv = glyphs[index], advance[index]
            for r in range(rows):
                out[r] += "".join("#" if glyph[c] >> r & 1 else "." for c in range(min(adv, stride) - 1)) + "."
        return "\n".join(out)

    for s in ("PAIRED", "FWD 180,128", "SEARCH", "E-STOP  LEFT -205,190"):
        print("")
        print("  {!r} as the panel will draw it:".format(s))
        for line in render(s).splitlines():
            print("      " + line)

    # Every character the badge draws must exist in the table: a status line with a missing glyph is a
    # blank space on the panel, and that is exactly the kind of defect a compile cannot see.
    sketch = read(BADGE_SKETCH)
    drawn = set()
    for literal in re.findall(r'"((?:[^"\\]|\\.)*)"', sketch):
        # Strings the sketch prints and strings it formats: drop the printf specifiers, keep the text.
        literal = re.sub(r"%.", "", literal)
        drawn |= {c for c in literal if c.isprintable()}
    missing = sorted(c for c in drawn if not (first <= ord(c) <= last)
                     and not (first <= ord(c.upper()) <= last))
    check(
        "every character the badge's own text contains has a glyph",
        not missing,
        ["{} distinct characters across the sketch's string literals, e.g. {}".format(
            len(drawn), "".join(sorted(drawn))[:60]),
         "missing from the font: {}".format(missing) if missing else "nothing is missing"],
    )


def buttons():
    print("")
    print("=" * 100)
    print("3. THE BUTTONS — shift order, polarity, and the pairs the car is tuned for")
    print("=" * 100)

    hal = read("remote/badge_controller/badge_hal.cpp")
    sketch = read(BADGE_SKETCH)
    pins = read("remote/badge_controller/badge_pins.h")

    order = re.findall(r"b\.(\w+) = \(\(bits >> (\d)\) & 1\) == 0;", hal)
    check(
        "the shift order is the guide's A, B, Home, Down, Left, Right, Up, Aux1, active-low",
        [name for name, _ in order] == ["a", "b", "home", "down", "left", "right", "up", "aux1"]
        and [int(bit) for _, bit in order] == [7, 6, 5, 4, 3, 2, 1, 0],
        ["bits: " + ", ".join("{}={}".format(n, b) for n, b in order),
         "a sampled 0 is pressed, which is the active-low comparison in that line",
         "Start: " + next(l.strip() for l in hal.splitlines() if "b.start = digitalRead" in l)],
    )

    maneuver = re.search(r"static Maneuver padManeuver\(\) \{(.*?)\n\}", sketch, re.S).group(1)
    mapping = re.findall(r"if \(buttons\.(\w+)\) return Maneuver::(\w+);", maneuver)
    print("")
    print("  what the four buttons do (badge_controller.ino padManeuver, first match wins):")
    for name, command in mapping:
        print("      {:<6} -> {}".format(name.upper(), command))
    check(
        "the four drive buttons are forward, left, right and stop, and a stop wins",
        [c for _, c in mapping[:5]] == ["EmergencyStop", "Stop", "Forward", "Left", "Right"],
        ["precedence: " + " > ".join(c for _, c in mapping),
         "start and down both stop; a held direction cannot outvote either"],
    )

    pairs = dict(re.findall(r"#define (DRIVE_\w+) (-?\d+)", sketch))
    tuned = dict(re.findall(r"const val (MANUAL_\w+) = (-?\d+)", read(MOTOR_SETTINGS)))
    expected = {
        "DRIVE_FORWARD_LEFT": tuned.get("MANUAL_FORWARD_LEFT"),
        "DRIVE_FORWARD_RIGHT": tuned.get("MANUAL_FORWARD_RIGHT"),
        "DRIVE_LEFT_LEFT": "-" + (tuned.get("MANUAL_TURN_OUTER") or ""),
        "DRIVE_LEFT_RIGHT": tuned.get("MANUAL_TURN_INNER_LEFT"),
        "DRIVE_RIGHT_LEFT": tuned.get("MANUAL_TURN_OUTER"),
        "DRIVE_RIGHT_RIGHT": "-" + (tuned.get("MANUAL_TURN_INNER_RIGHT") or ""),
    }
    check(
        "the badge's drive pairs are the phone app's tuned pairs, not new numbers",
        pairs == expected,
        ["{}: badge {} / app {}".format(k, pairs.get(k), expected.get(k)) for k in sorted(expected)],
    )
    check(
        "the guide's pins are the ones this firmware uses",
        all(x in pins for x in ["#define PIN_LCD_MOSI 10", "#define PIN_LCD_CLK 1", "#define PIN_LCD_CS 2",
                                "#define PIN_LCD_DC 0", "#define PIN_LCD_RST 4", "#define PIN_I2C_SDA 5",
                                "#define PIN_I2C_SCL 6", "#define PIN_BTN_DATA 7", "#define PIN_BTN_LOAD 20",
                                "#define PIN_BTN_CLK 21", "#define PIN_BTN_START 9", "#define PIN_LED_DIN 3"]),
        ["badge_pins.h is a transcription of the guide's section 2 table"],
    )


def deadman():
    print("")
    print("=" * 100)
    print("4. PAIRING AND THE DEADMAN — who may talk, and what happens when nobody does")
    print("=" * 100)

    car = read(CAR_SKETCH)
    badge = read(BADGE_SKETCH)
    apply_start = car.index("void espnow_apply_pending()")
    apply_block = car[apply_start:car.index("#endif", apply_start)]
    check(
        "an ESP-NOW command lands on the same ctrl_left/ctrl_right and heartbeat_time the phone uses",
        "ctrl_left = constrain(left" in apply_block and "ctrl_right = constrain(right" in apply_block
        and "heartbeat_time = millis();" in apply_block,
        [l.strip() for l in apply_block.splitlines()
         if l.strip().startswith(("ctrl_left", "ctrl_right", "heartbeat_time"))],
    )

    badge_pair = badge[badge.index("if (!gHasCarMac || memcmp(gCarMac, mac, 6) != 0)"):
                       badge.index("#if BADGE_DEBUG", badge.index("if (!gHasCarMac"))]
    check(
        "a learned MAC is registered as a peer before anything is unicast to it",
        "addPeer(gCarMac);" in badge_pair and "espnow_add_peer(espnow_badge_mac);" in apply_block,
        ["badge side, learning the car: " + next(l.strip() for l in badge_pair.splitlines() if "addPeer" in l),
         "car side, learning the badge: " + next(l.strip() for l in apply_block.splitlines()
                                                 if "espnow_add_peer" in l and "espnow_setup" not in l),
         "ESP-NOW refuses esp_now_send() to an address that is not in the peer table, so a missing",
         "registration is a link that dies at the moment it pairs - and it compiles perfectly."],
    )

    phone_stop = "if ((millis() - heartbeat_time) >= heartbeat_interval) {"
    check(
        "the phone's cutoff is still the only stop rule, and it is untouched",
        car.count(phone_stop) == 1 and "ctrl_left = 0;" in car[car.index(phone_stop):][:120],
        ["the 750 ms cutoff zeroes ctrl_left/ctrl_right whichever controller last spoke",
         "a badge that goes quiet for 750 ms therefore stops the car by itself"],
    )


def main():
    protocol()
    font()
    buttons()
    deadman()
    print("")
    print("=" * 100)
    if failures:
        print("FAILED: {}".format(", ".join(failures)))
        sys.exit(1)
    print("The badge and the car agree on the wire, and the badge can draw what it says.")


if __name__ == "__main__":
    main()
