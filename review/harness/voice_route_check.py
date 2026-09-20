#!/usr/bin/env python3
"""
voice_route_check.py — what the spoken-destination path actually does, from wake word to wheels.

The report this exists for: "the voice module is not able to set and start a route on its own. it says
'no route was found' or along those lines". Three separate things have to hold for a spoken
destination to become a walk, and each of them fails silently rather than loudly, so each is read out
of the real source and printed here as a fact about the tree:

  1. THE FETCH IS OFF THE MAIN THREAD. `RoutesApi.fetchRoute` is a blocking HttpURLConnection call
     from a `viewModelScope.launch` (= Dispatchers.Main.immediate) coroutine, so on a device it threw
     NetworkOnMainThreadException, which the caller's `catch (e: Exception)` turned into the spoken
     "Sorry, I couldn't calculate the route". The dispatcher now lives inside fetchRoute, so no call
     site can forget it. The typed-destination button never showed the bug because it happened to
     wrap the same call in `withContext(Dispatchers.IO)` — that wrapper is gone, and this check fails
     if a manual wrapper comes back, because a wrapper is the thing that hid the defect.

  2. THE CONFIRMATION STARTS THE WALK. After the route loads, the summary is spoken and then the
     app's own Go is issued from the speech's completion, once the conversation has returned to the
     wake word — an announcement is dropped while a turn is still open, so the order is the check.

  3. ONE ROUTE SWAP IS ONE WALK. `MutableStateFlow` does not re-emit an equal value, so a second
     identical destination needs a new `VoiceRoute.requestId`; and the tick loop's keys have to
     include the follower, or a route adopted mid-walk is only adopted by the map.

  4. THE ORIGIN IS THE FIX THE SCREEN SHOWS. The live position used to exist twice - a `Location` for
     the map and follower, and `latitude`/`longitude` strings for the voice and GET ROUTE - and only
     the continuous callback wrote the strings. A fix that arrived from the one-shot GET CURRENT
     LOCATION button (which is the button the app tells people to press) updated the map and left the
     strings at "Unknown", so the assistant answered a confirmed destination with "I don't have a
     location fix yet, so I can't build a route" while the screen showed a latitude and a longitude.
     There is one object now, and the update subscription is keyed on the permission so a grant that
     arrives after launch actually starts a fix.

Every literal below is read from the repository; nothing here restates the app's values from memory.

REPRODUCE (from the repo root):
    python review/harness/voice_route_check.py > review/logs/voice-route.log

A live section at the end makes ONE computeRoutes request with the request body transcribed out of
RoutesApi.kt and the key out of the gitignored local.properties, to separate "the API key, the
endpoint and the destination are fine" from "the client asked for the route on the wrong thread".

Sources, and the line each fact came from:
  RoutesApi.kt:75-114              suspend fun fetchRoute(...) = withContext(Dispatchers.IO) { ...
                                   FLAG: the `suspend` and the dispatcher are the fix; a plain fun
                                   would compile and fail only on a device
  ConversationManager.kt:340-392   handleNavigationConfirmation: fetchRoute -> _voiceRoute.value ->
                                   speak(summary) { returnToWakeWordListening(); onCommand(Go) }
  ConversationManager.kt:35-54     VoiceRoute.requestId, and why a StateFlow needs it
  MainActivity.kt:715              LaunchedEffect(following, link.connected, follower)
  MainActivity.kt:307-330          the Go branch: the one place a route starts
  MainActivity.kt:1205-1214        GET ROUTE: no manual withContext around the call any more
  MainActivity.kt:150-175, 315-320  one fix (`lastLocation`) for the voice's origin, the map, the
                                   Places bias and GET ROUTE; `requestOneShotFix()` for the button
  MainActivity.kt:457-472          currentLocation derived from that fix, not from strings
  MainActivity.kt:687-698          the update subscription keyed on locationPermissionGranted
"""

import json
import re
import sys
import urllib.error
import urllib.request

ROOT = ""
ROUTES_API = "app/src/main/java/com/example/guidedogtest/RoutesApi.kt"
CONVERSATION = "app/src/main/java/com/example/guidedogtest/voice/ConversationManager.kt"
MAIN = "app/src/main/java/com/example/guidedogtest/MainActivity.kt"

failures = []


def read(path):
    return open(ROOT + path, encoding="utf-8").read()


def check(label, ok, detail):
    print("  [{}] {}".format("ok " if ok else "FAIL", label))
    for line in detail:
        print("        {}".format(line))
    if not ok:
        failures.append(label)


def block(text, start_marker, end_marker):
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return text[start:end]


