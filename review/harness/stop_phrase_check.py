#!/usr/bin/env python3
"""
stop_phrase_check.py — which phrases actually stop the robot when nobody has said the wake word?

There is exactly one stop matcher in the app: EmergencyStopMatcher, run by the always-listening loop
on every partial and final transcript. This reads its phrase set out of the real source and applies
the real normalisation, so "which phrasings stop the car" is a table rather than an opinion.

The reason this file exists: a second, substring-based matcher (VoiceModels.containsStopWord) used to
sit beside it, documented as "errs towards stopping", pinned by VoiceCommandTest - and called from
nowhere in app/src/main. The suite was green on semantics no code path ran, so "stop the robot" and
"stop it" looked supported and were ignored by the robot. The helper and its assertions have been
deleted; the phrase table below is what the robot really does, and the last section proves the helper
is gone rather than asserting it.

REPRODUCE (from the repo root):
    python review/harness/stop_phrase_check.py > review/logs/stop-phrase.log

Sources, and the line each fact came from:
  EmergencyStopMatcher.kt:3-24   private val accepted = setOf(...10 exact phrases...)
                                 matches() = lowercase, strip non-[a-z] to spaces, trim, collapse
                                 spaces, then `normalized in accepted`  -> EXACT membership
  WakeWordDetector.kt:87-95      heardEmergencyStop(): `matches?.any(EmergencyStopMatcher::matches)`
                                 -- this is what the always-listening loop runs
  WakeWordDetector.kt:57-75      called from onResults (57) and onPartialResults (71), before containsWakeWord
  ConversationManager.kt:204, 209  the one-shot recognizer's own path, also EmergencyStopMatcher
                                 (SpeechCapture.kt:43-49, 61-73)
"""

import re
import subprocess
import sys

MATCHER = "app/src/main/java/com/example/guidedogtest/voice/EmergencyStopMatcher.kt"
DETECTOR = "app/src/main/java/com/example/guidedogtest/voice/WakeWordDetector.kt"


def read(path):
    return open(path, encoding="utf-8").read()


def accepted_phrases():
    m = re.search(r"private val accepted = setOf\((.*?)\)", read(MATCHER), re.S)
    if not m:
        sys.exit("could not read `accepted` from " + MATCHER)
    return [s for s in re.findall(r'"([^"]*)"', m.group(1))]


def runtime_matches(transcript, accepted):
    """EmergencyStopMatcher.matches(), transliterated from EmergencyStopMatcher.kt:17-24."""
    normalized = re.sub(r"[^a-z]+", " ", transcript.lower()).strip()
    normalized = re.sub(r"\s+", " ", normalized)
    return normalized in accepted


def main():
    accepted = accepted_phrases()
    print("=" * 100)
    print("WHICH PHRASES STOP THE ROBOT — the one matcher the runtime uses")
    print("=" * 100)
    print("EmergencyStopMatcher.accepted ({} phrases, {}):".format(len(accepted), MATCHER))
    for p in accepted:
        print("    {!r}".format(p))
    print("")
    print("runtime path: {}".format(
        [l.strip() for l in read(DETECTOR).splitlines()
         if "EmergencyStopMatcher" in l] or "(no reference)"))
    print("")
    print("phrases a user would say while the robot walks, and what the loop does with them:")
    print("")
    print("  {:<36} {}".format("spoken (recognizer output)", "always-listening loop"))
    phrases = [
        "stop", "Stop.", "STOP!", "halt", "cancel", "stop robot", "goose stop", "please stop",
        "okay stop the robot now", "stop the robot", "stop it", "stop now", "no stop",
        "goose stop the robot", "halt the robot", "please stop now",
        "goose take me to the library",
    ]
    for p in phrases:
        print("  {:<36} {}".format(repr(p), "STOPS" if runtime_matches(p, accepted) else "ignored"))
    print("=" * 100)
    print("")
    print("The matcher is exact-membership by design (commit 4831676): a substring match on \"stop\"")
    print("fires on \"stop\" inside any sentence, and this loop hears everything the room says. The")
    print("phrasings it does not catch are the trade the team chose, and they are listed above so the")
    print("choice is visible rather than assumed - the deleted helper used to claim the opposite.")
    # Prove the deletion rather than asserting it.
    for needle, scope in (("containsStopWord", "app/src"), ("STOP_WORDS", "app/src")):
        hits = subprocess.run(
            ["git", "grep", "-n", needle, "--", scope],
            capture_output=True, text=True).stdout.strip()
        print("")
        print("git grep -n {} -- {} :".format(needle, scope))
        print(hits if hits else "    (no hits: the stale helper and its test are gone)")


if __name__ == "__main__":
    main()
