# Heisenberg's Lux

A minimalist Android app that wakes your device screen when motion or ambient light changes are detected.

## Name Origin

"Heisenberg's Lux" is a playful reference to Heisenberg's uncertainty principle (you can't observe without affecting the system) combined with "lux" (the SI unit for illuminance).

## Features

- **Motion Detection**: Uses device camera to detect motion
- **Light Detection**: Uses ambient light sensor to detect changes in lighting
- **Configurable Sensitivity**: Adjust minimum activity thresholds from 0-100% (0 = disabled, 1% = most sensitive, 100% = least sensitive)
- **Foreground Service**: Runs continuously in the background
- **Minimal Dependencies**: Simple, elegant, and efficient

## Building

### Requirements

- JDK 17 or higher (works with Java 21)
- Android SDK
- Gradle 8.5+ (included via wrapper)

### Build Commands

```bash
make              # Build debug APK → out/heisenberg-debug.apk
make release      # Build release APK → out/heisenberg-release.apk (auto-signed)
make sideload     # Build and install debug APK via adb
make lint         # Run ktlint code style checks
make format       # Auto-format code with ktlint
make clean        # Clean build artifacts (including out/ directory)
```

All built APKs are automatically copied to the `out/` directory for easy access.

### Signing Configuration

Release builds use `~/android.jks` by default. Configure signing credentials via:

**Option 1: Environment variables** (recommended for CI/CD)
```bash
export ANDROID_KEYSTORE=/path/to/your/keystore.jks       # Optional, defaults to ~/android.jks
export ANDROID_KEYSTORE_PASSWORD=your_store_password
export ANDROID_KEY_ALIAS=your_key_alias                  # e.g., key0, androiddebugkey
export ANDROID_KEY_PASSWORD=your_key_password            # Optional, defaults to store password
```

**Option 2: gradle.properties** (for local development)
```properties
# ~/.gradle/gradle.properties or ./gradle.properties
android.keystorePassword=your_store_password
android.keyAlias=your_key_alias
android.keyPassword=your_key_password
```

If `~/android.jks` doesn't exist, a debug keystore will be auto-generated.

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