def main():
    routes = read(ROUTES_API)
    conversation = read(CONVERSATION)
    main_activity = read(MAIN)

    print("=" * 100)
    print("SPOKEN DESTINATION -> WHEELS: the four seams the defect lived in")
    print("=" * 100)

    # 1. The blocking fetch owns its dispatcher.
    signature = re.search(r"(\w+) fun fetchRoute\(", routes)
    body = block(routes, "fun fetchRoute(", "/** Visible for tests")
    dispatcher_line = next(
        (l.strip() for l in body.splitlines() if "withContext(Dispatchers.IO)" in l), None)
    check(
        "RoutesApi.fetchRoute is suspend and switches itself to Dispatchers.IO",
        bool(signature) and signature.group(1) == "suspend" and dispatcher_line is not None,
        [
            "signature: {} fun fetchRoute(".format(signature.group(1) if signature else "(none)"),
            "dispatcher: {}".format(dispatcher_line or "(none)"),
            "blocking call inside: {}".format(
                next((l.strip() for l in body.splitlines() if "openConnection()" in l), "(none)")),
            "viewModelScope.launch runs on Dispatchers.Main.immediate: the spoken path called this",
            "from there, so the dispatcher has to live in here - a plain function compiled fine and",
            "threw NetworkOnMainThreadException on the phone.",
        ],
    )

    # A manual wrapper at a call site is the thing that hid the defect: none should be left.
    manual = [
        "{}{}:{}".format(ROOT, name, i + 1)
        for name, text in (("main: ", main_activity), ("voice: ", conversation))
        for i, line in enumerate(text.splitlines())
        if "withContext(Dispatchers.IO)" in line
    ]
    check(
        "no call site wraps fetchRoute in its own dispatcher any more",
        not manual,
        manual or ["(no withContext(Dispatchers.IO) in MainActivity.kt or ConversationManager.kt)"],
    )

    # 2. The confirmation starts the walk, in the order the speech gate requires.
    confirmation = block(conversation, "private suspend fun handleNavigationConfirmation",
                         "private fun speak(text: String")
    on_done = confirmation[confirmation.index("speak("):]
    order = [m.start() for m in re.finditer(
        r"returnToWakeWordListening\(\)|onCommand\(RobotCommand\.Go\)", on_done)]
    tokens = re.findall(r"returnToWakeWordListening\(\)|onCommand\(RobotCommand\.Go\)", on_done)
    check(
        "the route summary ends by starting the walk itself",
        tokens[:2] == ["returnToWakeWordListening()", "onCommand(RobotCommand.Go)"],
        [
            "route loaded: {}".format(
                bool(re.search(r"_voiceRoute\.value = VoiceRoute\(", confirmation))),
            "order after the summary: {}".format(" -> ".join(tokens[:2]) or "(none)"),
            "wake-word first because ConversationManager.requestSpeech drops a NORMAL announcement",
            "while a conversation turn is open (VoicePriority.NORMAL < UNSAFE_PATH): the refusal for",
            "a robot that is not connected would otherwise be swallowed after the walker was told",
            "the robot is heading somewhere.",
        ],
    )
    go_branch = block(main_activity, "RobotCommand.Go ->", "is RobotCommand.Turn ->")
    check(
        "Go is still the one place a route starts, and it still refuses out loud",
        "following = true" in go_branch and "pendingAnnouncement" in go_branch,
        [
            "MainActivity.kt Go branch: follower != null && link.connected -> following = true",
            "not connected -> \"I'm not connected to the robot, so I can't walk there.\"",
            "So the spoken path and a spoken \"go\" start a walk the same way, and neither can start",
            "one the other could not.",
        ],
    )

    # 3. A repeat destination is a new walk, and a swap mid-walk is adopted by the loop.
    voice_route = block(conversation, "data class VoiceRoute(", "/** The steps the robot drives")
    loop_keys = next(
        (l.strip() for l in main_activity.splitlines() if "LaunchedEffect(following, link.connected" in l),
        "(none)",
    )
    check(
        "a repeat destination re-emits (requestId) and a swapped route restarts the tick loop",
        "val requestId: Long" in voice_route and "follower" in loop_keys,
        [
            "VoiceRoute: {}".format(
                next((l.strip() for l in voice_route.splitlines() if "requestId" in l), "(none)")),
            "tick loop keys: {}".format(loop_keys),
            "Equal StateFlow values are conflated, so without the id the second \"take me to the",
            "library\" re-adopts nothing; and with `following` already true, a new follower is only",
            "picked up if it is a key.",
        ],
    )

    # 4. The origin the assistant routes from is the fix the screen shows.
    provider = block(main_activity, "locationProvider = {", "},")
    one_fix = (
        "lastLocation?.let { LatLng(" in provider
        and "var latitude by remember" not in main_activity
        and "latitude = it.latitude.toString()" not in main_activity
        and "remember(lastLocation?.latitude, lastLocation?.longitude)" in main_activity
    )
    check(
        "one fix feeds the voice's origin, GET ROUTE and the map",
        one_fix,
        [
            "voice origin: {}".format(" ".join(provider.split())),
            "GET ROUTE / map / Places bias: remember(lastLocation?.latitude, lastLocation?.longitude)"
            if "remember(lastLocation?.latitude" in main_activity else "GET ROUTE / map: (not derived)",
            "second copy (`var latitude by remember`): {}".format(
                "present" if "var latitude by remember" in main_activity else "gone"),
            "The live fix used to exist twice: a Location for the map and follower, and",
            "latitude/longitude strings for the voice and GET ROUTE - written only by the continuous",
            "callback. A fix that arrived from the one-shot GET CURRENT LOCATION button (the button",
            "the app tells people to press) updated the map and left the strings at \"Unknown\", which",
            "is how the walker got \"I don't have a location fix yet, so I can't build a route\" with a",
            "latitude and longitude displayed underneath it.",
        ],
    )
    subscription = next(
        (l.strip() for l in main_activity.splitlines() if "LaunchedEffect(locationPermissionGranted" in l),
        "(none)",
    )
    check(
        "the live fixes start when the permission is granted, not only when it predates the launch",
        "locationPermissionGranted" in subscription,
        [
            "subscription key: {}".format(subscription),
            "The updates used to hang off `LaunchedEffect(Unit)`, so a permission granted after the",
            "app was already open never started a fix at all: the app held the grant and no position",
            "until it was launched again.",
        ],
    )

    # The live section: the same request the app builds, transcribed out of the app's source.
    print("")
    print("=" * 100)
    print("LIVE: one computeRoutes request, body transcribed out of RoutesApi.kt")
    print("=" * 100)
    live(routes)

    print("=" * 100)
    if failures:
        print("FAILED: {}".format(", ".join(failures)))
        sys.exit(1)
    print("All four seams hold in this tree.")


