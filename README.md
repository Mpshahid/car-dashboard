# Car Dashboard

An Android dashboard for your car's OBD-II port, written in Kotlin with Jetpack Compose. It talks to a cheap ELM327
Bluetooth adapter. It's tuned for a 2012 Vauxhall Meriva B (1.4 Turbo petrol, five-speed manual), but most of it works
with any car that speaks OBD-II.

## What it does

- **Drive**: speed, RPM, the gear you're in (worked out from speed and RPM) and every live reading, grouped by system.
- **Dashcam**: records the road, and the cabin too on phones that can run both cameras at once, with the time, speed and
  gear drawn onto the video.
- **Status**: car health checks, fault codes (read and clear), and fuel economy with an estimated range.
- **Sensors**: 47 standard OBD-II readings with trend charts and normal ranges, plus an optional AI check-up.
- **Trips**: distance, time, average and top speed, fuel used and economy for the current trip.
- **Voice assistant**: say "Dash" for hands-free commands. The wake word runs offline with Vosk, an optional download.
  Anything else goes to an AI service you choose: Claude, Gemini, OpenRouter or any OpenAI-compatible service.
- **Ride recording and replay**: every drive is logged once a second on the phone. The phone also serves a web page to
  your other devices that replays rides on a map, with charts and a 3D model of the car.
- **Navigation**: opens Google Maps beside the dashboard. You can save Home and Work.
- **Demo mode**: a simulated drive around central London when no adapter is connected.

## Build

You need Android Studio, or JDK 17 with the Android SDK (API 35). Then run:

```
./gradlew assembleDebug          # macOS / Linux
gradlew.bat assembleDebug        # Windows
```

The APK is written to `app/build/outputs/apk/debug/`. To run the unit tests: `./gradlew testDebugUnitTest`.

## Google Maps API key (optional, your own)

**This repository contains no API key.** The ride replay page needs your own key for Google Maps, satellite photos and
the photorealistic 3D view. Without one, it shows the free OpenStreetMap map.

1. In the [Google Cloud console](https://console.cloud.google.com/google/maps-apis/), create an API key and enable the
   **Maps JavaScript API** for it. Restricting the key to that API and setting a budget alert is a good idea.
2. Give the key to the app in one of two ways:
   - **In the app:** go to **Setup ▸ Google Maps key**, paste the key and tap **Save**. The key is stored encrypted on
     the phone.
   - **At build time (debug builds only):** add `MAPS_API_KEY=your-key` to `local.properties` in the project root. That
     file is git-ignored, so the key is never committed. Release builds ignore it, so an APK you share doesn't contain
     your key.

If both are set, the key saved in Setup is used.

## AI keys

The voice assistant and AI check-up use your own key for the AI service you pick. You enter it in
**Setup ▸ Voice assistant**. The key is stored encrypted on the phone and sent only to that service.

## Privacy

- Drive logs, including GPS, stay in the app's private storage on the phone. You choose how many days to keep.
- The replay page is served only on your own network, or over Tailscale. The link carries an access code, and Setup
  can make a new one.
- No keys are in the source code.

## Project layout

| Path | What's there |
| --- | --- |
| `app/src/main/java/com/cardash/dashboard/obd/` | ELM327 connection, PIDs and polling |
| `app/src/main/java/com/cardash/dashboard/domain/` | Gear estimate and unit conversions |
| `app/src/main/java/com/cardash/dashboard/data/` | Ride log, settings, key storage and the drive service |
| `app/src/main/java/com/cardash/dashboard/ui/` | The Compose screens |
| `app/src/main/java/com/cardash/dashboard/web/` | The phone's web server for the replay page |
| `app/src/main/java/com/cardash/dashboard/{ai,dashcam,insights,music,nav}/` | Voice assistant, dashcam, health checks, music, navigation |
| `app/src/main/assets/web/index.html` | The ride replay page |
| `tools/blender/` | Scripts that built the 3D car model (`meriva.glb`) |

The demo route follows OpenStreetMap roads (© OpenStreetMap contributors, ODbL).
