package com.example.firebasevideocall;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERMS = 1101;
    private static final String CALL_ID = "demo-call-001";

    private TextView statusText;
    private RadioGroup cameraSelection;
    private Button startCallBtn;
    private Button muteMicBtn;
    private Button torchBtn;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private EglBase eglBase;
    private DatabaseReference callRef;

    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper textureHelper;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;

    private final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();

    private boolean isMicMuted = false;
    private boolean isTorchEnabled = false;
    private String activeCameraName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        cameraSelection = findViewById(R.id.cameraSelection);
        startCallBtn = findViewById(R.id.startCallBtn);
        muteMicBtn = findViewById(R.id.muteMicBtn);
        torchBtn = findViewById(R.id.torchBtn);

        startCallBtn.setOnClickListener(v -> {
            if (peerConnection != null) {
                setStatus("Call already initialized.");
                return;
            }
            init();
        });

        muteMicBtn.setOnClickListener(v -> toggleMicMute());
        torchBtn.setOnClickListener(v -> toggleTorch());
        setInCallControlsEnabled(false);

        if (!hasPerms()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQ_PERMS);
            return;
        }

        setStatus("Choose camera and tap Start Call.");
    }

    private boolean hasPerms() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && hasPerms()) {
            setStatus("Choose camera and tap Start Call.");
        } else {
            setStatus("Camera/mic permissions required.");
            startCallBtn.setEnabled(false);
        }
    }

    private void init() {
        setStatus("Initializing WebRTC...");
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .createInitializationOptions());

        eglBase = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();

        FirebaseApp firebaseApp = FirebaseApp.initializeApp(this);
        if (firebaseApp == null) {
            setStatus("Firebase init failed. Add google-services.json and google-services plugin.");
            return;
        }

        callRef = FirebaseDatabase.getInstance(firebaseApp).getReference("calls").child(CALL_ID);

        createPeerConnection();
        createAndAddLocalTracks();
        listenForOfferAndCandidates();
        startCallBtn.setEnabled(false);

        setStatus("Waiting for offer on calls/" + CALL_ID + "/offer");
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> servers = new ArrayList<>();
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        servers.add(PeerConnection.IceServer.builder("turn:a.relay.metered.ca:3478?transport=udp")
                .setUsername("83eebabf8b4cce9d5dbcb649")
                .setPassword("2D7JvfkOQtBdYW3R")
                .createIceServer());
        servers.add(PeerConnection.IceServer.builder("turn:a.relay.metered.ca:3478?transport=tcp")
                .setUsername("83eebabf8b4cce9d5dbcb649")
                .setPassword("2D7JvfkOQtBdYW3R")
                .createIceServer());
        servers.add(PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443?transport=tcp")
                .setUsername("83eebabf8b4cce9d5dbcb649")
                .setPassword("2D7JvfkOQtBdYW3R")
                .createIceServer());
        servers.add(PeerConnection.IceServer.builder("turns:a.relay.metered.ca:5349?transport=tcp")
                .setUsername("83eebabf8b4cce9d5dbcb649")
                .setPassword("2D7JvfkOQtBdYW3R")
                .createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(servers);
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL;

        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                setStatus("ICE: " + iceConnectionState.name());
                if (iceConnectionState == PeerConnection.IceConnectionState.FAILED && peerConnection != null) {
                    peerConnection.restartIce();
                }
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {}

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                DatabaseReference cRef = callRef.child("candidates").child("android").push();
                Map<String, Object> json = new HashMap<>();
                json.put("candidate", iceCandidate.sdp);
                json.put("sdpMid", iceCandidate.sdpMid);
                json.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                cRef.setValue(json);
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

            @Override
            public void onAddStream(org.webrtc.MediaStream mediaStream) {}

            @Override
            public void onRemoveStream(org.webrtc.MediaStream mediaStream) {}

            @Override
            public void onDataChannel(org.webrtc.DataChannel dataChannel) {}

            @Override
            public void onRenegotiationNeeded() {}

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStreamTrack[] mediaStreamTracks) {
                // Intentionally ignore remote tracks: Android should not display remote video.
            }
        });
    }

    private void createAndAddLocalTracks() {
        videoCapturer = createCameraCapturer();
        textureHelper = SurfaceTextureHelper.create("captureThread", eglBase.getEglBaseContext());

        videoSource = factory.createVideoSource(false);
        videoCapturer.initialize(textureHelper, getApplicationContext(), videoSource.getCapturerObserver());
        videoCapturer.startCapture(1280, 720, 30);

        localVideoTrack = factory.createVideoTrack("video0", videoSource);
        localVideoTrack.setEnabled(true);

        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);

        List<String> streamIds = new ArrayList<>();
        streamIds.add("stream0");
        peerConnection.addTrack(localVideoTrack, streamIds);
        peerConnection.addTrack(localAudioTrack, streamIds);
    }

    private VideoCapturer createCameraCapturer() {
        Camera2Enumerator enumerator = new Camera2Enumerator(this);
        boolean useFront = cameraSelection.getCheckedRadioButtonId() == R.id.frontCameraOption;
        String[] names = enumerator.getDeviceNames();

        for (String n : names) {
            if ((useFront && enumerator.isFrontFacing(n)) || (!useFront && !enumerator.isFrontFacing(n))) {
                CameraVideoCapturer c = enumerator.createCapturer(n, null);
                if (c != null) {
                    activeCameraName = n;
                    return c;
                }
            }
        }

        for (String n : names) {
            CameraVideoCapturer c = enumerator.createCapturer(n, null);
            if (c != null) {
                activeCameraName = n;
                return c;
            }
        }

        throw new IllegalStateException("No camera found");
    }

    private void listenForOfferAndCandidates() {
        callRef.child("offer").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                if (peerConnection == null || peerConnection.getRemoteDescription() != null) return;

                String type = snapshot.child("type").getValue(String.class);
                String sdp = snapshot.child("sdp").getValue(String.class);
                if (type == null || sdp == null) return;

                SessionDescription offer = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp);
                peerConnection.setRemoteDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {}

                    @Override
                    public void onSetSuccess() {
                        drainPendingRemoteCandidates();
                        createAndSendAnswer();
                        setInCallControlsEnabled(true);
                    }

                    @Override
                    public void onCreateFailure(String s) {}

                    @Override
                    public void onSetFailure(String s) {
                        setStatus("Remote SDP set failed: " + s);
                    }
                }, offer);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                setStatus("Offer listener error: " + error.getMessage());
            }
        });

        callRef.child("candidates").child("browser").addChildEventListener(new com.google.firebase.database.ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snapshot, String previousChildName) {
                String candidate = snapshot.child("candidate").getValue(String.class);
                String sdpMid = snapshot.child("sdpMid").getValue(String.class);
                Integer sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Integer.class);
                if (candidate == null || sdpMid == null || sdpMLineIndex == null) return;
                IceCandidate remote = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
                if (peerConnection == null) return;
                if (peerConnection.getRemoteDescription() == null) {
                    pendingRemoteCandidates.add(remote);
                    return;
                }
                peerConnection.addIceCandidate(remote);
            }

            @Override public void onChildChanged(@NonNull DataSnapshot snapshot, String previousChildName) {}
            @Override public void onChildRemoved(@NonNull DataSnapshot snapshot) {}
            @Override public void onChildMoved(@NonNull DataSnapshot snapshot, String previousChildName) {}
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    private void drainPendingRemoteCandidates() {
        if (peerConnection == null || peerConnection.getRemoteDescription() == null) return;
        for (IceCandidate c : pendingRemoteCandidates) {
            peerConnection.addIceCandidate(c);
        }
        pendingRemoteCandidates.clear();
    }

    private void createAndSendAnswer() {
        peerConnection.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                Map<String, Object> answer = new HashMap<>();
                answer.put("type", sessionDescription.type.canonicalForm());
                answer.put("sdp", sessionDescription.description);
                callRef.child("answer").setValue(answer);
                setStatus("Auto-answered. Sending camera+audio.");
            }

            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String s) { setStatus("Answer create failed: " + s); }
            @Override public void onSetFailure(String s) { setStatus("Answer set failed: " + s); }
        }, new MediaConstraints());
    }

    private void toggleMicMute() {
        if (localAudioTrack == null) {
            setStatus("Mic control available after call starts.");
            return;
        }
        isMicMuted = !isMicMuted;
        localAudioTrack.setEnabled(!isMicMuted);
        muteMicBtn.setText(isMicMuted ? "Unmute Mic" : "Mute Mic");
        setStatus(isMicMuted ? "Microphone muted." : "Microphone unmuted.");
    }

    private void toggleTorch() {
        if (activeCameraName == null) {
            setStatus("Torch control available after call starts.");
            return;
        }

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            setStatus("Torch not supported on this device.");
            return;
        }

        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(activeCameraName);
            Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (hasFlash == null || !hasFlash) {
                setStatus("Selected camera has no torch.");
                return;
            }

            isTorchEnabled = !isTorchEnabled;
            cameraManager.setTorchMode(activeCameraName, isTorchEnabled);
            torchBtn.setText(isTorchEnabled ? "Torch Off" : "Torch On");
            setStatus(isTorchEnabled ? "Torch enabled." : "Torch disabled.");
        } catch (Exception e) {
            setStatus("Torch toggle failed: " + e.getMessage());
        }
    }

    private void setInCallControlsEnabled(boolean enabled) {
        muteMicBtn.setEnabled(enabled);
        torchBtn.setEnabled(enabled);
    }

    private void setStatus(String msg) {
        runOnUiThread(() -> statusText.setText(msg));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoCapturer != null) {
            try { videoCapturer.stopCapture(); } catch (InterruptedException ignored) {}
            videoCapturer.dispose();
        }
        if (localVideoTrack != null) localVideoTrack.dispose();
        if (localAudioTrack != null) localAudioTrack.dispose();
        if (videoSource != null) videoSource.dispose();
        if (audioSource != null) audioSource.dispose();
        if (textureHelper != null) textureHelper.dispose();
        if (peerConnection != null) peerConnection.close();
        if (factory != null) factory.dispose();
        if (eglBase != null) eglBase.release();
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) {}
        @Override public void onSetFailure(String s) {}
    }
}
