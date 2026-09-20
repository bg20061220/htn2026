#!/usr/bin/env python3
"""
live_api_check.py — ONE live Groq chat-completion and ONE live ElevenLabs TTS call, each with the
exact request the app builds, so the model id, the voice id, the endpoint, the status codes and the
latency in the real world are recorded rather than assumed.

REPRODUCE (from the repo root):
    python review/harness/live_api_check.py > review/logs/groq-contract.log
    python review/harness/live_api_check.py --tts-only > review/logs/elevenlabs-contract.log

Budget: exactly one request per provider per run. Do not loop this.

Transcribed request shapes:
  GroqClient.kt:42       .callTimeout(4, TimeUnit.SECONDS)
  GroqClient.kt:47-77    getReply(): url https://api.groq.com/openai/v1/chat/completions, Bearer header
  GroqClient.kt:79-112   buildRequestBody(): model "openai/gpt-oss-20b"; messages =
                         [system SYSTEM_PROMPT, system "sensor_state: {...}", ...history, user]
                         with response_format {"type":"json_object"}
  GroqClient.kt:15-38    SYSTEM_PROMPT, verbatim
  GroqClient.kt:115-133  parseReply()
  ElevenLabsClient.kt:63-83  POST https://api.elevenlabs.io/v1/text-to-speech/<voiceId>/stream
                         ?output_format=mp3_44100_128, header xi-api-key,
                         body {"text": ..., "model_id": "eleven_turbo_v2_5"},
                         .callTimeout(15, TimeUnit.SECONDS)
  ElevenLabsClient.kt:24 voiceId = "EXAVITQu4vr4xnSDxMaL"  ("Sarah")
  ElevenLabsClient.kt:20-23  the code comment claiming Voice Library voices 402 on the free tier
"""

import json
import re
import ssl
import sys
import time
import urllib.error
import urllib.request

SYSTEM_PROMPT = """You are Goose, a calm, warm guide dog assistant helping a person get around safely.
Stay in character as a guide dog at all times \u2014 never mention being an AI, a model,
or software. Reference nearby hazards naturally when they are relevant (e.g. "there's
a curb coming up on your left") based on the sensor_state you are given, but keep it
conversational, not robotic.

sensor_state.hazardsKnown tells you whether the robot can actually see anything. When it is
false you have no hazard picture at all: never claim the way is clear, and if the person asks
what is around them, say plainly that you cannot see right now.

If the user asks you to go, walk, navigate, or take them somewhere, set command to
"navigate" and destination to exactly the place they named (e.g. "the library", "tim
hortons"), with no filler words added. Do this even if the place sounds far away or
unclear \u2014 let the app resolve and route it; you are not responsible for judging
distance or feasibility. Your "speech" for a navigate command will not be spoken
(the app replaces it with its own confirmation prompt), so it can be brief.

Respond with ONLY a JSON object, no other text, in exactly this shape:
{"speech": "<1-2 short sentences to speak aloud>", "command": "none|stop|go|forward|backward|turn_left|turn_right|navigate", "destination": "<only present if command is navigate>"}

Use "go" to start following a route that has already been planned, and "forward" when the user
wants the robot to move straight ahead right now (e.g. "go forward", "walk on", "keep going straight").
"forward" drives the robot without a destination, so use it for any request to move ahead that does
not name a place.
Use "backward" for requests to move backward or reverse. Treat "continue" as "go".
"""

# What the app sends now that the depth scene is wired in (MainActivity.kt: `sensorProvider =
# { sensorSnapshot }` -> OcrScreen.kt `onSceneSnapshot` -> SceneSnapshot.kt's metres-to-millimetres
# conversion): a real hazard, not the old constant. 1250 mm straight ahead, something on the left.
SENSOR_STATE = {
    "frontDistanceMm": 1250,
    "obstacleLeft": True,
    "obstacleRight": False,
    "dropoffDetected": False,
    "isMoving": True,
    "hazardsKnown": True,
}

