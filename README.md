# Heisenberg's Lux

I wanted my wall-mounted home automation tablets to light up as I walked by. This app wakes the screen when it detects motion or light changes, and can optionally launch any app you want.

Works great for Home Assistant dashboards, Alarmo alarm panels, or any app you want to see when you approach a mounted tablet. No more tapping the screen to wake it.

The name is a nod to Heisenberg's uncertainty principle (you can't observe without affecting the system) combined with "lux" (the SI unit for illuminance).

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
