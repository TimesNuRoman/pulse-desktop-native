# Code signing — Pulse Desktop

Pulse is currently distributed **unsigned**. The v0.7.0-rc installers
work but trigger a Windows SmartScreen "Unknown publisher" warning on
first launch, and macOS Gatekeeper blocks the .dmg unless users
right-click → Open. Both go away with proper code signing.

## Why this is post-build (not Gradle-native)

The Compose Multiplatform 1.7.0 plugin's `nativeDistributions` DSL
does not expose jpackage's `--win-sign` / `--mac-sign` flags. We'd
have to either fork the plugin or call jpackage ourselves (which
defeats the convenience of `packageReleaseExe`). The pragmatic
choice for v0.7.0-rc is:

1. `gradle packageReleaseExe` — produces the unsigned .exe / .msi
2. `tools/sign-windows.ps1` — runs `signtool.exe sign /fd SHA256 ...`
3. `tools/sign-mac.sh` — runs `codesign`, `notarytool`, `stapler`

When the cert is installed and the env vars are set, both scripts
just work. Without env vars, they print "skipping" and exit 0 — local
dev builds stay unsigned.

## Windows — Authenticode

### What to buy

A standard **Authenticode Code Signing** certificate from any of:

| CA | Price (2026) | Notes |
|---|---|---|
| Sectigo (formerly Comodo) | $70/yr | Cheap, OV-level |
| SSL.com | $75/yr | Same as Sectigo |
| DigiCert | $500/yr | Premium, faster SmartScreen reputation |
| Certum | $30/yr | Open-source friendly (free for OSS!) |

For a brand-new signing key, Microsoft SmartScreen shows the warning
until ~500 installs accrue reputation. DigiCert's higher price buys
you a faster trust ramp.

### Where to get it free for OSS

**Certum** offers a free 1-year code-signing cert for open-source
projects. Apply at <https://shop.certum.eu/open-source-code-signing-certificate.html>
with a link to your repo + a one-paragraph justification. Approval
takes 3-5 days.

### Setup (one-time)

1. Receive the .pfx via email or your CA's portal
2. Store it OUTSIDE the repo, e.g. `C:\keys\pulse-2026.pfx`
3. Add the password to your password manager — **never** to git
4. Local PowerShell:
   ```powershell
   $env:PULSE_SIGN_PFX = 'C:\keys\pulse-2026.pfx'
   $env:PULSE_SIGN_PWD = '...redacted...'
   $env:PULSE_SIGN_TSA = 'http://timestamp.digicert.com'
   ```
5. For CI, add the .pfx as a GitHub Actions secret (base64-encoded)
   and the password as another secret. The CI workflow in
   `.github/workflows/build-windows.yml` already has a `signing`
   job that reads these secrets.

### Sign the build

```powershell
.\gradlew.bat packageReleaseExe packageReleaseMsi
.\tools\sign-windows.ps1
```

Output: signed `Pulse-1.0.0.exe` + `Pulse-1.0.0.msi` in
`build\compose\binaries\main-release\`.

### Verify

```powershell
signtool verify /pa build\compose\binaries\main-release\exe\Pulse-1.0.0.exe
Get-AuthenticodeSignature build\compose\binaries\main-release\exe\Pulse-1.0.0.exe
```

The `Status` should be `Valid`, `SignerCertificate.Subject` should
match your CA's organization name, and `TimeStamperCertificate` should
list DigiCert's TSA.

## macOS — Developer ID + Notarization

### What to buy

Enroll in the **Apple Developer Program** ($99/yr). This gives you
a "Developer ID Application" certificate in your Keychain.

### Setup (one-time)

1. On a Mac with Xcode installed, generate a Developer ID cert:
   <https://developer.apple.com/account/resources/certificates/add>
2. Create an App Store Connect API key (.p8 file) for `notarytool`
3. Store the .p8 in your Keychain:
   ```bash
   xcrun notarytool store-credentials pulse-notary \
       --apple-id you@apple.com --team-id TEAMID \
       --key /path/to/AuthKey_KEYID.p8 --key-id KEYID
   ```
4. Local shell:
   ```bash
   export PULSE_MAC_SIGN_IDENTITY='Developer ID Application: Your Name (TEAMID)'
   export PULSE_MAC_KEYCHAIN_PROFILE='pulse-notary'
   ```

### Sign the build

```bash
./gradlew packageReleaseDmg
./tools/sign-mac.sh
```

Output: signed + notarized `Pulse-1.0.0.dmg`.

## Cost summary

| Item | Cost | Renewal | When |
|---|---|---|---|
| Windows code signing | $30-$500/yr | annual | Before first public .exe release |
| Apple Developer Program | $99/yr | annual | Before first public .dmg release |
| Certum OSS code signing | $0 | annual | If you can get approved |
| Cloudflare R2 (for downloads) | ~$0.15/GB/mo | monthly | Already in place |

For a v0.7.0-rc MVP, the cheapest path is Certum (free) + Apple
Developer Program ($99). Total year-1: $99. Year-2+: $30-$600/yr
depending on whether you keep Certum or upgrade to DigiCert.
