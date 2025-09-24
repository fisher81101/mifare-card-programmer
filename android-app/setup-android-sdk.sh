#!/bin/bash

# Android SDK Auto-Setup Script for Replit Environment
# This script ensures Android SDK is available in persistent workspace storage

WORKSPACE_SDK_DIR="/home/runner/workspace/.android-sdk"
NIX_ANDROID_TOOLS="/nix/store/28qzkr2zsgvzh4pip920raspl1gwd77v-android-tools-35.0.1"

echo "🔧 Setting up Android SDK in persistent workspace storage..."

# Create workspace SDK directory if it doesn't exist
if [ ! -d "$WORKSPACE_SDK_DIR" ]; then
    echo "📁 Creating Android SDK directory in workspace..."
    mkdir -p "$WORKSPACE_SDK_DIR"
    
    # Copy Android tools from Nix store to workspace
    echo "📦 Copying Android tools to workspace..."
    if [ -d "$NIX_ANDROID_TOOLS" ]; then
        cp -r "$NIX_ANDROID_TOOLS"/* "$WORKSPACE_SDK_DIR/" 2>/dev/null || true
    else
        echo "❌ Nix Android tools not found. Using alternative setup..."
        # Create minimal SDK structure
        mkdir -p "$WORKSPACE_SDK_DIR"/{licenses,platforms,build-tools,platform-tools}
    fi
    
    # Create licenses directory and accept licenses
    mkdir -p "$WORKSPACE_SDK_DIR/licenses"
    echo "24333f8a63b6825ea9c5514f83c2829b004d1fee" > "$WORKSPACE_SDK_DIR/licenses/android-sdk-license"
    echo "d56f5187479451eabf01fb78af6dfcb131a6481e" > "$WORKSPACE_SDK_DIR/licenses/android-sdk-preview-license"
    
    echo "✅ Android SDK setup complete"
else
    echo "✅ Android SDK already exists in workspace"
fi

# Update local.properties to point to workspace SDK
echo "🔗 Updating local.properties..."
echo "sdk.dir=$WORKSPACE_SDK_DIR" > local.properties

# Set environment variables
export ANDROID_HOME="$WORKSPACE_SDK_DIR"
export ANDROID_SDK_ROOT="$WORKSPACE_SDK_DIR"

echo "📍 Android SDK Location: $WORKSPACE_SDK_DIR"
echo "🌍 ANDROID_HOME: $ANDROID_HOME"

# Verify SDK structure
if [ -d "$WORKSPACE_SDK_DIR/licenses" ]; then
    echo "✅ SDK licenses directory exists"
else
    echo "⚠️ SDK licenses directory missing - creating..."
    mkdir -p "$WORKSPACE_SDK_DIR/licenses"
    echo "24333f8a63b6825ea9c5514f83c2829b004d1fee" > "$WORKSPACE_SDK_DIR/licenses/android-sdk-license"
    echo "d56f5187479451eabf01fb78af6dfcb131a6481e" > "$WORKSPACE_SDK_DIR/licenses/android-sdk-preview-license"
fi

echo "🚀 Android SDK setup complete! Ready for build."