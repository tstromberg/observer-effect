.PHONY: all release sideload clean lint format rebuild-icons

# Default target: build debug APK
all:
	./gradlew assembleDebug
	@mkdir -p out
	@cp app/build/outputs/apk/debug/app-debug.apk out/heisenberg-debug.apk
	@echo "\nDebug APK built and copied to out/:"
	@ls -lh out/heisenberg-debug.apk

# Build release APK (signs with ~/android.jks or $ANDROID_KEYSTORE if present)
release:
	@KEYSTORE_PATH=$${ANDROID_KEYSTORE:-$$HOME/android.jks}; \
	if [ ! -f "$$KEYSTORE_PATH" ]; then \
		echo "Warning: Keystore not found at $$KEYSTORE_PATH"; \
		echo "Creating default debug keystore..."; \
		keytool -genkeypair -v \
			-keystore "$$HOME/android.jks" \
			-alias key0 \
			-keyalg RSA \
			-keysize 2048 \
			-validity 10000 \
			-storepass android \
			-keypass android \
			-dname "CN=Android Debug, OU=Android, O=Android, L=Unknown, ST=Unknown, C=US"; \
		echo "Created debug keystore at $$HOME/android.jks with alias 'key0'"; \
	fi
	./gradlew assembleRelease
	@mkdir -p out
	@if [ -f app/build/outputs/apk/release/app-release.apk ]; then \
		cp app/build/outputs/apk/release/app-release.apk out/heisenberg-release.apk; \
		echo "\nSigned release APK built and copied to out/:"; \
	else \
		cp app/build/outputs/apk/release/app-release-unsigned.apk out/heisenberg-release-unsigned.apk; \
		echo "\nUnsigned release APK built and copied to out/:"; \
	fi
	@ls -lh out/heisenberg-release*.apk

# Build and install debug APK via adb
sideload:
	./gradlew installDebug

# Clean build artifacts
clean:
	./gradlew clean
	@rm -rf out

# Run ktlint checks
lint:
	./gradlew ktlintCheck

# Auto-format code with ktlint
format:
	./gradlew ktlintFormat

# Generate launcher icons from source PNG (expects icon.png in project root)
rebuild-icons:
	@if [ ! -f icon.png ]; then \
		echo "Error: icon.png not found in project root"; \
		exit 1; \
	fi
	@echo "Generating launcher icons from icon.png..."
	@convert icon.png -resize 48x48 app/src/main/res/mipmap-mdpi/ic_launcher.png
	@convert icon.png -resize 72x72 app/src/main/res/mipmap-hdpi/ic_launcher.png
	@convert icon.png -resize 96x96 app/src/main/res/mipmap-xhdpi/ic_launcher.png
	@convert icon.png -resize 144x144 app/src/main/res/mipmap-xxhdpi/ic_launcher.png
	@convert icon.png -resize 192x192 app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
	@echo "Done! Generated icons in all density folders."
