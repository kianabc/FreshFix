# FreshFix

An Android camera that matches **every photo to a live GPS fix taken at the moment of the shutter**, then stamps the time, street address and coordinates onto the photo.

## Why

The stock Android camera geotags photos with the OS's *cached* location. Walk down a street photographing parking signs and every photo lands on the same two or three points. That's useless as proof of where a photo was taken.

FreshFix never uses a "last known" location:

- While the camera is open it streams raw GNSS fixes from the GPS chip once per second (`LocationManager.GPS_PROVIDER`, no Wi-Fi or cell-tower guesses, no Google Play Services).
- When you press the shutter it records the exact time, then uses the fix **closest in time to that moment**. If the next fix is due, it waits for it (up to 4 s).
- The status bar shows fix accuracy, fix age and satellites in use: green for a good fix, amber for a weak one, red when there's no fix or it's lost.

## What each photo gets

**Burned into the image** (bottom band, follows device rotation):

```
2026-10-04 14:32:07 MDT
123 Main St, Salt Lake City, UT 84101, USA
40.760779, -111.891047  ±4 m
```

**EXIF:** GPS lat/lon/altitude/speed, GPS timestamp, horizontal accuracy (`GPSHPositioningError`), capture time with timezone offset, and the address as `ImageDescription`.

**Log:** a row in `freshfix_log.csv`. Tap **Export log** to share it.

| column | meaning |
|---|---|
| `photo` | file name in `Pictures/FreshFix` |
| `taken_at` | shutter time, ISO 8601 with offset |
| `latitude`, `longitude` | from the matched fix |
| `accuracy_m` | horizontal accuracy reported by GNSS |
| `fix_offset_ms` | fix time minus shutter time (negative = fix just before the shot) |
| `satellites_used` | satellites in the fix |
| `speed_mps`, `bearing_deg`, `altitude_m` | when available |
| `mock` | `true` if a mock-location app supplied the fix |
| `status` | `OK` (±15 m and within 2 s), `WEAK`, or `NO_FIX` |
| `address` | reverse-geocoded street address |

The CSV imports directly into Google My Maps (pick `latitude`/`longitude` as the position columns), Google Earth, QGIS or a spreadsheet.

## Notes

- The address comes from the phone's built-in geocoder and needs a data connection. Offline, the photo is stamped "Address unavailable" and the coordinates are still exact.
- GPS needs open sky. Wait for the bar to turn green after opening the app; the first fix can take 10–30 s.
- Photos are saved with the stamp only; no unstamped copy is kept.

## Build

Requires JDK 17 and the Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Android 10 (API 29) or newer.
