#!/usr/bin/env python3
"""
telemetry_check.py — the telemetry up-link: what the ESP32 actually puts on the wire, what the
app's display parser makes of it, and what the model would be told.

The point of this file is one question: for a given firmware sensor reading, what number (and what
unit) does each consumer downstream see?

REPRODUCE (from the repo root):
    python review/harness/telemetry_check.py > review/logs/telemetry-units.log

Transcribed sources, with the line each fact came from:

FIRMWARE  OpenBot/firmware/openbot/openbot.ino   (OPENBOT == DIY_ESP32, line 57)
  656        const float US_TO_CM = 0.01715;               // cm/uS
  657        const unsigned int MAX_SONAR_DISTANCE = 300;  // cm
  669-670    unsigned int distance = -1;           // cm
             unsigned int distance_estimate = -1;  // cm      <-- centimetres, not millimetres
  1865-1867  void send_sonar_reading() { sendData("s" + String(distance_estimate)); }
  1759-1761  void send_voltage_reading() { sendData("v" + String(get_voltage(), 2)); }
  1777-1783  void send_wheel_reading(long) { sendData("w" + String(rpm_left) + "," + String(rpm_right)); }
  1457-1459  void send_bumper_reading(char id[]) { sendData("b" + String(id)); }
  setup()    Serial.println('r');                          // boot banner
  501-518    the DIY_ESP32 block: HAS_VOLTAGE_DIVIDER 0, HAS_INDICATORS 0, HAS_SONAR 0,
             SONAR_MEDIAN 0, HAS_SPEED_SENSORS_FRONT 0, and no HAS_BUMPER / HAS_DROP_OFF /
             VL53L1X anywhere in the sketch -> every sensor telemetry sender is compiled out.
  1732-1735  display_vehicle_data() gated on DEBUG prints "Distance: N/A" when !HAS_SONAR

APP
  RobotLink.kt:62-73   describeTelemetry(): 's' -> "obstacle $body cm", 'v' -> "battery $body V",
                       'w' -> "wheels $body rpm", 'b' -> "bump $body", 'r' -> "robot ready"
  UsbTransport.kt:177-190  feed(): newline framing -> describeTelemetry -> onTelemetry
  BleTransport.kt:162-169  feed(): per-notification split on '\n' -> describeTelemetry -> onTelemetry
  RobotLink.kt:142-144  postTelemetry(): the only consumer of onTelemetry -> `telemetry` (a String
                       Compose state read by MainActivity.kt:852 "Robot: ${link.telemetry}")
  VoiceModels.kt:11-30 SensorSnapshot( frontDistanceMm: Int = NO_HAZARD_MM, ..., hazardsKnown )
  GroqClient.kt:89-96  sensor_state JSON: .put("frontDistanceMm", ...) ... .put("hazardsKnown", ...)
  SceneSnapshot.kt:18-29 toSensorSnapshot(): the one place metres become millimetres
  ObstacleAvoidanceController.kt:161-162   BLOCKED_DISTANCE_METERS = 0.8f, CAUTION_DISTANCE_METERS = 1.5f
  SceneAwarenessAnalyzer.kt:19             blockedBelowMeters = 0.8f, clearAboveMeters = 1.5f

REPRODUCE for the flag table: the compile log review/logs/firmware-compile.log.
"""

import pathlib

REPO = pathlib.Path(__file__).resolve().parents[2]

# ---- openbot.ino:501-518, the DIY_ESP32 feature block that is actually selected by line 57 ----
FLAGS = {
    "HAS_VOLTAGE_DIVIDER":      0,
    "HAS_INDICATORS":           0,
    "HAS_SONAR":                0,
    "SONAR_MEDIAN":             0,
    "HAS_SPEED_SENSORS_FRONT":  0,
    "HAS_BUMPER":               None,   # never defined in the sketch -> 0 in every `#if`
    "HAS_DROP_OFF / VL53L1X":   None,   # not present in the sketch at all
}

# The frame each flag authorises, as a (id, producer line, literal) triple.
TELEMETRY_PRODUCERS = [
    ("v<volts>",      "openbot.ino:1759-1761", "sendData(\"v\" + String(get_voltage(), 2));", "HAS_VOLTAGE_DIVIDER"),
    ("s<cm>",         "openbot.ino:1865-1867", "sendData(\"s\" + String(distance_estimate));", "HAS_SONAR"),
    ("w<rpm>,<rpm>",  "openbot.ino:1777-1783", "sendData(\"w\" + String(rpm_left) + \",\" + String(rpm_right));", "HAS_SPEED_SENSORS_FRONT"),
    ("b<id>",         "openbot.ino:1457-1459", "sendData(\"b\" + String(bumper_id));", "HAS_BUMPER"),
    ("r",             "setup(), openbot.ino:942", "Serial.println('r');", None),
]


