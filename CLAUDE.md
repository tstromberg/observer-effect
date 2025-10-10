# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Heisenberg's Lux** is a minimalist Android app (Kotlin, min SDK 26) that wakes the device screen when motion or ambient light changes are detected. The name is a playful reference to Heisenberg's uncertainty principle (you can't observe without affecting the system) combined with "lux" (the SI unit for illuminance). The app is designed as a canonical example of modern Android development with minimal dependencies and maximum simplicity.

**Core Philosophy**: Simplicity, robustness, minimal dependencies. Use traditional Android Views, not Jetpack Compose. This app should be elegant enough to serve as a reference implementation.

## Key Requirements

- **Foreground Service**: Runs invisibly in background with notification
- **Detection Methods**: Camera motion detection (CameraX) and ambient light sensor (SensorManager)
- **Screen Wake**: Use PowerManager.WakeLock with ACQUIRE_CAUSES_WAKEUP flag (acquire and immediately release)
- **Settings UI**: Simple activity with two sliders (0-100, 0=disabled) for motion and light sensitivity
- **Permissions**: Camera, wake lock, foreground service
- **No battery optimization concerns**: Devices will be plugged in

## Build Commands

```bash
make              # Build debug APK (./gradlew assembleDebug)
make release      # Build release APK (./gradlew assembleRelease)
make sideload     # Build and install via adb (./gradlew installDebug)
make clean        # Clean build artifacts (./gradlew clean)
```

The project can also be opened and built directly in Android Studio.

## Architecture

### Service Layer
- **DetectionService**: Foreground service that coordinates motion and light detection
- Runs continuously when enabled, shows persistent notification
- Manages lifecycle of camera and sensor detection

### Detection Components
- **MotionDetector**: Uses CameraX to compare consecutive frames, configurable sensitivity (0-100)
- **LightDetector**: Uses SensorManager TYPE_LIGHT sensor, detects changes above threshold (0-100)
- Both detectors callback to service when detection threshold exceeded

### Screen Wake Mechanism
- PowerManager.WakeLock with ACQUIRE_CAUSES_WAKEUP + SCREEN_BRIGHT_WAKE_LOCK flags
- Acquire lock momentarily, then immediately release
- Screen wakes to last shown screen (lock screen or home screen)
- Screen timeout follows system settings

### Settings Persistence
- SharedPreferences for storing sensitivity values
- Keys: "motion_sensitivity" and "light_sensitivity" (0-100 integers)
- Settings activity reads/writes directly to SharedPreferences

### UI Layer
- **MainActivity**: Simple settings screen with traditional Android Views
- Two SeekBars (0-100) for motion and light detection sensitivity
- Display sensor details if available (e.g., "Rear Camera 12MP"), otherwise "Not detected"
- No Material Design 3 dependency - use simple, clean Android UI components

## Dependencies (Minimal)

- **CameraX**: Only for camera access and frame analysis
- **AndroidX Core**: Only what's strictly necessary for compatibility
- Avoid: Jetpack Compose, Material Design 3, unnecessary architecture components
- Use standard Android SDK components wherever possible

## Code Style

- Follow Kotlin conventions and Android best practices
- Keep it simple: avoid over-engineering or unnecessary abstractions
- Inline small functions (< 7 lines) that are only called once
- Comprehensive structured logging for debugging
- Context passed from MainActivity, never embedded in structs
- Function names: never begin with "Get" (it's implied), "Set" is OK

## Testing

- Focus on core detection logic and service lifecycle
- Test frame comparison algorithms for motion detection
- Test light sensor threshold calculations
- Ensure wake lock acquire/release works correctly

## Security Considerations

- Camera permission only used for motion detection, never recording or transmission
- No network access required
- Minimal attack surface due to simplicity
- Foreground service prevents background execution restrictions
