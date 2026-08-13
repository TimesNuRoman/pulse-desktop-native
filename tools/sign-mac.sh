#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Pulse — macOS code-signing + notarization helper.
#
# We sign the .dmg with codesign, then submit it to Apple's notary
# service via notarytool, then staple the ticket. Requires:
#   - Apple Developer ID Application certificate in Keychain
#   - App Store Connect API key (.p8) for notarytool
#
# Env vars:
#   PULSE_MAC_SIGN_IDENTITY  = "Developer ID Application: Your Name (TEAMID)"
#   PULSE_MAC_KEYCHAIN_PROFILE = notarytool keychain profile name
#   PULSE_MAC_BUNDLE_ID      = "com.pulseteam.desktop"
#
# Usage:
#   ./tools/sign-mac.sh /path/to/Pulse-1.0.0.dmg
#
# If env vars are not set, the script prints a "skipping" notice
# and exits 0 — local dev builds remain unsigned.
set -euo pipefail

DMG_PATH="${1:-build/compose/binaries/main-release/dmg/Pulse-1.0.0.dmg}"

if [[ ! -f "$DMG_PATH" ]]; then
    echo "sign-mac: $DMG_PATH not found. Run 'gradle packageReleaseDmg' first." >&2
    exit 1
fi

if [[ -z "${PULSE_MAC_SIGN_IDENTITY:-}" || -z "${PULSE_MAC_KEYCHAIN_PROFILE:-}" ]]; then
    echo "sign-mac: PULSE_MAC_SIGN_IDENTITY / PULSE_MAC_KEYCHAIN_PROFILE not set. Skipping (unsigned build)."
    exit 0
fi

echo "sign-mac: signing $DMG_PATH with '$PULSE_MAC_SIGN_IDENTITY'"
codesign --force --deep --options runtime --sign "$PULSE_MAC_SIGN_IDENTITY" "$DMG_PATH"
codesign --verify --strict --verbose=2 "$DMG_PATH"

echo "sign-mac: submitting to notary service"
xcrun notarytool submit "$DMG_PATH" \
    --keychain-profile "$PULSE_MAC_KEYCHAIN_PROFILE" \
    --wait

echo "sign-mac: stapling notarization ticket"
xcrun stapler staple "$DMG_PATH"
xcrun stapler validate "$DMG_PATH"
echo "sign-mac: done"
