#!/usr/bin/env bash
# R-BuildLinux: WSL / native Linux build script for Pulse Desktop (Kotlin/Compose).
# Produces a Debian package at build/compose/binaries/main/deb/Pulse-1.0.0.deb
#
# Usage (Ubuntu 22.04+ / WSL Ubuntu):
#   ./tools/build-deb.sh
#
# Output:
#   build/compose/binaries/main/deb/Pulse-1.0.0.deb
#
# Install on target:
#   sudo dpkg -i Pulse-1.0.0.deb
#   sudo apt-get install -f    # pulls runtime deps (freetype, fontconfig, libxcomposite, etc.)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "==> Host: $(. /etc/os-release 2>/dev/null && echo "${PRETTY_NAME:-$(uname -s)}")"
echo "==> JDK:  $(java -version 2>&1 | head -n 1)"

# 1. Install build deps if missing (skiko runtime + jpackage DebBundler).
if command -v apt-get >/dev/null 2>&1; then
  echo "==> Installing build dependencies (apt-get)…"
  sudo apt-get update
  sudo apt-get install -y \
    openjdk-17-jdk \
    libfreetype6 libfontconfig1 \
    fakeroot dpkg binutils
elif command -v dnf >/dev/null 2>&1; then
  echo "==> Installing build dependencies (dnf)…"
  sudo dnf install -y \
    java-17-openjdk-devel \
    freetype fontconfig fakeroot dpkg binutils
else
  echo "::error::Unsupported distro. Install JDK 17 + freetype + fontconfig + fakeroot + dpkg manually."
  exit 1
fi

# 2. Sanity check.
command -v java >/dev/null 2>&1 || { echo "::error::java not on PATH"; exit 1; }
command -v fakeroot >/dev/null 2>&1 || { echo "::error::fakeroot missing"; exit 1; }
command -v dpkg-deb >/dev/null 2>&1 || { echo "::error::dpkg-deb missing"; exit 1; }

# 3. Make gradlew executable (Windows checkouts strip the bit).
chmod +x ./gradlew

# 4. Build.
echo "==> ./gradlew packageDeb"
./gradlew packageDeb --no-daemon --stacktrace

# 5. Report.
DEB=$(find build/compose/binaries/main/deb -maxdepth 1 -name '*.deb' | head -n 1 || true)
if [ -z "$DEB" ]; then
  echo "::error::No .deb was produced. Check the Gradle output above."
  exit 1
fi

echo ""
echo "==================================================="
echo "  Built: $DEB"
echo "  Size:  $(du -h "$DEB" | cut -f1)"
echo "==================================================="
echo "Install: sudo dpkg -i $DEB && sudo apt-get install -f"
