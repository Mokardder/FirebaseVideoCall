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

  firebase.initializeApp(firebaseConfig);
  const db = firebase.database();

  const remoteVideo = document.getElementById("remoteVideo");
  const callIdInput = document.getElementById("callId");
  const cameraSelect = document.getElementById("cameraSelect");
  const startBtn = document.getElementById("startBtn");
  const hangupBtn = document.getElementById("hangupBtn");
  const muteBtn = document.getElementById("muteBtn");
  const torchBtn = document.getElementById("torchBtn");
  const sendFcmBtn = document.getElementById("sendFcmBtn");
  const fcmEndpointInput = document.getElementById("fcmEndpoint");
  const fcmTopicInput = document.getElementById("fcmTopic");
  const logView = document.getElementById("log");

  let pc = null;
  let remoteStream = null;
  let answerRef = null;
  let androidCandidatesRef = null;
  let controlsRef = null;

  let micMuted = false;
  let torchOn = false;

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
  }

  async function pushControls() {
    if (!controlsRef) return;
    await controlsRef.update({
      camera: cameraSelect.value,
      micMuted,
      torchOn,
    });
  }

  async function triggerAndroidByFcm() {
    const endpoint = fcmEndpointInput.value.trim();
    const topic = fcmTopicInput.value.trim();
    const callId = callIdInput.value.trim();

    if (!endpoint) {
      throw new Error("FCM endpoint is required.");
    }

    if (!topic) {
      throw new Error("FCM topic is required.");
    }

    if (!callId) {
      throw new Error("Call ID is required to trigger Android client.");
    }

    const payload = {
      topic,
      notification: {
        title: "Incoming WebRTC Call",
        body: `Join call: ${callId}`,
      },
      data: {
        action: "start_call",
        callId,
        timestamp: String(Date.now()),
      },
    };

    const response = await fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      const text = await response.text();
      throw new Error(`FCM request failed (${response.status}): ${text || "empty response"}`);
    }

    const body = await response.text();
    log(`FCM trigger sent. Response: ${body || "ok"}`);
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
    muteBtn.textContent = "Mute Mic";
    torchBtn.textContent = "Torch On";

    await pushControls();

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

  sendFcmBtn.addEventListener("click", () => {
    triggerAndroidByFcm().catch((err) => log(`FCM trigger failed: ${err.message}`));
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
})();
