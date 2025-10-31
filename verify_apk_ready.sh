#!/bin/bash
APK="app-release-signed.apk"

if [ ! -f "$APK" ]; then
  echo "❌ APK not found: $APK"
  exit 1
fi

echo "🔍 Checking signature..."
~/android-sdk/build-tools/34.0.0/apksigner verify --verbose "$APK"

echo -e "\n🔍 Checking SDK info..."
~/android-sdk/build-tools/34.0.0/aapt dump badging "$APK" | grep -E "sdkVersion|targetSdkVersion"

echo -e "\n🔍 Checking supported architectures..."
unzip -l "$APK" | grep "lib/" | awk '{print $4}' | sort -u || echo "⚠️ No native libs found"

echo -e "\n🔍 Checking permissions..."
~/android-sdk/build-tools/34.0.0/aapt dump badging "$APK" | grep uses-permission || echo "⚠️ No permissions listed"

echo -e "\n✅ APK health check complete!"
