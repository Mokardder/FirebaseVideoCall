# Firebase RTDB Signaling: Android (send-only UI) + Web (caller, no local video)

This starter project gives you a **Firebase Realtime Database signaling flow** for:

- **Web browser**: starts the call, sends no local camera video (`recvonly`), and receives Android media.
- **Android (Java)**: auto-accepts incoming offer, sends camera + mic, does not render remote video.
- **TURN relay**: included in ICE server list (replace placeholders with your own TURN credentials).

> This is a pragmatic starter. You still need to plug in your Firebase config, TURN credentials, and Android WebRTC dependency versions that match your environment.

## 1) Call flow

1. Browser creates offer and writes it to `calls/{callId}/offer`.
2. Android listens to that call path and auto-answers when an offer appears.
3. Android writes answer to `calls/{callId}/answer`.
4. Both sides exchange candidates under:
   - Browser candidates: `calls/{callId}/candidates/browser/{candidateId}`
   - Android candidates: `calls/{callId}/candidates/android/{candidateId}`

## 2) Firebase RTDB structure

```json
{
  "calls": {
    "demo-call-001": {
      "offer": { "type": "offer", "sdp": "..." },
      "answer": { "type": "answer", "sdp": "..." },
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

## 4) Web app

- Open `web/index.html` through a local static server.
- This web client is **vanilla JavaScript only**, and uses Firebase **CDN scripts** (no npm).
- Set your Firebase web config directly inside `web/app.js`.
- Click **Start Call**.

## 5) Android app

- Add your `google-services.json` and Firebase dependencies.
- Add camera/microphone permissions.
- Install app and grant permissions once.
- App listens to the same `callId` and auto-answers.

## 6) TURN relay

In both `web/app.js` and `MainActivity.java`, replace:

- `turn:YOUR_TURN_HOST:3478?transport=udp`
- `username: "YOUR_TURN_USERNAME"`
- `credential: "YOUR_TURN_PASSWORD"`

Use your coturn or managed TURN provider credentials.

## 7) Notes

- Android intentionally does **not** attach remote video track to any renderer.
- Browser intentionally adds `recvonly` transceivers so it does not send camera.
- If ICE fails on mobile networks, verify TURN UDP/TCP/TLS reachability.
- If Android crashes with `Default FirebaseApp is not initialized`, verify `google-services.json`, apply `com.google.gms.google-services`, and initialize Firebase before `FirebaseDatabase.getInstance(...)`.
