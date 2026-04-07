# Doom Reforged Prototype (Android)

This repository now contains a **playable Android FPS prototype** inspired by classic corridor shooters.

## What is included

- Landscape-only mobile gameplay loop.
- On-screen joystick controls and a fire button.
- Ray-cast style walls with distance shading.
- Basic enemies, ammo, health, kill tracking.
- Recoil + muzzle flash animation on shooting.
- Story-mode style ending state with "TO BE CONTINUED...".
- GitHub Action that builds and uploads a debug APK artifact.

## Build locally

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Important note

This project intentionally avoids bundling copyrighted commercial DOOM WAD assets.
It uses original code and placeholder visuals so you can extend it with legally compatible assets.
