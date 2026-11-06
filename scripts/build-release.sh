#!/usr/bin/env bash
# Builds version $1 into build/release/ for semantic-release to attach to the GitHub Release:
#   volum-<version>.apk  signed with the release key (Obtainium, or install directly)
#   volum-<version>.ipa  unsigned; AltStore/SideStore sign it with the user's own Apple ID
# Refuses to run without the Android release key: an APK signed with a throwaway debug key couldn't
# update an installed copy. Needs macOS with Xcode and xcodegen for the iOS half.
set -euo pipefail

version="${1:?usage: build-release.sh <version>}"
if [[ -z "${VOLUM_KEYSTORE_PATH:-}" || ! -f "${VOLUM_KEYSTORE_PATH}" ]]; then
  echo "::error::Release key missing (VOLUM_KEYSTORE_PATH); not publishing unsigned builds." >&2
  exit 1
fi

# Both platforms read the version from here (Gradle for Android and the Kotlin framework, the Xcode
# pre-build script for the Info.plist). CI never commits this change.
sed -i.bak "s/^volum.versionName=.*/volum.versionName=$version/" gradle.properties && rm gradle.properties.bak

dest=build/release
rm -rf "$dest" && mkdir -p "$dest"

# Android
./gradlew :androidApp:assembleRelease --stacktrace
# Stable names, so Obtainium and similar can pick the right file with a pattern.
cp androidApp/build/outputs/apk/release/androidApp-release.apk "$dest/volum-$version.apk"

# iOS
(cd iosApp && xcodegen generate --quiet)
xcodebuild build \
  -project iosApp/iosApp.xcodeproj \
  -target iosApp \
  -configuration Release \
  -sdk iphoneos \
  SYMROOT="$PWD/build/ios" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  -quiet
payload="$(mktemp -d)/Payload"
mkdir -p "$payload"
cp -R build/ios/Release-iphoneos/Volum.app "$payload/"
(cd "$(dirname "$payload")" && zip -qry "$OLDPWD/$dest/volum-$version.ipa" Payload)

ls -l "$dest"
