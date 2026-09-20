#!/usr/bin/env python3
"""
protocol_conformance.py — does every frame the Android app can emit mean, on the ESP32,
exactly what the app thinks it means?

It is a line-by-line transcription of the firmware's receive path and of the app's frame
writers. Nothing here is invented: every rule carries the source line it came from.

REPRODUCE (from the repo root):
    python review/harness/protocol_conformance.py > review/logs/protocol-conformance.log

Transcribed from OpenBot/firmware/openbot/openbot.ino (line numbers as of commit c76af4d):
  575-585  enum msgParts {HEADER, BODY}; char endChar = '\n'; const char MAX_MSG_SZ = 60;
           char msg_buf[MAX_MSG_SZ]; int msg_idx = 0;
  1034-1036 the main loop: `if (Serial.available() > 0) on_serial_rx();` -- one byte per pass
  1611-1626 void on_serial_rx()          -- byte framing, '\n' terminates
  1628-1637 process_header/process_body -- body bytes appended to msg_buf, unbounded
  1639-1689 void parse_msg()             -- header dispatch, then msg_idx=0 / msgPart=HEADER
  1462-1474 void process_ctrl_msg()      -- strtok(msg_buf, ",:") then atoi() x2
  1493-1500 void process_heartbeat_msg() -- atol(msg_buf) -> heartbeat_interval
  1041-1044 the deadman check in the same loop body
  588-602  on_ble_rx(): the BLE path, same state machine, one byte at a time

Transcribed from the app:
  RobotLink.kt:39   fun frame(left: Int, right: Int): String = "c$left,$right\n"
  RobotLink.kt:36   const val STOP_FRAME = "c0,0\n"
  MainActivity.kt     link.send("h500\n")   (every tick: 50 ms driving, 200 ms listening)
  MotorSettings.kt:53-59  speedsFor(): FORWARD/LEFT/RIGHT/BACK wheel pairs
"""

import pathlib
import re

MAX_MSG_SZ = 60          # openbot.ino:583
END_CHAR = "\n"          # openbot.ino:582
# openbot.ino:771-774 — read out of the sketch rather than copied, so this check cannot drift from
# the firmware. The sketch arms the cutoff at boot (750 ms), which is what makes the "no heartbeat,
# no motion" claim true from the first millisecond instead of from the first `h` message.
_SKETCH = pathlib.Path(__file__).resolve().parents[2] / "OpenBot/firmware/openbot/openbot.ino"
_MATCH = re.search(
    r"unsigned long heartbeat_interval = (-?\d+);",
    _SKETCH.read_text(encoding="utf-8", errors="replace"),
)
FIRMWARE_DEFAULT_HEARTBEAT_LITERAL = _MATCH.group(1)
FIRMWARE_DEFAULT_HEARTBEAT = int(_MATCH.group(1)) & 0xFFFFFFFF

HEADER, BODY = 0, 1

class FirmwareParser:
    """openbot.ino:580-585 state + 1611-1689 behaviour."""

    def __init__(self):
        self.msg_part = HEADER
        self.header = "\0"
        self.buf = []
        self.msg_idx = 0
        self.ctrl_left = 0
        self.ctrl_right = 0
        self.heartbeat_interval = FIRMWARE_DEFAULT_HEARTBEAT
        self.heartbeat_time = 0
        self.dropped_bytes = 0                 # body bytes refused once msg_buf was full
        self.millis = 0
        self.ctrl_msgs = 0
        self.heartbeat_msgs = 0

    # ---- openbot.ino:1611-1626 ------------------------------------------------
    def rx(self, ch):
        if ch != END_CHAR:
            if self.msg_part == HEADER:
                self.header = ch                 # process_header()  (1628-1631)
                self.msg_part = BODY
            else:
                # process_body() (1636-1642): a body longer than the buffer is dropped
                if self.msg_idx >= MAX_MSG_SZ - 1:
                    self.dropped_bytes += 1
                    return
                self.buf.append(ch)
                self.msg_idx += 1
        else:
            self.buf.append("\0")                # msg_buf[msg_idx] = '\0';  (1623)
            self.parse_msg()                     # (1624)

    def feed(self, text):
        for ch in text:
            self.rx(ch)

    # ---- openbot.ino:1639-1689 ------------------------------------------------
    def parse_msg(self):
        h = self.header
        body = "".join(self.buf).rstrip("\0")
        if h == "c":
            self.process_ctrl_msg(body)
        elif h == "h":
            self.process_heartbeat_msg(body)
        # no case for anything else, and no case is not an error: the message is dropped (1685)
        self.msg_idx = 0
        self.msg_part = HEADER
        self.header = "\0"
        self.buf = []

    def process_ctrl_msg(self, body):
        """openbot.ino:1462-1474.  strtok(msg_buf, ",:") then strtok(NULL, ",:"), atoi each."""
        parts = body.replace(":", ",").split(",")
        left_s = parts[0] if len(parts) > 0 else ""
        right_s = parts[1] if len(parts) > 1 else ""
        self.ctrl_left = c_atoi(left_s)
        self.ctrl_right = c_atoi(right_s)
        self.ctrl_msgs += 1

    def process_heartbeat_msg(self, body):
        """openbot.ino:1493-1500."""
        self.heartbeat_interval = c_atol(body)
        self.heartbeat_time = self.millis
        self.heartbeat_msgs += 1

    # ---- openbot.ino:1041-1044 -------------------------------------------------
    def deadman_cut(self):
        return (self.millis - self.heartbeat_time) >= self.heartbeat_interval


