#!/usr/bin/env python3
"""Is the phone finding the floor, right now, from where it is mounted?

Run this on the bench or on the car *before* trusting the obstacle stop. It reads the app's own
bench lines off the phone for a few seconds and says which of the four states the depth is in, and
what to change about the aim if it is not the good one.

  python review/harness/floor_check.py            # 12 seconds, then a verdict
  python review/harness/floor_check.py --seconds 20

The four states, from the app's `Boxes` line:

  GOOD    sampled 201, depth returned N>0, support rows 3 of 3, fit ok (slope S)
          The floor is in the depth image and a plane is fitted through it. Obstacle detection is on.
  NO-FRAME  nonzero 0/14400
          ARCore has produced no depth image at all (just after the session starts, or a camera that
          cannot see). Pan the phone slowly across the room for a second or two.
  UNUSABLE  nonzero 14400/14400 (in range 0), column reading tens of metres
          Depth arrived, but nothing in it is inside the sampler's 0.15-8 m window: ARCore could not
          triangulate the view. Too steep, too close, or a low-texture floor with no camera motion.
  NO-FLOOR  in range > 0 but support rows 0 of 3, fit FAILED
          Depth is real, but no floor inside the band the boxes sample. The floor is either above or
          below the boxes' 0.30-0.87 of the picture: change the mount pitch.

Exit status is 0 for GOOD, 1 otherwise, so it can gate a run.
"""

import argparse
import os
import re
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def find_adb():
    override = os.environ.get("ADB")
    if override and os.path.exists(override):
        return override
    props = os.path.join(REPO, "local.properties")
    if os.path.isfile(props):
        for line in open(props, encoding="utf-8"):
            m = re.match(r"\s*sdk\.dir\s*=\s*(.+?)\s*$", line)
            if m:
                sdk = m.group(1).replace("\\\\", "\\").replace("\\", os.sep)
                for name in ("adb.exe", "adb"):
                    candidate = os.path.join(sdk, "platform-tools", name)
                    if os.path.exists(candidate):
                        return candidate
    return "adb"


def adb(*args, timeout=60):
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout).stdout


def last_line(tag):
    out = adb("logcat", "-d", "-s", tag)
    lines = [l for l in out.splitlines() if " D " in l or " I " in l]
    return lines[-1] if lines else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=12.0, help="how long to watch for (default 12)")
    ap.add_argument("--from-file", help="read captured logcat text instead of the phone (for testing)")
    args = ap.parse_args()

    if args.from_file:
        text = open(args.from_file, encoding="utf-8", errors="replace").read().splitlines()
        boxes = next((l for l in reversed(text) if "Boxes" in l), "")
        avoidance = next((l for l in reversed(text) if "Avoidance" in l), "")
    else:
        devices = [l for l in adb("devices").splitlines()[1:] if l.strip() and "device" in l]
        if not devices:
            print("no phone attached - plug the S21 in and allow USB debugging")
            return 1
        print("phone:", devices[0].split()[0])
        adb("logcat", "-c")
        print(f"watching the depth pipeline for {args.seconds:.0f}s ...")
        time.sleep(args.seconds)
        boxes = last_line("Boxes")
        avoidance = last_line("Avoidance")
    print()
    print("Boxes   :", boxes.split("Boxes", 1)[-1].lstrip(": ") or "(nothing - is the camera view open?)")
    print("Avoidance:", avoidance.split("Avoidance", 1)[-1].lstrip(": ") or "(nothing)")
    print()

    if not boxes:
        print("VERDICT: NO LOGS - open CAMERA VIEW (or say \"goose, go forward\") and run this again.")
        return 1

    nonzero = re.search(r"nonzero (\d+)/(\d+)", boxes)
    in_range = re.search(r"in range (\d+)", boxes)
    returned = re.search(r"sampled (\d+), depth returned (\d+)", boxes)
    fit_ok = "fit ok" in boxes
    rows = re.search(r"support rows (\d+) of (\d+)", boxes)
    slope = re.search(r"slope ([\d.]+)", boxes)
    depths = re.search(r"depths ([\d.,]+),", boxes)
    column = re.search(r"column ([^|]+)", boxes)

    if nonzero and int(nonzero.group(1)) == 0:
        print("VERDICT: NO-FRAME - ARCore has produced no depth image at all.")
        print("  do: pan the phone slowly across the room for a second or two, then re-run.")
        return 1
    if in_range and int(in_range.group(1)) == 0:
        print("VERDICT: UNUSABLE - depth arrived but nothing in it is inside the 0.15-8 m window.")
        if column:
            print("  column:", column.group(1).strip())
        print("  do: pitch the camera UP towards the path (aim ~20-25 degrees down, floor 1.5-3 m")
        print("      ahead), and give it camera motion. Close-range low-texture floor is the worst case.")
        return 1
    if not fit_ok:
        print("VERDICT: NO-FLOOR - depth is real but no floor inside the boxes' band.")
        if rows:
            print(f"  support rows {rows.group(1)} of {rows.group(2)}")
        print("  do: the floor is above or below the box band (0.30-0.87 of the picture):"),
        print("      the floor should sit at ~0.65-0.90 of the frame, 1.5-3 m ahead.")
        return 1

    print("VERDICT: GOOD - the floor is in the depth image and a plane is fitted through it.")
    if rows:
        print(f"  support rows {rows.group(1)} of {rows.group(2)}", end="")
    if depths:
        print(f", floor at {depths.group(1)} m", end="")
    if slope:
        print(f", slope {slope.group(1)}", end="")
    print()
    if returned:
        print(f"  sampled {returned.group(1)}, depth returned {returned.group(2)}")
    print("  obstacle detection is on: anything in the middle box now stops the car.")
    return 0


if __name__ == "__main__":
    ADB = find_adb()
    sys.exit(main())
