# The Observer Effect

**Auto-unlock and auto-open when movement is detected.**

Turn your wall-mounted tablet into a smart display that automatically wakes up and launches your dashboard when you walk by. Uses camera motion detection and ambient light sensors to detect your presence.

Perfect for Home Assistant dashboards, alarm panels, calendars, or any app you want instant access to. No more tapping the screen—just walk up and it's ready.

Similar concept to [Yakk](https://yakk.bkappz.com/), but open-source with more sensors and different customization options.

## Screenshots

**Tablet View**

<img src="media/tablet.png" height="480px" />

**Mobile View**

<img src="media/mobile.png" height="480px" />

## Features

- Camera motion detection with adjustable sensitivity
- Ambient light sensor for detecting lighting changes
- Auto-unlock screen and launch your dashboard app
- Start at boot for set-and-forget operation
- Material Design 3 with dark mode and tablet layouts
- Full accessibility support with TalkBack

## Setup Recommendations

**For best results, disable your device's lock screen security:**

Go to **Settings → Security → Screen Lock** and set it to **"None"** or **"Swipe"**.

**Why?** Android's security model prevents apps from bypassing PIN, pattern, or biometric authentication. If you have secure lock screen enabled, the app will wake the screen but you'll still need to authenticate manually. For wall-mounted tablets that don't leave your home, disabling the lock screen provides the smoothest experience.

If you need security, consider using Smart Lock (Settings → Security → Smart Lock) with trusted locations or devices instead.

## Building

```bash
make              # Build debug APK → out/heisenberg-debug.apk
make release      # Build release APK → out/heisenberg-release.apk
make sideload     # Install via adb
```

**Requirements:** Android 8.0+, JDK 17+

See [CLAUDE.md](CLAUDE.md) for detailed build instructions and architecture documentation.

## License

Apache 2.0