GROQ_MODEL = "openai/gpt-oss-20b"            # GroqClient.kt:108
GROQ_TIMEOUT_S = 4                            # GroqClient.kt:44
ELEVEN_VOICE_ID = "EXAVITQu4vr4xnSDxMaL"      # ElevenLabsClient.kt:24
ELEVEN_TIMEOUT_S = 15                         # ElevenLabsClient.kt:27


def redact(key):
    return key[:6] + "..." if key and len(key) > 6 else "(absent)"


def load_keys():
    text = open("local.properties", encoding="utf-8", errors="replace").read()
    out = {}
    for name in ("GROQ_API_KEY", "ELEVENLABS_API_KEY", "MAPS_API_KEY"):
        m = re.search(name + r"\s*=\s*(\S+)", text)
        out[name] = m.group(1) if m else None
    return out


# urllib's default User-Agent is rejected by Groq's edge (Cloudflare error 1010). The app never sends
# it: OkHttp sends its own. This mirrors what OkHttpClient sends so the one call under test is the
# one the app would really make. Recorded here so nobody has to rediscover error 1010.
UA = "okhttp/5.5.0"


def post(url, headers, payload, timeout):
    data = json.dumps(payload).encode()
    headers = dict(headers)
    headers.setdefault("User-Agent", UA)
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    ctx = ssl.create_default_context()
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
            body = r.read()
            # header names are matched case-insensitively by HTTP; lower-case them so
            # headers.get("content-type") cannot silently return None.
            return r.status, {k.lower(): v for k, v in r.headers.items()}, body, time.monotonic() - started
    except urllib.error.HTTPError as e:
        body = e.read()
        return e.code, {k.lower(): v for k, v in e.headers.items()}, body, time.monotonic() - started
    except Exception as e:
        return None, {}, repr(e).encode(), time.monotonic() - started


def groq_check(key):
    print("=" * 100)
    print("GROQ CONTRACT CHECK — one chat/completions call, exactly the body GroqClient builds")
    print("=" * 100)
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "system", "content": "sensor_state: " + json.dumps(SENSOR_STATE, separators=(",", ":"))},
        {"role": "user", "content": "goose, what is around me right now"},
    ]
    payload = {
        "model": GROQ_MODEL,
        "messages": messages,
        "response_format": {"type": "json_object"},
    }
    print("endpoint        : https://api.groq.com/openai/v1/chat/completions")
    print("model           : {}".format(GROQ_MODEL))
    print("api key         : {} (GROQ_API_KEY)".format(redact(key)))
    print("client timeout  : GroqClient.kt:42 .callTimeout({} s)".format(GROQ_TIMEOUT_S))
    print("request bytes   : {}".format(len(json.dumps(payload).encode())))
    print("response_format : json_object")
    print("sensor_state    : {}  (a live hazard, from SceneSnapshot.kt)".format(
        json.dumps(SENSOR_STATE, separators=(",", ":"))))
    print("user turn       : 'goose, what is around me right now'  (the persona's hazard-awareness path)")
    print("")
    status, headers, body, elapsed = post(
        "https://api.groq.com/openai/v1/chat/completions",
        {"Authorization": "Bearer {}".format(key), "Content-Type": "application/json"},
        payload, GROQ_TIMEOUT_S + 6)
    print("HTTP status     : {}".format(status))
    print("content-type    : {}".format(headers.get("content-type")))
    print("elapsed         : {:.3f} s".format(elapsed))
    if status != 200:
        print("body (first 600 chars): {}".format(body.decode("utf-8", "replace")[:600]))
        print("")
        print("VERDICT: the configured model id did NOT serve this request -> GroqClient.getReply would")
        print("         return its fallback ConverseResponse and the app would say 'Sorry, I didn't catch that.'")
        if status in (400, 404):
            print("         (a 400/404 naming the model means the id is stale — GroqClient.kt:104-106 says")
            print("          to re-check /v1/models if that happens)")
        return
    doc = json.loads(body)
    content = doc["choices"][0]["message"]["content"]
    print("usage           : {}".format(doc.get("usage")))
    print("raw content     : {}".format(content))
    try:
        parsed = json.loads(content)
        print("parses as JSON  : yes -> command={!r} destination={!r}".format(
            parsed.get("command"), parsed.get("destination")))
    except Exception as e:
        print("parses as JSON  : NO ({} -> parseReply takes the JSONObject catch and returns the fallback)".format(e))
    print("")
    print("LATENCY VERDICT")
    print("  observed {:.3f} s for one turn, timeout {:.0f} s, plan.md:11.5 item 3 target: under 2 s for".format(
        elapsed, GROQ_TIMEOUT_S))
    print("  'speak -> transcript -> Groq reply -> ElevenLabs speaks it'. The LLM call alone is {:.3f} s.".format(elapsed))
    print("  The TTS call and the buffer-then-play design (ElevenLabsClient.kt:39-50, 78-80) come on top.")


