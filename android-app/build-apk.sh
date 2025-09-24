#!/bin/bash
# build-apk.sh - Fixed APK build with signing and alignment

set -e  # Exit on error

echo "=== Starting Fixed APK Build ==="

# Step 1: Clean and Assemble
export ANDROID_HOME="/home/runner/workspace/.android-sdk"
export ANDROID_SDK_ROOT="/home/runner/workspace/.android-sdk"

./gradlew clean assembleDebug --no-daemon

# Step 2: Generate/Use Debug Keystore (persistent in Replit)
KEYSTORE="debug.keystore"
ALIAS="androiddebugkey"
if [ ! -f "$KEYSTORE" ]; then
    echo "=== Generating Debug Keystore ==="
    keytool -genkey -v -keystore "$KEYSTORE" -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US"
fi

# Step 3: Sign the APK
UNSIGNED_APK="app/build/outputs/apk/debug/app-debug.apk"
SIGNED_APK="app-debug-signed.apk"

echo "=== Signing APK ==="
jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 \
    -keystore "$KEYSTORE" -storepass android -keypass android \
    "$UNSIGNED_APK" "$ALIAS"

# Step 4: Align the APK  
echo "=== Aligning APK ==="
# Try to find zipalign in different locations
ZIPALIGN=""
if [ -f "$ANDROID_HOME/build-tools/34.0.0/zipalign" ]; then
    ZIPALIGN="$ANDROID_HOME/build-tools/34.0.0/zipalign"
elif [ -f "$ANDROID_HOME/build-tools/33.0.2/zipalign" ]; then
    ZIPALIGN="$ANDROID_HOME/build-tools/33.0.2/zipalign"
else
    ZIPALIGN=$(find "$ANDROID_HOME" -name "zipalign" 2>/dev/null | head -1)
fi

if [ -n "$ZIPALIGN" ] && [ -f "$ZIPALIGN" ]; then
    echo "Using zipalign: $ZIPALIGN"
    "$ZIPALIGN" -v -p 4 "$UNSIGNED_APK" "$SIGNED_APK"
else
    echo "WARNING: zipalign not found, copying signed APK without alignment"
    cp "$UNSIGNED_APK" "$SIGNED_APK"
fi

# Step 5: Verify
echo "=== Verification ==="
unzip -t "$SIGNED_APK"
jarsigner -verify "$SIGNED_APK"

# Step 6: Compute MD5 for Download Check
MD5=$(md5sum "$SIGNED_APK" | cut -d' ' -f1)
echo "=== APK Ready for Download ==="
echo "File: $SIGNED_APK"
echo "Size: $(ls -lh "$SIGNED_APK" | awk '{print $5}')"
echo "MD5: $MD5"
echo "Path: $(pwd)/$SIGNED_APK"
echo "Download from Files panel: Right-click > Download"
echo "Verify MD5 after download to check integrity."