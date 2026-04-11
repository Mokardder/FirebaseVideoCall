# Firebase RTDB Signaling: Android (send-only UI) + Web (caller, no local video)

This starter project gives you a **Firebase Realtime Database signaling flow** for:

- **Web browser**: starts the call, sends no local camera video (`recvonly`), and receives Android media.
- **Android (Java)**: auto-accepts incoming offer, sends camera + mic, does not render remote video, and applies caller controls for camera/mic/torch.
- **TURN relay**: included in ICE server list (replace placeholders with your own TURN credentials).

> This is a pragmatic starter. You still need to plug in your Firebase config, TURN credentials, and Android WebRTC dependency versions that match your environment.

## 1) Call flow

1. Browser creates offer and writes it to `calls/{callId}/offer`.
2. Browser writes control state under `calls/{callId}/controls` (camera, micMuted, torchOn).
3. Android listens to the call path, applies controls, and auto-answers when an offer appears.
4. Android writes answer to `calls/{callId}/answer`.
5. Both sides exchange candidates under:
   - Browser candidates: `calls/{callId}/candidates/browser/{candidateId}`
   - Android candidates: `calls/{callId}/candidates/android/{candidateId}`

## 2) Firebase RTDB structure

```json
{
  "calls": {
    "demo-call-001": {
      "offer": { "type": "offer", "sdp": "..." },
      "answer": { "type": "answer", "sdp": "..." },
      "controls": { "camera": "front", "micMuted": false, "torchOn": false },
      "candidates": {
        "browser": {
          "-Nx1": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 }
        },
        "android": {
          "-Nx2": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 }
        }
      }
    }
  }
}
```

## 3) Firebase security rules (example)

For testing only (tighten before production):

```json
{
  "rules": {
    "calls": {
      ".read": true,
      ".write": true
    }
  }
}
```

This sample no longer uses Firebase Authentication. Use explicit path validation and rate limiting before production.

## 4) Web app

- Open `web/index.html` through a local static server.
- This web client is **vanilla JavaScript only**, and uses Firebase **CDN scripts** (no npm).
- Set your Firebase web config directly inside `web/app.js`.
- Choose camera (front/back) and click **Start Call**.
- While in call, use **Mute Mic** and **Torch On/Off** (these control Android sender state).

## 5) Android app

- Add your `google-services.json` and Firebase dependencies (`database`).
- Add camera/microphone permissions.
- Install app and grant permissions once.
- App listens to the same `callId` and auto-answers.
- Android has no local call controls; it follows control commands from the browser caller.

## 6) TURN relay

In both `web/app.js` and `MainActivity.java`, replace:

- `turn:a.relay.metered.ca:443?transport=tcp`
- `username: "83eebabf8b4cce9d5dbcb649"`
- `credential: "2D7JvfkOQtBdYW3R"`

Use your coturn or managed TURN provider credentials.

## 7) Notes

- Android intentionally does **not** attach remote video track to any renderer.
- Browser intentionally adds `recvonly` transceivers so it does not send camera.
- If ICE fails on mobile networks, verify TURN UDP/TCP/TLS reachability.
- Android now includes TURN UDP + TCP + TLS entries and will call `restartIce()` when ICE reaches FAILED.
- If Android crashes with `Default FirebaseApp is not initialized`, verify `google-services.json`, apply `com.google.gms.google-services`, and initialize Firebase before `FirebaseDatabase.getInstance(...)`.