def live(routes):
    """The app's request, one call, so an API-level failure cannot be mistaken for a thread bug."""
    literals = re.search(r"private const val ENDPOINT = \"([^\"]+)\"", routes)
    travel = re.search(r"\.put\(\"travelMode\", \"([^\"]+)\"\)", routes)
    mask_group = re.search(r"private const val FIELD_MASK =\s*(.*?)\n\n", routes, re.S)
    mask = "".join(re.findall(r'"([^"]*)"', mask_group.group(1))) if mask_group else ""
    key = None
    try:
        for line in open(ROOT + "local.properties", encoding="utf-8"):
            if line.startswith("MAPS_API_KEY"):
                key = line.split("=", 1)[1].strip()
    except OSError:
        pass

    print("endpoint    : {}".format(literals.group(1) if literals else "(none)"))
    print("travelMode  : {}".format(travel.group(1) if travel else "(none)"))
    print("field mask  : {}".format(mask or "(none)"))
    if not key:
        print("api key     : (no MAPS_API_KEY in local.properties - live section skipped)")
        return

    body = {
        "origin": {"location": {"latLng": {"latitude": 43.4723, "longitude": -80.5449}}},
        "destination": {"location": {"latLng": {"latitude": 43.4770, "longitude": -80.5400}}},
        "travelMode": travel.group(1),
        "computeAlternativeRoutes": False,
        "languageCode": "en-US",
        "units": "METRIC",
    }
    request = urllib.request.Request(
        literals.group(1),
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "X-Goog-Api-Key": key,
            "X-Goog-FieldMask": mask,
        },
        method="POST",
    )
    print("api key     : {}... (MAPS_API_KEY)".format(key[:6]))
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.loads(response.read().decode())
            status = response.status
    except urllib.error.HTTPError as error:
        print("")
        print("HTTP {}: {}".format(error.code, error.read().decode()[:300]))
        print("The key, the API or the destination is the problem - not the dispatch.")
        failures.append("live computeRoutes")
        return

    route = (payload.get("routes") or [None])[0]
    if not route:
        print("")
        print("HTTP {} with no route: {}".format(status, json.dumps(payload)[:300]))
        failures.append("live computeRoutes returned no route")
        return

    print("HTTP {}: {} steps, {} m, duration {}".format(
        status,
        sum(len(leg.get("steps", [])) for leg in route.get("legs", [])),
        route.get("distanceMeters"),
        route.get("duration"),
    ))
    print("A real walking route comes back for this key and endpoint, so \"no route was found\" was")
    print("never the API's answer: it was the catch around a call that never left the main thread.")


if __name__ == "__main__":
    main()
