(() => {
  const firebaseConfig = {
    apiKey: "YOUR_API_KEY",
    authDomain: "YOUR_PROJECT.firebaseapp.com",
    databaseURL: "https://YOUR_PROJECT-default-rtdb.firebaseio.com",
    projectId: "YOUR_PROJECT",
    appId: "YOUR_APP_ID",
  };

  firebase.initializeApp(firebaseConfig);
  const db = firebase.database();

  const remoteVideo = document.getElementById("remoteVideo");
  const localVideo = document.getElementById("localVideo");
  const callIdInput = document.getElementById("callId");
  const mediaModeSelect = document.getElementById("mediaMode");
  const flashEnabledInput = document.getElementById("flashEnabled");
  const startBtn = document.getElementById("startBtn");
  const hangupBtn = document.getElementById("hangupBtn");
  const logView = document.getElementById("log");

  let pc = null;
  let localStream = null;
  let remoteStream = null;
  let answerRef = null;
  let androidCandidatesRef = null;

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

  async function maybeApplyTorch(videoTrack, enabled) {
    if (!videoTrack) return;
    if (!enabled) return;
    try {
      const capabilities = videoTrack.getCapabilities ? videoTrack.getCapabilities() : null;
      if (!capabilities || !capabilities.torch) {
        log("Torch requested but not supported on this device/camera.");
        return;
      }
      await videoTrack.applyConstraints({ advanced: [{ torch: true }] });
      log("Torch enabled.");
    } catch (err) {
      log(`Torch failed: ${err.message}`);
    }
  }

  async function tryGetUserMedia(constraints) {
    try {
      return await navigator.mediaDevices.getUserMedia(constraints);
    } catch (_) {
      return null;
    }
  }

  async function prepareLocalMedia(mode, flashEnabled) {
    const wantsVideo = mode !== "audio";
    if (!wantsVideo) {
      const audioOnly = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
      log("Local mode: audio-only.");
      return audioOnly;
    }

    const facingMode = mode === "back" ? { ideal: "environment" } : { ideal: "user" };
    const fullConstraints = { audio: true, video: { facingMode } };

    try {
      const full = await navigator.mediaDevices.getUserMedia(fullConstraints);
      await maybeApplyTorch(full.getVideoTracks()[0], flashEnabled);
      log(`Local mode: ${mode} camera + audio.`);
      return full;
    } catch (err) {
      // Fallback: if one device is busy/in use, degrade to available media.
      log(`Full media failed (${err.name || "unknown"}). Trying single-media fallbacks...`);
      const videoOnly = await tryGetUserMedia({ audio: false, video: { facingMode } });
      if (videoOnly) {
        await maybeApplyTorch(videoOnly.getVideoTracks()[0], flashEnabled);
        log("Microphone appears busy/in use; sending video only.");
        return videoOnly;
      }

      const audioOnly = await tryGetUserMedia({ audio: true, video: false });
      if (audioOnly) {
        log("Camera appears busy/in use; sending audio only.");
        return audioOnly;
      }

      throw err;
    }
  }

  function createPeerConnection(callId, stream) {
    pc = new RTCPeerConnection({ iceServers, iceTransportPolicy: "all" });

    const hasVideo = !!stream && stream.getVideoTracks().length > 0;
    const hasAudio = !!stream && stream.getAudioTracks().length > 0;

    if (hasVideo || hasAudio) {
      stream.getTracks().forEach((track) => pc.addTrack(track, stream));
    }

    if (!hasVideo) pc.addTransceiver("video", { direction: "recvonly" });
    if (!hasAudio) pc.addTransceiver("audio", { direction: "recvonly" });

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
    const mediaMode = mediaModeSelect.value;
    const flashEnabled = flashEnabledInput.checked;

    localStream = await prepareLocalMedia(mediaMode, flashEnabled);
    localVideo.srcObject = localStream;

    createPeerConnection(callId, localStream);

    const callRef = db.ref(`calls/${callId}`);
    answerRef = db.ref(`calls/${callId}/answer`);
    androidCandidatesRef = db.ref(`calls/${callId}/candidates/android`);

    // Clear stale data for same callId.
    await callRef.remove();

    await db.ref(`calls/${callId}/controls`).set({
      mediaMode,
      flashEnabled,
      updatedAt: Date.now(),
    });

    const offer = await pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true });
    await pc.setLocalDescription(offer);

    await db.ref(`calls/${callId}/offer`).set({
      type: offer.type,
      sdp: offer.sdp,
    });
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

    if (pc) {
      pc.close();
      pc = null;
    }

    if (remoteStream) {
      remoteStream.getTracks().forEach((t) => t.stop());
      remoteStream = null;
    }
    if (localStream) {
      localStream.getTracks().forEach((t) => t.stop());
      localStream = null;
    }

    remoteVideo.srcObject = null;
    localVideo.srcObject = null;

    const callId = callIdInput.value.trim();
    if (callId) {
      await db.ref(`calls/${callId}`).remove();
    }

    log("Hangup + call data removed.");
  }

  startBtn.addEventListener("click", () => {
    startCall().catch((err) => log(`Start failed: ${err.message}`));
  });

  hangupBtn.addEventListener("click", () => {
    hangup().catch((err) => log(`Hangup failed: ${err.message}`));
  });
})();
