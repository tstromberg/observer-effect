# Heisenberg's Lux

A minimalist Android app that wakes your device screen when motion or ambient light changes are detected.

## Name Origin

"Heisenberg's Lux" is a playful reference to Heisenberg's uncertainty principle (you can't observe without affecting the system) combined with "lux" (the SI unit for illuminance).

## Features

- **Motion Detection**: Uses device camera to detect motion
- **Light Detection**: Uses ambient light sensor to detect changes in lighting
- **Configurable Sensitivity**: Adjust detection thresholds from 0-100 (0 = disabled)
- **Foreground Service**: Runs continuously in the background
- **Minimal Dependencies**: Simple, elegant, and efficient

## Building

### Requirements

- JDK 17 or higher (works with Java 21)
- Android SDK
- Gradle 8.5+ (included via wrapper)

### Build Commands

```bash
make              # Build debug APK
make release      # Build release APK
make sideload     # Build and install via adb
make clean        # Clean build artifacts
```

Or use Gradle directly:
```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew installDebug
```

## Runtime Requirements

- Android 8.0 (API 26) or higher
- Camera permission (for motion detection)
- Device should be plugged in for continuous operation

## How It Works

1. **Motion Detection**: Compares consecutive camera frames to detect changes
2. **Light Detection**: Monitors ambient light sensor for significant changes
3. **Screen Wake**: Uses PowerManager.WakeLock to wake the screen momentarily
4. **Settings Persistence**: Stores sensitivity values in SharedPreferences

## Architecture

- **MainActivity**: Settings UI with traditional Android Views
- **DetectionService**: Foreground service coordinating detection
- **MotionDetector**: CameraX-based motion detection
- **LightDetector**: SensorManager-based light change detection

## License

Apache 2.0
