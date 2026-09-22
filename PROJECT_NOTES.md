# Duel Dash 2P Arcade — project notes

Version: 0.2.0

Implemented:
- 7 game modes: FIGHT, RACE, TAP_DUEL, PONG, PENALTY, REACTION, TUG.
- Solo mode against built-in AI with no network connection.
- Multiplayer host/client over TCP Wi-Fi LAN or Bluetooth Classic RFCOMM.
- Host-authoritative simulation and 20 Hz state snapshots.
- 6 selectable fighter palettes.
- Procedural cartoon character rendering and simple run/punch animations.
- ToneGenerator sound effects and haptic feedback.
- Original colorful arcade menu.
- GitHub Actions APK build workflow with the corrected Android SDK setup.

Validation performed in the generation environment:
- All Java sources compile against API-shape Android stubs with javac 17+ syntax checking.
- Existing Wi-Fi/Bluetooth transport source kept intact.
- GitHub Actions workflow uses the same SDK setup that successfully produced the earlier project APK.

Recommended device validation after upload/build:
- Solo bot matches in all 7 modes.
- Two-phone Wi-Fi match and restart in all modes.
- Bluetooth pairing/permission behavior on Android 12+.
- Touch layout on small 16:9 and tall landscape screens.
