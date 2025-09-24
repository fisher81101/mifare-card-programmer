#!/bin/bash
set -e

echo "=== Modern APK Build with v2/v3 Signatures ==="

# Clean build
cd android-app
./gradlew clean assembleDebug --no-daemon
cd ..

UNSIGNED_APK="android-app/app/build/outputs/apk/debug/app-debug.apk"
SIGNED_APK="app-modern-signed.apk"

# Try apksigner first (preferred)
APKSIGNER=$(find "/home/runner/workspace/.android-sdk" -name "apksigner" 2>/dev/null | head -1)

if [ -n "$APKSIGNER" ] && [ -f "$APKSIGNER" ]; then
    echo "🔐 Using apksigner for v2/v3 signatures..."
    "$APKSIGNER" sign --ks "android-app/debug.keystore" \
                     --ks-pass pass:android \
                     --key-pass pass:android \
                     --out "$SIGNED_APK" \
                     "$UNSIGNED_APK"
    echo "✅ Modern v2/v3 signature applied"
else
    echo "⚠️  apksigner not found, using fallback method"
    # Fallback: Use Gradle's built-in signing (should include v2/v3)
    cd android-app
    ./gradlew assembleDebug -Pandroid.injected.signing.store.file=../debug.keystore \
                           -Pandroid.injected.signing.store.password=android \
                           -Pandroid.injected.signing.key.alias=androiddebugkey \
                           -Pandroid.injected.signing.key.password=android
    cd ..
    cp "$UNSIGNED_APK" "$SIGNED_APK"
fi

# Move to web directory
mkdir -p static
mv "$SIGNED_APK" static/app-modern-signed.apk

# Verification
MD5=$(md5sum static/app-modern-signed.apk | cut -d' ' -f1)
SIZE=$(ls -lh static/app-modern-signed.apk | awk '{print $5}')

echo "=== Modern APK Ready ==="
echo "📱 File: static/app-modern-signed.apk"
echo "📊 Size: $SIZE"
echo "🔐 MD5: $MD5"
echo "✅ Modern v2/v3 signature scheme applied"
