(() => {
  const remoteVideo = document.getElementById("remoteVideo");
  const callIdInput = document.getElementById("callId");
  const dbUrlInput = document.getElementById("dbUrl");
  const startBtn = document.getElementById("startBtn");
  const hangupBtn = document.getElementById("hangupBtn");
  const logView = document.getElementById("log");

  let pc = null;
  let remoteStream = null;
  let answerPollTimer = null;
  let candidatePollTimer = null;
  let seenAndroidCandidateKeys = new Set();

  const iceServers = [
    { urls: "stun:stun.l.google.com:19302" },
    {
      urls: [
        "turn:YOUR_TURN_HOST:3478?transport=udp",
        "turn:YOUR_TURN_HOST:3478?transport=tcp",
        "turns:YOUR_TURN_HOST:5349?transport=tcp",
      ],
      username: "YOUR_TURN_USERNAME",
      credential: "YOUR_TURN_PASSWORD",
    },
  ];

  function log(msg) {
    logView.textContent += `${new Date().toISOString()} ${msg}\n`;
  }

  function ensureDbUrl() {
    const base = dbUrlInput.value.trim().replace(/\/$/, "");
    if (!base.startsWith("https://")) {
      throw new Error("databaseURL must start with https://");
    }
    return base;
  }

  function url(base, path) {
    return `${base}/${path}.json`;
  }

  async function rtdbPut(base, path, data) {
    const res = await fetch(url(base, path), {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
    });
    if (!res.ok) throw new Error(`PUT ${path} failed (${res.status})`);
    return res.json();
  }

  async function rtdbPost(base, path, data) {
    const res = await fetch(url(base, path), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(data),
    });
    if (!res.ok) throw new Error(`POST ${path} failed (${res.status})`);
    return res.json();
  }

  async function rtdbGet(base, path) {
    const res = await fetch(url(base, path), { method: "GET" });
    if (!res.ok) throw new Error(`GET ${path} failed (${res.status})`);
    return res.json();
  }

  async function rtdbDelete(base, path) {
    const res = await fetch(url(base, path), { method: "DELETE" });
    if (!res.ok) throw new Error(`DELETE ${path} failed (${res.status})`);
  }

  function createPeerConnection(callId, base) {
    pc = new RTCPeerConnection({ iceServers, iceTransportPolicy: "all" });

    // Caller should not send local media.
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
        await rtdbPost(base, `calls/${callId}/candidates/browser`, {
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

  function startPollingAnswer(callId, base) {
    clearInterval(answerPollTimer);
    answerPollTimer = setInterval(async () => {
      if (!pc || pc.currentRemoteDescription) return;
      try {
        const answer = await rtdbGet(base, `calls/${callId}/answer`);
        if (!answer || !answer.type || !answer.sdp) return;
        await pc.setRemoteDescription(new RTCSessionDescription(answer));
        log("Answer applied.");
      } catch (err) {
        log(`Answer poll error: ${err.message}`);
      }
    }, 1000);
  }

  function startPollingAndroidCandidates(callId, base) {
    clearInterval(candidatePollTimer);
    candidatePollTimer = setInterval(async () => {
      if (!pc) return;
      try {
        const candidates = await rtdbGet(base, `calls/${callId}/candidates/android`);
        if (!candidates || typeof candidates !== "object") return;

        for (const [key, value] of Object.entries(candidates)) {
          if (seenAndroidCandidateKeys.has(key)) continue;
          seenAndroidCandidateKeys.add(key);
          if (!value || !value.candidate || value.sdpMLineIndex === undefined || !value.sdpMid) continue;

          try {
            await pc.addIceCandidate(new RTCIceCandidate(value));
          } catch (err) {
            log(`addIceCandidate error: ${err.message}`);
          }
        }
      } catch (err) {
        log(`Candidate poll error: ${err.message}`);
      }
    }, 1000);
  }

  async function startCall() {
    const callId = callIdInput.value.trim();
    if (!callId) throw new Error("Call ID is required.");

    const base = ensureDbUrl();
    seenAndroidCandidateKeys = new Set();

    createPeerConnection(callId, base);

    // Clean stale signaling payload for same callId.
    await rtdbDelete(base, `calls/${callId}`);

    const offer = await pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true });
    await pc.setLocalDescription(offer);

    await rtdbPut(base, `calls/${callId}/offer`, {
      type: offer.type,
      sdp: offer.sdp,
    });
    log("Offer written.");

    startPollingAnswer(callId, base);
    startPollingAndroidCandidates(callId, base);
  }

  async function hangup() {
    clearInterval(answerPollTimer);
    clearInterval(candidatePollTimer);
    answerPollTimer = null;
    candidatePollTimer = null;

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
      const base = ensureDbUrl();
      await rtdbDelete(base, `calls/${callId}`);
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
