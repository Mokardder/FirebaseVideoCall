# Android setup notes (Java)

## Dependencies (module `app/build.gradle`)

```gradle
dependencies {
    implementation platform('com.google.firebase:firebase-bom:34.0.0')
    implementation 'com.google.firebase:firebase-database'
    implementation 'com.google.firebase:firebase-auth'

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

## Firebase initialization crash fix

If you see `Default FirebaseApp is not initialized`, ensure all 3 are in place:

1. `google-services.json` exists in `android/app/`.
2. Google Services plugin is applied in app module (`id 'com.google.gms.google-services'`).
3. `FirebaseApp.initializeApp(context)` is called before `FirebaseDatabase.getInstance(...)` if auto-init is not happening in your build variant.


## Auth requirement

- Enable Anonymous auth provider in Firebase Console (Authentication -> Sign-in method).
- This sample signs in anonymously before reading/writing RTDB signaling paths.


## ICE failed troubleshooting

- Configure real TURN credentials (host/user/pass) in `MainActivity.java` and `web/app.js`.
- Ensure TURN supports UDP 3478, TCP 3478, and TLS 5349 from mobile networks.
- This sample now queues remote ICE candidates until remote SDP is set, then drains the queue.
- When ICE state becomes `FAILED`, Android triggers `peerConnection.restartIce()` automatically.


## TURN configured in sample

- Host: `a.relay.metered.ca`
- Username: `83eebabf8b4cce9d5dbcb649`
- Password: `2D7JvfkOQtBdYW3R`
- Includes `3478/udp`, `3478/tcp`, `443/tcp`, and `5349/tls` entries in code.
