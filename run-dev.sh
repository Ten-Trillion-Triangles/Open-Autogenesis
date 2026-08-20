#!/bin/bash

# Set Chrome binary for testing
export CHROME_BIN=/snap/bin/chromium

# Run the KVision development server
echo "Starting KVision development server..."
./gradlew :kvisionApp:jsBrowserDevelopmentRun