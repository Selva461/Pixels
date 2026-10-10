#!/bin/sh
# Compiles the whole app (main, unit tests, instrumented tests) and runs the app unit tests,
# without the Android SDK or Google Maven. Prints compiler diagnostics for app files only.
set -u
cd "$(dirname "$0")" && mkdir -p build
../../gradlew compileKotlin compileAndroidTestKotlin test --console=plain > build/typecheck.log 2>&1 || status=$?
grep -E "^(e|w): " build/typecheck.log | grep -v "/offline-typecheck/" || true
grep -E "FAILURE|What went wrong" -A4 build/typecheck.log | head -12
exit "${status:-0}"