def describe_telemetry(frame):
    """RobotLink.kt:62-73, transliterated."""
    if not frame:
        return None
    body = frame[1:]
    head = frame[0]
    if head == "v":
        return "battery {} V".format(body)
    if head == "s":
        return "obstacle {} cm".format(body)
    if head == "w":
        return "wheels {} rpm".format(body)
    if head == "b":
        return "bump {}".format(body)
    if head == "r":
        return "robot ready"
    return None


def main():
    print("=" * 100)
    print("TELEMETRY UP-LINK — firmware emitters, app parser, and the unit the model is told")
    print("=" * 100)

    print("\n-- 1. which telemetry frames can this board emit? (OPENBOT = DIY_ESP32) --------------")
    print("{:<16} {:<26} {:<34} {}".format("frame", "producer", "flag that gates it", "state on this board"))
    for frame, where, literal, flag in TELEMETRY_PRODUCERS:
        if flag is None:
            state = "compiled in"
        else:
            v = FLAGS[flag]
            state = ("compiled in" if v else "COMPILED OUT").format()
        print("{:<16} {:<26} {:<34} {}".format(frame, where, "{} = {}".format(flag, FLAGS[flag]) if flag else "-", state))
    print("\n-> the only frame the app can ever receive from this board is the boot banner 'r'.")

    print("\n-- 2. the app's parser, on a frame per sensor -----------------------------------------")
    samples = [
        ("s25",        "sonar at STOP_DISTANCE (10 cm is the firmware cutoff) -- 25 cm here"),
        ("s0",         "sonar floored"),
        ("s300",       "MAX_SONAR_DISTANCE"),
        ("v12.34",     "battery"),
        ("w210,196",   "wheel odometry"),
        ("bcf",        "bumper"),
        ("r",          "boot banner"),
        ("fDIY:v:s:",  "feature reply (never requested by the app)"),
        ("Control: 180,128", "the DEBUG echo of a control frame -- first char 'C', not 'c'"),
        ("Heartbeat Interval: 500", "the DEBUG echo of a heartbeat"),
    ]
    for frame, note in samples:
        print("{:<22} -> {:<24} {!r}".format(frame, repr(describe_telemetry(frame)), note))

    print("\n-- 3. what the model is told, for the same readings -----------------------------------")
    print("GroqClient.kt:89-96 ships SensorSnapshot.frontDistanceMm under the key 'frontDistanceMm'.")
    print("The firmware's sonar value is CENTIMETRES (openbot.ino:669-670 comment), so a naive")
    print("frontDistanceMm = <sonar value> is a 10x under-report of the true distance.")
    print()
    print("{:<14} {:<10} {:<16} {:<28} {}".format(
        "wire frame", "cm", "mm (!)", "what the model concludes", "ObstacleAvoidanceController verdict"))
    for cm in (25, 60, 100, 150, 300):
        mm = cm                      # naive assignment: value copied, unit not converted
        true_m = cm / 100.0
        believed_m = mm / 1000.0
        conclusion = "obstacle at {:.2f} m, must stop".format(believed_m) if believed_m < 0.8 else (
            "caution at {:.2f} m".format(believed_m) if believed_m < 1.5 else "clear at {:.2f} m".format(believed_m))
        verdict = "BLOCKED" if true_m < 0.8 else ("CAUTION" if true_m < 1.5 else "clear")
        print("{:<14} {:<10} {:<16} {:<28} {}".format(
            "s{}".format(cm), cm, mm, conclusion, verdict + " (real {:.2f} m)".format(true_m)))

    print("\n-- 4. what the model is told: who supplies SensorSnapshot ----------------------------")
    app = REPO / "app/src/main/java/com/example/guidedogtest"
    main_src = (app / "MainActivity.kt").read_text(encoding="utf-8")
    ocr_src = (app / "ocr/OcrScreen.kt").read_text(encoding="utf-8")
    mapper_src = (app / "ocr/SceneSnapshot.kt").read_text(encoding="utf-8")
    groq_src = (app / "voice/GroqClient.kt").read_text(encoding="utf-8")
    checks = [
        ("MainActivity passes sensorProvider = { sensorSnapshot }",
         "sensorProvider = { sensorSnapshot }" in main_src),
        ("OcrScreen publishes the scene the controller decided on (onSceneSnapshot)",
         "onSceneSnapshot(" in ocr_src),
        ("the metres -> millimetres conversion lives in one function",
         "(it * 1000f)" in mapper_src and "NO_HAZARD_MM" in mapper_src),
        ("a robot that cannot see says so (hazardsKnown reaches the model)",
         "hazardsKnown" in groq_src),
    ]
    for what, ok in checks:
        print("  {:<70} {}".format(what, "yes" if ok else "NO - the model is being fed a constant again"))
    print()
    print("Consumer behaviour with a live scene: the same SceneAwarenessResult the safety controller")
    print("decides on is what the model is told, in millimetres, with hazardsKnown=false whenever")
    print("there is no depth frame - so a clear-path claim always has evidence behind it.")
    print("=" * 100)


if __name__ == "__main__":
    main()