def eleven_check(key):
    print("=" * 100)
    print("ELEVENLABS CONTRACT CHECK — one TTS call, exactly the request ElevenLabsClient builds")
    print("=" * 100)
    text = "Okay, heading to the library. That's about 400 m, roughly 5 min on foot."
    url = ("https://api.elevenlabs.io/v1/text-to-speech/{}/stream"
           "?output_format=mp3_44100_128".format(ELEVEN_VOICE_ID))
    payload = {"text": text, "model_id": "eleven_turbo_v2_5"}
    print("endpoint        : POST {}".format(url))
    print("voiceId         : {}  (ElevenLabsClient.kt:24, \"Sarah\")".format(ELEVEN_VOICE_ID))
    print("model_id        : eleven_turbo_v2_5")
    print("api key         : {} (ELEVENLABS_API_KEY)".format(redact(key)))
    print("client timeout  : ElevenLabsClient.kt:27 .callTimeout({} s)".format(ELEVEN_TIMEOUT_S))
    print("text            : {!r} ({} chars, the route-confirmation sentence)".format(text, len(text)))
    print("")
    status, headers, body, elapsed = post(
        url, {"xi-api-key": key, "Content-Type": "application/json"},
        payload, ELEVEN_TIMEOUT_S + 6)
    print("HTTP status     : {}".format(status))
    print("content-type    : {}".format(headers.get("content-type")))
    print("all headers     : {}".format({k: v for k, v in sorted(headers.items())}))
    print("bytes received  : {}".format(len(body)))
    if body[:2] == b"\xff\xf3" or body[:2] == b"\xff\xfb" or body[:3] == b"ID3":
        open("review/logs/elevenlabs-sample.mp3", "wb").write(body)
        print("first bytes     : {!r} (MPEG audio frame sync / ID3 — written to review/logs/elevenlabs-sample.mp3)".format(body[:4]))
    else:
        print("first bytes     : {!r} (NOT an MPEG frame header)".format(body[:8]))
    print("elapsed         : {:.3f} s".format(elapsed))
    if status != 200:
        print("body (first 600 chars): {}".format(body.decode("utf-8", "replace")[:600]))
    print("")
    if status == 200:
        print("VERDICT: the configured voiceId serves audio on this account; the free-tier 402 that")
        print("         ElevenLabsClient.kt:20-23 works around did not apply here.")
    elif status == 402:
        print("VERDICT: 402 payment_required — the workaround is still needed / the plan changed.")
    else:
        print("VERDICT: unexpected status; ElevenLabsClient.streamSpeech throws IOException on !isSuccessful")
        print("         (ElevenLabsClient.kt:77) and speak()'s catch calls onDone() immediately")
        print("         (ElevenLabsClient.kt:57-59), so the app goes silent but its state machine does not stick.")
    print("")
    print("NOTE: streamSpeech downloads the whole MP3 before MediaPlayer starts (ElevenLabsClient.kt:39-50, 78-80),")
    print("      so the download time above is added to every spoken reply before the first phoneme.")


def main():
    keys = load_keys()
    if "--tts-only" in sys.argv:
        eleven_check(keys["ELEVENLABS_API_KEY"])
        return
    groq_check(keys["GROQ_API_KEY"])


if __name__ == "__main__":
    main()
