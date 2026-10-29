#!/usr/bin/env sh
set -eu
if ! command -v gradle >/dev/null 2>&1; then
    echo 'Gradle 8.8+ is required, or build using the included GitHub Actions workflow.' >&2
    exit 1
fi
gradle clean build
echo 'Plugin JAR: build/libs/Platt-0.1.0-alpha.jar'
