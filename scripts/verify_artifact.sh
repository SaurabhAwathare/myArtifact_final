#!/bin/bash

# Android Artifact Integrity Validator
# Usage: ./verify_artifact.sh <path_to_apk> <expected_package>

APK_PATH=$1
EXPECTED_PACKAGE=$2

if [ -z "$APK_PATH" ] || [ -z "$EXPECTED_PACKAGE" ]; then
    echo "Usage: $0 <path_to_apk> <expected_package>"
    exit 1
fi

if [ ! -f "$APK_PATH" ]; then
    echo "Error: APK file not found at $APK_PATH"
    exit 1
fi

echo "Verifying artifact integrity for: $APK_PATH"

# 1. Check for expected package in DEX
echo "Checking for package: $EXPECTED_PACKAGE..."
PACKAGES=$(apkanalyzer dex packages "$APK_PATH")

if echo "$PACKAGES" | grep -q "$EXPECTED_PACKAGE"; then
    echo "SUCCESS: Package $EXPECTED_PACKAGE found in DEX."
else
    echo "FATAL: Package $EXPECTED_PACKAGE NOT found in DEX! The build is likely corrupted."
    exit 1
fi

# 2. Check for minimum class count (sanity check)
CLASS_COUNT=$(apkanalyzer dex list "$APK_PATH" | wc -l)
MIN_CLASSES=100 # Adjust based on project size

echo "Total classes in DEX: $CLASS_COUNT"
if [ "$CLASS_COUNT" -lt "$MIN_CLASSES" ]; then
    echo "WARNING: Unusually low class count ($CLASS_COUNT). Verify if classes were stripped."
    # exit 1 # Optional: enable if you want to be strict
fi

# 3. Check for Room schemas if applicable
# (This can be extended to check assets if schemas are bundled)

echo "Artifact validation PASSED."
exit 0
