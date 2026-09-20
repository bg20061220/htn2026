#!/usr/bin/env python3
"""
confirmation_match_check.py — does the spoken yes/no answer to "Did you mean X?" land on the answer
the user gave?

The word lists are read out of the real source file, not retyped, so this cannot drift from the code.
The predicate is the transcribed body of ConversationManager.handleNavigationConfirmation.

REPRODUCE (from the repo root):
    python review/harness/confirmation_match_check.py > review/logs/confirmation-match.log

Transcribed from app/src/main/java/com/example/guidedogtest/voice/ConversationManager.kt:
  32     private val CONFIRM_WORDS = listOf("yes", "yeah", "yep", "yup", "correct", "right", "sure", "please", "confirm")
  33     private val DENY_WORDS    = listOf("no", "nope", "not", "wrong", "cancel", "nevermind", "never mind")
  300-306  val answer = transcript.trim().lowercase()
           val denied = DENY_WORDS.any { answer.contains(it) }
           val confirmed = !denied && CONFIRM_WORDS.any { answer.contains(it) }
           when {
             confirmed -> { ... RoutesApi.fetchRoute(...) ... speak("Okay, heading to <place>...") }
             denied -> { speak("Okay, cancelled. Where would you like to go?") }
             else -> { speak("Sorry, was that a yes or a no?") }
           }
  295      the question that produces this answer: speak("Did you mean <name>? Say yes or no.")

Denial wins, and that is the whole point of the check below: CONFIRM_WORDS holds "right" and
DENY_WORDS holds "not"/"wrong", so a refusal phrased the way people actually phrase it ("no, that's
not right") contains a confirmation word. With `confirmed` tested first, that answer fetched a route
to the place the user had just rejected and announced it as the destination.
"""

import re
import sys

SRC = "app/src/main/java/com/example/guidedogtest/voice/ConversationManager.kt"


def read_list(name):
    text = open(SRC, encoding="utf-8").read()
    m = re.search(r"private val " + name + r"\s*=\s*listOf\(([^)]*)\)", text)
    if not m:
        sys.exit("could not read {} from {}".format(name, SRC))
    return [s for s in re.findall(r'"([^"]*)"', m.group(1))]


def classify(answer, confirm, deny):
    a = answer.strip().lower()
    # ConversationManager.kt:305-306, in this order: a refusal is never read as agreement.
    denied = any(w in a for w in deny)
    confirmed = (not denied) and any(w in a for w in confirm)
    if confirmed:
        return "CONFIRMED -> route fetched, 'Okay, heading to <place>'"
    if denied:
        return "DENIED    -> 'Okay, cancelled.'"
    return "UNCLEAR   -> 'Sorry, was that a yes or a no?'"


def main():
    confirm = read_list("CONFIRM_WORDS")
    deny = read_list("DENY_WORDS")
    print("=" * 100)
    print("CONFIRMATION MATCHING — CONFIRM_WORDS vs DENY_WORDS, both read from {}".format(SRC))
    print("=" * 100)
    print("CONFIRM_WORDS = {}".format(confirm))
    print("DENY_WORDS    = {}".format(deny))
    print("")
    print("The user is answering: 'Did you mean <place>? Say yes or no.'  (ConversationManager.kt:295)")
    print("")
    answers = [
        "yes", "yeah", "yep", "sure", "correct", "that's right", "please",
        "no", "nope", "wrong", "cancel", "never mind",
        "no, that's not right", "not right", "no that is not right",
        "no, that's wrong", "that's not the one", "no, it's not",
        "no please", "no thanks, not that",
        "turn right", "go right",
    ]
    disagree = []
    for a in answers:
        got = classify(a, confirm, deny)
        # what a human would mean by these answers, given the question asked:
        human = "NO" if re.search(r"\b(no|nope|not|wrong|cancel|never)\b", a) else (
            "YES" if re.search(r"\b(yes|yeah|yep|yup|correct|right|sure|please|confirm)\b", a) else "?")
        bad = (human == "NO" and got.startswith("CONFIRMED")) or (human == "YES" and got.startswith("DENIED"))
        if bad:
            disagree.append(a)
        print("  {:<26} human={:<4} {}".format(repr(a), human, got))
    print("")
    print("=" * 100)
    print("answers where the machine acted against what the speaker meant: {}".format(disagree))
    print("=" * 100)
    for a in disagree:
        print("  {!r} -> {}".format(a, classify(a, confirm, deny)))
    print("")
    print("(The table above is the fixed predicate: denial is tested first, so every refusal - including")
    print("the ones that contain CONFIRM_WORDS' \"right\" - lands on the cancelled branch.)")


if __name__ == "__main__":
    main()