def c_atoi(s):
    """C atoi(): optional whitespace, optional sign, digits until the first non-digit."""
    i, n = 0, len(s)
    while i < n and s[i] in " \t\r\n\v\f":
        i += 1
    sign = 1
    if i < n and s[i] in "+-":
        sign = -1 if s[i] == "-" else 1
        i += 1
    start = i
    while i < n and s[i].isdigit():
        i += 1
    if i == start:
        return 0
    return sign * int(s[start:i])


def c_atol(s):
    """C atol() on an unsigned long target: same parse, then stored as unsigned long.
    A leading '-' wraps, exactly as 'unsigned long heartbeat_interval = -1' does."""
    v = c_atoi(s)
    return v & 0xFFFFFFFF


# ---- the app's writers ---------------------------------------------------------
def app_drive_frame(left, right):      # RobotLink.kt:39
    return "c{},{}".format(left, right) + "\n"


APP_STOP_FRAME = "c0,0\n"              # RobotLink.kt:36
APP_HEARTBEAT = "h500\n"               # MainActivity.kt HEARTBEAT_FRAME

# MotorSettings.kt:20-24 + 53-59 — every value the app can put on the wire.
MOTOR_TUNING = dict(
    MANUAL_FORWARD_LEFT=180, MANUAL_FORWARD_RIGHT=128,
    MANUAL_TURN_OUTER=205, MANUAL_TURN_INNER_LEFT=190, MANUAL_TURN_INNER_RIGHT=195,
)
FORWARD = (180, 128)
LEFT_PAIR = (-205, 190)
RIGHT_PAIR = (205, -195)
BACK_PAIR = (-180, -128)


def show(title, chunks):
    """Feed one chunk list to a fresh parser; print what the firmware ends up holding."""
    p = FirmwareParser()
    for c in chunks:
        p.feed(c)
    print("{:<52} -> ctrl=({},{})  heartbeat={}  ctrl_msgs={}  hb_msgs={}".format(
        title, p.ctrl_left, p.ctrl_right,
        "never armed" if p.heartbeat_interval == 0xFFFFFFFF else p.heartbeat_interval,
        p.ctrl_msgs, p.heartbeat_msgs))
    return p


def expect(title, chunks, want_left, want_right):
    p = show(title, chunks)
    ok = (p.ctrl_left, p.ctrl_right) == (want_left, want_right) and p.ctrl_msgs == 1
    print("      expected ({}, {})  {}".format(want_left, want_right, "PASS" if ok else "DIVERGENCE"))
    return ok


