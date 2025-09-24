#!/bin/bash
set -e

echo "=== Starting APK Build ==="

# Step 1: Set environment
export ANDROID_HOME="/home/runner/workspace/.android-sdk"
export PATH="$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/33.0.2:$PATH"

# Step 2: Clean and build
cd android-app
echo "🔄 Cleaning and building APK..."
./gradlew clean assembleDebug --no-daemon
cd ..

# Step 3: Generate debug keystore (if missing)
KEYSTORE="debug.keystore"
ALIAS="androiddebugkey"
if [ ! -f "android-app/$KEYSTORE" ]; then
  echo "🔑 Creating debug keystore..."
  keytool -genkey -v -keystore "android-app/$KEYSTORE" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US"
fi

# Step 4: Sign and align APK
UNSIGNED_APK="android-app/app/build/outputs/apk/debug/app-debug.apk"
SIGNED_APK="app-final-signed.apk"

echo "🔐 Signing APK..."
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore "android-app/$KEYSTORE" -storepass android -keypass android \
  "$UNSIGNED_APK" "$ALIAS"

echo "📐 Aligning APK..."
# Use zipalign from Android SDK if available
ZIPALIGN=$(find "$ANDROID_HOME" -name "zipalign" 2>/dev/null | head -1)
if [ -n "$ZIPALIGN" ]; then
    "$ZIPALIGN" -v -p 4 "$UNSIGNED_APK" "$SIGNED_APK"
else
    echo "⚠️  zipalign not found, copying APK without alignment"
    cp "$UNSIGNED_APK" "$SIGNED_APK"
fi

# Step 5: Move to web-accessible directory
echo "📁 Moving APK to web directory..."
mkdir -p static
mv "$SIGNED_APK" static/app-final-signed.apk

# Step 6: Verify and compute MD5
echo "=== Verification ==="
echo "✅ APK structure test:"
if command -v unzip >/dev/null 2>&1; then
    unzip -t static/app-final-signed.apk >/dev/null 2>&1 && echo "   APK structure: VALID" || echo "   APK structure: INVALID"
else
    echo "   APK structure: SKIPPED (unzip not available)"
fi

echo "✅ Signature verification:"
if command -v jarsigner >/dev/null 2>&1; then
    jarsigner -verify static/app-final-signed.apk >/dev/null 2>&1 && echo "   APK signature: VALID" || echo "   APK signature: INVALID"
else
    echo "   APK signature: SKIPPED (jarsigner not available)"
fi

MD5=$(md5sum static/app-final-signed.apk | cut -d' ' -f1)
SIZE=$(ls -lh static/app-final-signed.apk | awk '{print $5}')

echo "=== APK Ready ==="
echo "📱 File: static/app-final-signed.apk"
echo "📊 Size: $SIZE"
echo "🔐 MD5: $MD5"
echo ""
echo "🌐 Download URL:"
echo "   https://28c24505-1520-4ed1-b5bf-b43f4c5c2f1c-00-3ca6ln8qqlkg5.kirk.replit.dev/apk/download"
echo ""
echo "✅ APK build complete! Test the download to verify HTTP 200 (not 206)."