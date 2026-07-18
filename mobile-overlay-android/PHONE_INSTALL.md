# Installing AntiJail on a physical phone (Realme, Samsung, etc.)

The app works on real phones — not only emulators. A debug APK built on your PC only installs on that PC via USB. For GitHub downloads you need a **signed release APK**.

## Before you install

1. **Landscape** — open the game in landscape; run Setup on the phone (coordinates from an emulator will not match).
2. **Download** the `AntiJail.apk` from the GitHub Release (not a debug build from another machine).

## Android settings (especially Realme / ColorOS)

1. **Install unknown apps** — allow your browser or Files app to install APKs.
2. **Overlay** — AntiJail landing page → Open Settings → allow display over other apps.
3. **Accessibility** — Settings → Accessibility → AntiJail → enable.
   - **Android 13+:** Apps → AntiJail → ⋮ menu → **Allow restricted settings**, then enable accessibility.
4. **Notifications** — allow when prompted (Android 13+). Needed so screen capture stays active.
5. **Screen capture** — tap Enable Screen Capture in AntiJail and approve the system prompt.
6. **Battery** — Settings → Battery → AntiJail → **Don't optimize** / allow autostart and background activity (wording varies by OEM).

## Setup on the phone

Complete all three setup steps (Points and Region, Zoom, Game Screen Area) **on the same device** you will play on.

For **Fish mode**, open Setup → Points and Region → enable **Fishing Detections** and position:

- **Fish stop** (STP) — your in-game stop button
- **Pirate HP** — small box over player + pirate HP bars

## Building a release APK (maintainer)

```powershell
cd mobile-overlay-android
copy keystore.properties.example keystore.properties
# Edit keystore.properties and create the keystore (see below)
.\gradlew assembleRelease
```

Create a keystore once:

```powershell
keytool -genkey -v -keystore antijail-release.keystore -alias antijail -keyalg RSA -keysize 2048 -validity 10000
```

Output: `app/build/outputs/apk/release/AntiJail.apk`

Upload that file to a GitHub Release. Do **not** commit `keystore.properties` or `.keystore` files.

## Troubleshooting

| Symptom | Fix |
|--------|-----|
| APK won't install | Use signed **release** APK; uninstall old debug build first if signature differs |
| Accessibility greyed out | Allow restricted settings (Android 13+) |
| Capture stops / macro dies | Grant notifications; disable battery optimization |
| Taps miss targets | Re-run Setup on this phone in landscape |
| App stops when switching apps | Expected — refocus game and press Play again |
