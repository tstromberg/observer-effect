.PHONY: all release sideload clean rebuild-icons

# Default target: build debug APK
all:
	./gradlew assembleDebug

# Build release APK
release:
	./gradlew assembleRelease

# Build and install debug APK via adb
sideload:
	./gradlew installDebug

# Clean build artifacts
clean:
	./gradlew clean

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
