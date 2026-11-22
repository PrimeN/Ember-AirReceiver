# Ember AirReceiver

![Ember AirReceiver — turn your TV into a wireless receiver](assets/ember-airreceiver-banner.svg)

Mirror your iPhone, iPad, or Mac to a Google TV or Fire TV on your local network. Ember supports H.264 screen mirroring, realtime AirPlay audio, and UPnP/DLNA playback. See the [privacy policy](PRIVACY.md) and [license](LICENSE).

## Install

Download **Ember-AirReceiver-debug** from a successful [Android receiver build](https://github.com/PrimeN/Ember-AirReceiver/actions/workflows/android.yml?query=branch%3Amain). Unzip it and install the APK for your device.

- **Google TV or Android TV:** `app-googletv-debug.apk` (Android 10+)
- **Fire TV:** `app-firetv-debug.apk` (Android 7.1+)

Open Ember on the TV and select the receiver name shown in the app from your sender’s Screen Mirroring menu. FairPlay Streaming video and buffered AirPlay 2 audio are currently unsupported.

## Build

Use JDK 17, Android SDK 35, NDK 28.2.13676358, and CMake 3.22.1.

```sh
./gradlew :test-runner:test :app:assembleGoogletvDebug :app:assembleFiretvDebug
```

The APKs are under `app/build/outputs/apk/`.

Google TV builds support Cast Connect when built with a registered Cast application ID. Supply it with the `AIRRECEIVER_CAST_APP_ID` environment variable or the `airreceiver.castAppId` Gradle property. The standard CI APKs omit this configuration. Cast Connect is unavailable on Fire TV.