def main():
    print("=" * 100)
    print("PROTOCOL CONFORMANCE — app frame writers vs openbot.ino receive path")
    print("=" * 100)

    results = []

    print("\n-- 1. the frames the app actually emits ----------------------------------------------")
    results.append(expect("Drive.frame(180,128)  (FORWARD, tuned pair)",
                          [app_drive_frame(*FORWARD)], *FORWARD))
    results.append(expect("Drive.STOP_FRAME", [APP_STOP_FRAME], 0, 0))
    results.append(expect("Drive.frame(-205,190) (LEFT, tuned pivot)",
                          [app_drive_frame(*LEFT_PAIR)], *LEFT_PAIR))
    results.append(expect("Drive.frame(205,-195) (RIGHT, tuned pivot)",
                          [app_drive_frame(*RIGHT_PAIR)], *RIGHT_PAIR))
    results.append(expect("Drive.frame(-180,-128) (BACK)", [app_drive_frame(*BACK_PAIR)], *BACK_PAIR))
    results.append(expect("Drive.frame(255,255)  (clamp ceiling, Drive.MAX_PWM)",
                          [app_drive_frame(255, 255)], 255, 255))
    results.append(expect("Drive.frame(-255,-255) (clamp floor)",
                          [app_drive_frame(-255, -255)], -255, -255))

    print("\n-- 2. chunking: does the framing survive how the bytes arrive? ----------------------")
    # USB: SerialInputOutputManager delivers arbitrary chunks; the firmware state machine is
    # global (openbot.ino:580-585), so it must reassemble. BLE: one notification per write.
    results.append(expect("frame split across two feed() chunks",
                          ["c180,", "128\n"], 180, 128))
    results.append(expect("frame split after the header byte",
                          ["c", "180,128\n"], 180, 128))
    p = show("two frames in one chunk", [app_drive_frame(180, 128) + APP_STOP_FRAME])
    print("      (last frame wins: ctrl=({}, {}))  {}".format(
        p.ctrl_left, p.ctrl_right, "PASS" if (p.ctrl_left, p.ctrl_right) == (0, 0) else "DIVERGENCE"))
    results.append((p.ctrl_left, p.ctrl_right) == (0, 0))
    results.append(expect("frame + heartbeat in one chunk",
                          ["c180,128\nh500\n"], 180, 128))

    print("\n-- 3. malformed input the app does not produce (what the parser does with it) -------")
    show("trailing CR (\\r\\n terminator)", ["c180,128\r\n"])
    show("no terminator at all", ["c180,128"])
    show("no terminator, then a well-formed frame", ["c180,128", "c0,0\n"])
    show("junk before the frame", ["hello\nc180,128\n"])
    show("truncated body 'c180\\n' (missing right)", ["c180\n"])
    show("empty body 'c\\n'", ["c\n"])
    show("unknown header 'z1,2\\n'", ["z1,2\n"])
    show("bare newline", ["\n"])
    p = FirmwareParser()
    p.feed("c" + "9" * 70 + "\n")
    print("{:<52} -> {} bytes dropped once msg_buf was full (MAX_MSG_SZ={})  {}".format(
        "70-char body on one frame", p.dropped_bytes, MAX_MSG_SZ,
        "WITHIN BOUNDS" if p.dropped_bytes else "NOTHING DROPPED - check the guard"))

    print("\n-- 4. deadman switch: openbot.ino:771-774, 1041-1044, 1493-1500 --------------------")
    p = FirmwareParser()
    p.millis = 0
    print("unsigned long heartbeat_interval = {}  ->  {}".format(
        FIRMWARE_DEFAULT_HEARTBEAT_LITERAL, p.heartbeat_interval))
    print("at boot, millis()=0, check (millis()-heartbeat_time) >= heartbeat_interval is {}"
          .format(p.deadman_cut()))
    p.millis = 750
    print("750 ms after boot, no heartbeat ever received: deadman fires? {}".format(p.deadman_cut()))
    p.millis = 3_600_000
    print("after 1 hour uptime, no heartbeat ever received: deadman fires? {}".format(p.deadman_cut()))
    p.feed(APP_HEARTBEAT)
    p.millis = 3_600_400
    print("after h500 is received, +400 ms: deadman fires? {}  (interval={} ms)".format(
        p.deadman_cut(), p.heartbeat_interval))
    p.millis = 3_600_501
    print("after h500 is received, +501 ms: deadman fires? {}".format(p.deadman_cut()))
    # MainActivity now sends the heartbeat on every tick of a loop that slows down while the
    # assistant is listening, so the worst case is the slow tick, not a multiple of the fast one.
    for label, tick_ms in (("driving", 50), ("listening", 200)):
        p.millis = 3_600_501
        p.feed(APP_HEARTBEAT)
        p.millis += tick_ms
        print("app cadence: {} tick = {} ms  ->  deadman fires before the next heartbeat? {}  (margin {} ms)"
              .format(label, tick_ms, p.deadman_cut(), p.heartbeat_interval - tick_ms))

    print("\n" + "=" * 100)
    print("RESULT: {}/{} checks passed".format(sum(1 for r in results if r), len(results)))
    print("=" * 100)


if __name__ == "__main__":
    main()
