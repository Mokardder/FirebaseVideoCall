(() => {
  const firebaseConfig = {
    apiKey: "AIzaSyBpO0ReUf7r6QrjX1kMG7Yv0chl-nVuRok",
    authDomain: "screenshareserver-c7ec1.firebaseapp.com",
    databaseURL: "https://screenshareserver-c7ec1-default-rtdb.firebaseio.com",
    projectId: "screenshareserver-c7ec1",
    storageBucket: "screenshareserver-c7ec1.firebasestorage.app",
    messagingSenderId: "779415078321",
    appId: "1:779415078321:web:f07be1fb25e0eea9b32990",
  };

  if (!firebase.apps.length) {
    firebase.initializeApp(firebaseConfig);
  }
  const db = firebase.database();

  const remoteVideo = document.getElementById("remoteVideo");
  const callIdInput = document.getElementById("callId");
  const cameraSelect = document.getElementById("cameraSelect");
  const startBtn = document.getElementById("startBtn");
  const hangupBtn = document.getElementById("hangupBtn");
  const muteBtn = document.getElementById("muteBtn");
  const torchBtn = document.getElementById("torchBtn");
  const screenBtn = document.getElementById("screenBtn");
  const videoSourceEl = document.getElementById("videoSource");
  const screenActiveEl = document.getElementById("screenActive");
  const screenPermEl = document.getElementById("screenPerm");
  const logView = document.getElementById("log");

  let pc = null;
  let remoteStream = null;
  let answerRef = null;
  let androidCandidatesRef = null;
  let controlsRef = null;
  let stateRef = null;

  let micMuted = false;
  let torchOn = false;
  let screenShare = false;

  const iceServers = [
    { urls: "stun:stun.l.google.com:19302" },
    {
      urls: [
        "turn:a.relay.metered.ca:3478?transport=udp",
        "turn:a.relay.metered.ca:3478?transport=tcp",
        "turn:a.relay.metered.ca:443?transport=tcp",
        "turns:a.relay.metered.ca:5349?transport=tcp",
      ],
      username: "83eebabf8b4cce9d5dbcb649",
      credential: "2D7JvfkOQtBdYW3R",
    },
  ];

  function log(msg) {
    logView.textContent += `${new Date().toISOString()} ${msg}\n`;
  }

  function setControlButtonsEnabled(enabled) {
    muteBtn.disabled = !enabled;
    torchBtn.disabled = !enabled;
    screenBtn.disabled = !enabled;
  }

  function updateScreenButtonText() {
    screenBtn.textContent = screenShare ? "Switch to Camera" : "Start Screen Share";
  }

  async function pushControls() {
    if (!controlsRef) return;
    await controlsRef.update({
      camera: cameraSelect.value,
      micMuted,
      torchOn,
      screenShare,
    });
  }

  function bindStateListener(callId) {
    stateRef = db.ref(`calls/${callId}/state`);
    stateRef.on("value", (snapshot) => {
      const state = snapshot.val() || {};
      const source = state.videoSource || "unknown";
      const active = !!state.screenShareActive;
      const permissionGranted = !!state.screenCapturePermissionGranted;

      videoSourceEl.textContent = source;
      screenActiveEl.textContent = String(active);
      screenPermEl.textContent = permissionGranted ? "granted" : "not granted";

      if (screenShare !== active) {
        screenShare = active;
        updateScreenButtonText();
      }
    });
  }

  function unbindStateListener() {
    if (stateRef) {
      stateRef.off();
      stateRef = null;
    }
  }

  function createPeerConnection(callId) {
    pc = new RTCPeerConnection({ iceServers, iceTransportPolicy: "all" });

    // Caller sends no local tracks; receive only.
    pc.addTransceiver("video", { direction: "recvonly" });
    pc.addTransceiver("audio", { direction: "recvonly" });

    remoteStream = new MediaStream();
    remoteVideo.srcObject = remoteStream;

    pc.ontrack = (event) => {
      event.streams[0].getTracks().forEach((track) => remoteStream.addTrack(track));
      log(`Remote track: ${event.track.kind}`);
    };

    pc.onicecandidate = async (event) => {
      if (!event.candidate) return;
      try {
        await db.ref(`calls/${callId}/candidates/browser`).push({
          candidate: event.candidate.candidate,
          sdpMid: event.candidate.sdpMid,
          sdpMLineIndex: event.candidate.sdpMLineIndex,
        });
      } catch (err) {
        log(`ICE push failed: ${err.message}`);
      }
    };

    pc.onconnectionstatechange = () => log(`pc.state = ${pc.connectionState}`);
  }

  async function startCall() {
    const callId = callIdInput.value.trim();
    if (!callId) throw new Error("Call ID is required.");

    createPeerConnection(callId);

    const callRef = db.ref(`calls/${callId}`);
    controlsRef = db.ref(`calls/${callId}/controls`);
    answerRef = db.ref(`calls/${callId}/answer`);
    androidCandidatesRef = db.ref(`calls/${callId}/candidates/android`);

    // Clear stale data for same callId.
    await callRef.remove();

    micMuted = false;
    torchOn = false;
    screenShare = false;
    muteBtn.textContent = "Mute Mic";
    torchBtn.textContent = "Torch On";
    updateScreenButtonText();

    await pushControls();
    bindStateListener(callId);

    const offer = await pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true });
    await pc.setLocalDescription(offer);

    await db.ref(`calls/${callId}/offer`).set({
      type: offer.type,
      sdp: offer.sdp,
    });
    setControlButtonsEnabled(true);
    log("Offer written.");

    answerRef.on("value", async (snapshot) => {
      const answer = snapshot.val();
      if (!answer || !answer.type || !answer.sdp || !pc || pc.currentRemoteDescription) return;
      try {
        await pc.setRemoteDescription(new RTCSessionDescription(answer));
        log("Answer applied.");
      } catch (err) {
        log(`Apply answer failed: ${err.message}`);
      }
    });

    androidCandidatesRef.on("child_added", async (snapshot) => {
      const candidate = snapshot.val();
      if (!candidate || !pc) return;
      try {
        await pc.addIceCandidate(new RTCIceCandidate(candidate));
      } catch (err) {
        log(`addIceCandidate error: ${err.message}`);
      }
    });
  }

  async function hangup() {
    if (answerRef) answerRef.off();
    if (androidCandidatesRef) androidCandidatesRef.off();
    unbindStateListener();
    answerRef = null;
    androidCandidatesRef = null;
    controlsRef = null;

    if (pc) {
      pc.close();
      pc = null;
    }

    if (remoteStream) {
      remoteStream.getTracks().forEach((t) => t.stop());
      remoteStream = null;
    }

    remoteVideo.srcObject = null;

    const callId = callIdInput.value.trim();
    if (callId) {
      await db.ref(`calls/${callId}`).remove();
    }

    setControlButtonsEnabled(false);
    log("Hangup + call data removed.");
  }

  startBtn.addEventListener("click", () => {
    startCall().catch((err) => log(`Start failed: ${err.message}`));
  });

  hangupBtn.addEventListener("click", () => {
    hangup().catch((err) => log(`Hangup failed: ${err.message}`));
  });

  cameraSelect.addEventListener("change", () => {
    pushControls().catch((err) => log(`Control update failed: ${err.message}`));
  });

  muteBtn.addEventListener("click", () => {
    micMuted = !micMuted;
    muteBtn.textContent = micMuted ? "Unmute Mic" : "Mute Mic";
    pushControls().catch((err) => log(`Mic control failed: ${err.message}`));
  });

  torchBtn.addEventListener("click", () => {
    torchOn = !torchOn;
    torchBtn.textContent = torchOn ? "Torch Off" : "Torch On";
    pushControls().catch((err) => log(`Torch control failed: ${err.message}`));
  });

  screenBtn.addEventListener("click", () => {
    screenShare = !screenShare;
    updateScreenButtonText();
    pushControls().catch((err) => log(`Screen control failed: ${err.message}`));
  });
})();
