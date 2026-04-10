# Android setup notes (Java)

## Dependencies (module `app/build.gradle`)

```gradle
dependencies {
    implementation platform('com.google.firebase:firebase-bom:34.0.0')
    implementation 'com.google.firebase:firebase-database'

    // WebRTC (example from maven central mirror builds)
    implementation 'io.github.webrtc-sdk:android:125.6422.02'
}
```

## Project-level plugins

```gradle
plugins {
    id 'com.android.application'
    id 'com.google.gms.google-services'
}
```

## Required files

- Place `google-services.json` in `android/app/`.
- Set the same RTDB instance used by your web app.

## Behavior implemented

- App waits for `calls/demo-call-001/offer`.
- It auto-creates answer.
- It sends local camera + mic.
- It ignores remote video rendering by design.
