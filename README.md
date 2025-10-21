# The Observer Effect

**Auto-unlock and auto-open when movement is detected.**

Configures your Android device to automatically unlock and optionally open an application when it detects your presence using the camera and ambient light sensors. 

While initially designed for an Android-based home security system, it's also perfect for Home Assistant, Calendars, and Public Transit schedules. No more tapping the screen—just walk up and it's ready. This app is similar in concept to [Yakk](https://yakk.bkappz.com/), but I didn't know it existed when I started hacking on this. In comparison, TOE is open-source, with support for more sensors and different customization options. TOE does not record video or store data other than your configuration data. TOE does not require any network access.

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
- **App preload for instant startup** - Optional background preloading for near-instant app appearance
- Configurable notification sound on wake
- Start at boot for set-and-forget operation
- **Optimized for low-end devices** - Intelligent memory management and frame throttling
- Zero-animation instant transitions for snappy responsiveness
- Clean, simple UI with tablet layouts
- Full accessibility support with TalkBack

## Permissions

On first launch, the app will request the following permissions:

- **Camera** - For motion detection
- **Display over other apps** (SYSTEM_ALERT_WINDOW) - Required to reliably launch apps from background on Android 10+
- **Battery optimization exemption** - For reliable background operation

These permissions are essential for the app to function properly.

## Performance

The Observer Effect is optimized to run on a wide range of devices, from high-end tablets to budget Chinese tablets:

**Low-end device optimizations:**
- Camera frames throttled to ~5 FPS to reduce CPU usage by 80%
- Automatic memory management - pauses detection during low memory conditions
- Low resolution processing (320×240) for minimal resource usage
- Efficient pixel sampling (~1000 pixels per frame)

**High-end device features:**
- Optional app preload for instant appearance (100-300ms vs 2-5 seconds)
- Zero-animation transitions for snappy responsiveness
- 3-second cooldown between triggers to prevent spam

**Recommendations for cheap tablets (<2GB RAM):**
- Use the ambient light sensor instead of camera (near-zero CPU usage)
- Disable app preload to save memory
- Choose simple launch apps (launchers, calendars) over browsers

## Building

You are probably best off using Android Studio, but we have make targets too:

```bash
make              # Build debug APK → out/observer-effect-debug.apk
make release      # Build release APK → out/observer-effect-release.apk
make sideload     # Install via adb
```

**Requirements:** Android 8.0+, JDK 17+

## License

Apache 2.0
