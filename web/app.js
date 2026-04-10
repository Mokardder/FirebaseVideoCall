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
  const auth = firebase.auth();

  const remoteVideo = document.getElementById("remoteVideo");
  const callIdInput = document.getElementById("callId");
  const startBtn = document.getElementById("startBtn");
  const hangupBtn = document.getElementById("hangupBtn");
  const logView = document.getElementById("log");

  let pc = null;
  let remoteStream = null;
  let answerRef = null;
  let androidCandidatesRef = null;

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

  async function ensureSignedIn() {
    if (auth.currentUser) return auth.currentUser;

    try {
      const result = await auth.signInAnonymously();
      log(`Signed in anonymously: uid=${result.user.uid}`);
      return result.user;
    } catch (err) {
      throw new Error(
        `Auth failed (${err.code || "unknown"}). Enable Anonymous auth in Firebase Console or relax RTDB rules for testing.`
      );
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

    await ensureSignedIn();
    createPeerConnection(callId);

    const callRef = db.ref(`calls/${callId}`);
    answerRef = db.ref(`calls/${callId}/answer`);
    androidCandidatesRef = db.ref(`calls/${callId}/candidates/android`);

    // Clear stale data for same callId.
    await callRef.remove();

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

    remoteVideo.srcObject = null;

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
