# iTantra — Firebase + Emergency Location Build

This build is based on the working Firebase relay version and adds emergency GPS location sharing.

## Firebase setup

1. Open the project in Android Studio.
2. Put your real Firebase `google-services.json` in `app/`.
3. Sync Gradle.

## Emergency location flow

Emergency button -> Android Fused Location Provider -> latitude/longitude -> emergency Packet -> Firestore inbox -> receiver app -> coordinates displayed -> Open Emergency Location in Maps.

Location is only attached to emergency packets. Normal voice/text packets do not include location.

## Permissions

The app requests `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` at runtime when Emergency is pressed. The phone's Location service must be enabled.

## Important

The map button opens the device's installed map app using a `geo:` intent; no Google Maps SDK/API key is required.

Firestore rules must allow the sender to write to the recipient inbox and the recipient to read it. The existing hackathon rules can be used for testing, but open rules are not appropriate for production.
