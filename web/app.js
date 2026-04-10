import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getDatabase,
  ref,
  set,
  push,
  onValue,
  onChildAdded,
  off,
  get,
  remove,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-database.js";

const firebaseConfig = {
  apiKey: "YOUR_API_KEY",
  authDomain: "YOUR_PROJECT.firebaseapp.com",
  databaseURL: "https://YOUR_PROJECT-default-rtdb.firebaseio.com",
  projectId: "YOUR_PROJECT",
  appId: "YOUR_APP_ID",
};

const app = initializeApp(firebaseConfig);
const db = getDatabase(app);

const remoteVideo = document.getElementById("remoteVideo");
const callIdInput = document.getElementById("callId");
const startBtn = document.getElementById("startBtn");
const hangupBtn = document.getElementById("hangupBtn");
const logView = document.getElementById("log");

let pc;
let unsubscribers = [];

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

function createPeerConnection(callId) {
  const cfg = { iceServers, iceTransportPolicy: "all" };
  pc = new RTCPeerConnection(cfg);

  // Caller should not send video/audio; only receive from Android.
  pc.addTransceiver("video", { direction: "recvonly" });
  pc.addTransceiver("audio", { direction: "recvonly" });

  const remoteStream = new MediaStream();
  remoteVideo.srcObject = remoteStream;

  pc.ontrack = (event) => {
    event.streams[0].getTracks().forEach((t) => remoteStream.addTrack(t));
    log(`Remote track: ${event.track.kind}`);
  };

  pc.onicecandidate = async (event) => {
    if (!event.candidate) return;
    const cRef = ref(db, `calls/${callId}/candidates/browser`);
    await push(cRef, {
      candidate: event.candidate.candidate,
      sdpMid: event.candidate.sdpMid,
      sdpMLineIndex: event.candidate.sdpMLineIndex,
    });
  };

  pc.onconnectionstatechange = () => log(`pc.state = ${pc.connectionState}`);
}

async function startCall() {
  const callId = callIdInput.value.trim();
  if (!callId) return log("Call ID is required.");

  createPeerConnection(callId);

  const callRef = ref(db, `calls/${callId}`);
  const offerRef = ref(db, `calls/${callId}/offer`);
  const answerRef = ref(db, `calls/${callId}/answer`);
  const androidCandidatesRef = ref(db, `calls/${callId}/candidates/android`);

  // optional: clear old call
  await remove(callRef);

  const offer = await pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true });
  await pc.setLocalDescription(offer);
  await set(offerRef, { type: offer.type, sdp: offer.sdp });
  log("Offer written.");

  const answerUnsub = onValue(answerRef, async (snap) => {
    const answer = snap.val();
    if (!answer || pc.currentRemoteDescription) return;
    await pc.setRemoteDescription(new RTCSessionDescription(answer));
    log("Answer applied.");
  });
  unsubscribers.push(() => off(answerRef, "value", answerUnsub));

  const candUnsub = onChildAdded(androidCandidatesRef, async (snap) => {
    const c = snap.val();
    if (!c || !pc) return;
    try {
      await pc.addIceCandidate(new RTCIceCandidate(c));
    } catch (e) {
      log(`addIceCandidate error: ${e.message}`);
    }
  });
  unsubscribers.push(() => off(androidCandidatesRef, "child_added", candUnsub));

  const existingAnswer = await get(answerRef);
  if (existingAnswer.exists() && !pc.currentRemoteDescription) {
    await pc.setRemoteDescription(new RTCSessionDescription(existingAnswer.val()));
    log("Existing answer applied.");
  }
}

async function hangup() {
  const callId = callIdInput.value.trim();
  for (const un of unsubscribers) un();
  unsubscribers = [];

  if (pc) {
    pc.getSenders().forEach((s) => s.track && s.track.stop());
    pc.close();
    pc = null;
  }
  remoteVideo.srcObject = null;

  if (callId) {
    await remove(ref(db, `calls/${callId}`));
  }
  log("Hangup + call data removed.");
}

startBtn.onclick = () => startCall().catch((e) => log(`Start failed: ${e.message}`));
hangupBtn.onclick = () => hangup().catch((e) => log(`Hangup failed: ${e.message}`));
