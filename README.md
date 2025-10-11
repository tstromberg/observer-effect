# The Observer Effect

**Auto-unlock and auto-open when movement is detected.**

Configures a wall-mounted tablet into a smart display that automatically wakes up and launches your favorite app when you walk by using camera motion detection and ambient light sensors for presence detection. Perfect for Home Assistant dashboards, alarm panels, calendars, tram schedules, or any app you want instant access to. No more tapping the screen—just walk up and it's ready.

This app is similar in concept to [Yakk](https://yakk.bkappz.com/), but I didn't know it existed at first. In comparison, this tool is open-source, with more sensors and different customization options. YMMV.

## Screenshots

**Tablet View (light mode)**

<img src="media/tablet.png" height="480px" />

**Mobile View (dark mode)**

<img src="media/mobile.png" height="480px" />

## Features

- Camera motion detection with adjustable sensitivity
- Ambient light sensor for detecting lighting changes
- Auto-unlock screen and launch your dashboard app
- Automatic keyguard dismissal (works with swipe-to-unlock and no security)
- Configurable notification sound on wake
- Start at boot for set-and-forget operation
- Clean, simple UI with tablet layouts
- Full accessibility support with TalkBack

## Permissions

On first launch, the app will request the following permissions:

- **Camera** - For motion detection
- **Display over other apps** (SYSTEM_ALERT_WINDOW) - Required to reliably launch apps from background on Android 10+
- **Battery optimization exemption** - For reliable background operation

These permissions are essential for the app to function properly.

## Building

You are probably best off using Android Studio, but we have make targets too:

```bash
make              # Build debug APK → out/heisenberg-debug.apk
make release      # Build release APK → out/heisenberg-release.apk
make sideload     # Install via adb
```

**Requirements:** Android 8.0+, JDK 17+

## License

Apache 2.0
