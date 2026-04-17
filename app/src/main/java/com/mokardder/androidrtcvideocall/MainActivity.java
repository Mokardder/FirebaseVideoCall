package com.mokardder.androidrtcvideocall;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessaging;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.FlashlightCameraCapturer;
import org.webrtc.FlashlightCameraEnumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpSender;
import org.webrtc.ScreenCapturerAndroid;
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
    private static final String TAG = "AndroidRtcVideoCall";
    private static final String DEFAULT_CALL_ID = "demo-call-001";
    private static final int CAPTURE_WIDTH = 1280;
    private static final int CAPTURE_HEIGHT = 720;
    private static final int CAPTURE_FPS = 30;
    private static final String VIDEO_SOURCE_CAMERA = "camera";
    private static final String VIDEO_SOURCE_SCREEN = "screen";
    public static final String FCM_CALL_TOPIC = "video_call_android";
    public static final String EXTRA_START_FROM_FCM = "extra_start_from_fcm";
    public static final String EXTRA_CALL_ID = "extra_call_id";

    private TextView statusText;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private EglBase eglBase;
    private WebRtcEngine webRtcEngine;
    private DatabaseReference callRef;
    private String currentCallId = DEFAULT_CALL_ID;

    private VideoCapturer videoCapturer;
    private FlashlightCameraCapturer flashlightCapturer;
    private SurfaceTextureHelper textureHelper;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private RtpSender localVideoSender;
    private RtpSender localAudioSender;

    private final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();
    private boolean preferFrontCamera = true;
    private boolean isMicMuted = false;
    private boolean isTorchEnabled = false;
    private String activeCameraName;
    private PowerManager.WakeLock wakeLock;
    private ValueEventListener offerListener;
    private ChildEventListener browserCandidatesListener;
    private ValueEventListener controlsListener;
    private boolean isReconnecting = false;
    private String lastHandledOfferSdp;
    private boolean useScreenShare = false;
    private MediaProjectionManager mediaProjectionManager;
    private Intent screenCapturePermissionData;
    private int screenCapturePermissionCode = Activity.RESULT_CANCELED;
    private final ActivityResultLauncher<Intent> screenCapturePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    screenCapturePermissionCode = result.getResultCode();
                    screenCapturePermissionData = result.getData();
                    useScreenShare = true;
                    setStatus("Screen capture permission granted.");
                    restartVideoTrackForCurrentSource();
                } else {
                    useScreenShare = false;
                    updateWebStreamState();
                    setStatus("Screen capture permission denied. Using camera.");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.statusText);
        handleLaunchIntent(getIntent());


        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!hasPerms()) {


            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    REQ_PERMS);
            return;
        }

        init();
    }

    private void handleLaunchIntent(Intent launchIntent) {
        if (launchIntent == null) return;
        boolean launchedFromFcm = launchIntent.getBooleanExtra(EXTRA_START_FROM_FCM, false);
        String incomingCallId = launchIntent.getStringExtra(EXTRA_CALL_ID);
        if (launchedFromFcm) {
            switchToCallId(incomingCallId);
        }

        if (!launchedFromFcm) {
            if (incomingCallId != null && !incomingCallId.isEmpty()) {
                switchToCallId(incomingCallId);
            }
            return;
        }

        if (incomingCallId == null || incomingCallId.isEmpty()) {
            setStatus("App opened from FCM call request.");
        } else {
            setStatus("App opened from FCM for callId: " + incomingCallId);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    private boolean hasPerms() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && hasPerms()) {
            init();
        } else {
            setStatus("Camera/mic permissions required.");
        }
    }

    private void init() {
        startCallService();
        acquireWakeLock();
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        setStatus("Initializing WebRTC...");
        webRtcEngine = new WebRtcEngine(this);
        webRtcEngine.initialize();
        eglBase = webRtcEngine.getEglBase();
        factory = webRtcEngine.getPeerConnectionFactory();

        FirebaseApp firebaseApp = FirebaseApp.initializeApp(this);
        if (firebaseApp == null) {
            setStatus("Firebase init failed. Add google-services.json and google-services plugin.");
            return;
        }

        callRef = FirebaseDatabase.getInstance(firebaseApp).getReference("calls").child(currentCallId);
        subscribeForCallTopic();

        createPeerConnection();
        createAndAddLocalTracks();
        listenForOfferAndCandidates();
        listenForControls();

        setStatus("Waiting for offer on calls/" + currentCallId + "/offer");
    }

    private void switchToCallId(String nextCallId) {
        String resolvedCallId = (nextCallId == null || nextCallId.isEmpty()) ? DEFAULT_CALL_ID : nextCallId;
        if (resolvedCallId.equals(currentCallId)) {
            return;
        }

        currentCallId = resolvedCallId;
        if (callRef == null) {
            return;
        }

        detachCallListeners(callRef);
        callRef = FirebaseDatabase.getInstance().getReference("calls").child(currentCallId);
        pendingRemoteCandidates.clear();
        lastHandledOfferSdp = null;
        listenForOfferAndCandidates();
        listenForControls();
        updateWebStreamState();
        setStatus("Switched to calls/" + currentCallId + ". Waiting for offer...");
    }

    private void subscribeForCallTopic() {
        FirebaseMessaging.getInstance()
                .subscribeToTopic(FCM_CALL_TOPIC)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        setStatus("Subscribed to FCM topic: " + FCM_CALL_TOPIC);
                    } else {
                        Exception error = task.getException();
                        if (error == null) {
                            setStatus("Failed to subscribe FCM topic.");
                        } else {
                            setStatus("Failed FCM topic subscribe: " + error.getMessage());
                        }
                    }
                });
    }

    private void createPeerConnection() {
        if (webRtcEngine == null) {
            setStatus("WebRTC engine is not initialized.");
            return;
        }
        peerConnection = webRtcEngine.createPeerConnection(new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {}

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                setStatus("ICE: " + iceConnectionState.name());
                if (iceConnectionState == PeerConnection.IceConnectionState.FAILED && peerConnection != null) {
                    peerConnection.restartIce();
                }
                if (iceConnectionState == PeerConnection.IceConnectionState.FAILED ||
                        iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED ||
                        iceConnectionState == PeerConnection.IceConnectionState.CLOSED) {

                    setStatus("Reconnecting...");

                    reconnectCall();
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

        });
    }

    private void reconnectCall() {
        if (isReconnecting) return;
        isReconnecting = true;
        runOnUiThread(() -> {
            try {
                resetPeerConnectionForNextCall();

                // Clear Firebase old signaling
                callRef.child("offer").removeValue();
                callRef.child("answer").removeValue();
                callRef.child("candidates").removeValue();
                lastHandledOfferSdp = null;

                // Re-listen for new offer
                listenForOfferAndCandidates();

                setStatus("Waiting for reconnection offer...");
            } catch (Exception e) {
                setStatus("Reconnect failed: " + e.getMessage());
            } finally {
                isReconnecting = false;
            }
        });
    }

    private void createAndAddLocalTracks() {
        setupVideoCaptureTrack();
        audioSource = factory.createAudioSource(new MediaConstraints());
        localAudioTrack = factory.createAudioTrack("audio0", audioSource);
        localAudioTrack.setEnabled(true);

        List<String> streamIds = new ArrayList<>();
        streamIds.add("stream0");
        localVideoSender = peerConnection.addTrack(localVideoTrack, streamIds);
        localAudioSender = peerConnection.addTrack(localAudioTrack, streamIds);
        updateWebStreamState();
    }

    private CameraEnumerator getCameraEnumerator() {
        return new FlashlightCameraEnumerator(true);
    }

    private VideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator = getCameraEnumerator();
        String[] names = enumerator.getDeviceNames();

        for (String n : names) {
            if ((preferFrontCamera && enumerator.isFrontFacing(n)) || (!preferFrontCamera && !enumerator.isFrontFacing(n))) {
                CameraVideoCapturer c = enumerator.createCapturer(n, null);
                if (c != null) {
                    activeCameraName = n;
                    if (c instanceof FlashlightCameraCapturer) flashlightCapturer = (FlashlightCameraCapturer) c;
                    return c;
                }
            }
        }

        for (String n : names) {
            CameraVideoCapturer c = enumerator.createCapturer(n, null);
            if (c != null) {
                activeCameraName = n;
                if (c instanceof FlashlightCameraCapturer) flashlightCapturer = (FlashlightCameraCapturer) c;
                return c;
            }
        }
        throw new IllegalStateException("No camera found");
    }

    private VideoCapturer createScreenCapturer() {
        if (screenCapturePermissionData == null || mediaProjectionManager == null) return null;
        return new ScreenCapturerAndroid(
                screenCapturePermissionData,
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        runOnUiThread(() -> {
                            if (useScreenShare) {
                                useScreenShare = false;
                                setStatus("Screen sharing stopped by system. Switching to camera.");
                                restartVideoTrackForCurrentSource();
                            }
                        });
                    }
                }
        );
    }

    private void setupVideoCaptureTrack() {
        VideoCapturer nextCapturer = null;
        boolean wantsScreen = useScreenShare;
        if (wantsScreen) {
            nextCapturer = createScreenCapturer();
        }
        if (nextCapturer == null) {
            useScreenShare = false;
            nextCapturer = createCameraCapturer();
        }

        videoCapturer = nextCapturer;
        textureHelper = SurfaceTextureHelper.create("captureThread", eglBase.getEglBaseContext());
        videoSource = factory.createVideoSource(false);
        videoCapturer.initialize(textureHelper, getApplicationContext(), videoSource.getCapturerObserver());
        videoCapturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS);

        localVideoTrack = factory.createVideoTrack("video0", videoSource);
        localVideoTrack.setEnabled(true);
        setTorchEnabled(isTorchEnabled);
    }

    private void restartVideoTrackForCurrentSource() {
        if (factory == null || peerConnection == null || eglBase == null) return;
        disposeVideoCaptureOnly();
        setupVideoCaptureTrack();

        if (localVideoSender != null) {
            localVideoSender.setTrack(localVideoTrack, true);
        } else {
            List<String> streamIds = new ArrayList<>();
            streamIds.add("stream0");
            localVideoSender = peerConnection.addTrack(localVideoTrack, streamIds);
        }
        updateWebStreamState();
    }

    private void disposeVideoCaptureOnly() {
        setTorchEnabled(false);
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException ignored) {}
            videoCapturer.dispose();
            videoCapturer = null;
            flashlightCapturer = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.dispose();
            localVideoTrack = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (textureHelper != null) {
            textureHelper.dispose();
            textureHelper = null;
        }
    }

    private void listenForControls() {
        if (controlsListener != null) {
            callRef.child("controls").removeEventListener(controlsListener);
        }

        controlsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                String camera = snapshot.child("camera").getValue(String.class);
                Boolean micMuted = snapshot.child("micMuted").getValue(Boolean.class);
                Boolean torchOn = snapshot.child("torchOn").getValue(Boolean.class);
                Boolean screenShare = snapshot.child("screenShare").getValue(Boolean.class);

                if (camera != null) {
                    boolean nextFront = !"back".equalsIgnoreCase(camera);
                    if (nextFront != preferFrontCamera) {
                        preferFrontCamera = nextFront;
                        switchCamera();
                    }
                }

                if (micMuted != null) {
                    setMicMuted(micMuted);
                }

                if (torchOn != null) {
                    setTorchEnabled(torchOn);
                }

                if (screenShare != null && screenShare != useScreenShare) {
                    toggleScreenShare(screenShare);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                setStatus("Controls listener error: " + error.getMessage());
            }
        };
        callRef.child("controls").addValueEventListener(controlsListener);
    }

    private void switchCamera() {
        if (useScreenShare) return;
        if (!(videoCapturer instanceof CameraVideoCapturer)) return;
        ((CameraVideoCapturer) videoCapturer).switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override
            public void onCameraSwitchDone(boolean isFrontCamera) {
                preferFrontCamera = isFrontCamera;
                findActiveCameraName(isFrontCamera);
                setStatus("Camera switched to " + (isFrontCamera ? "front" : "back"));
                if (!isFrontCamera && isTorchEnabled) {
                    setTorchEnabled(true);
                }
            }

            @Override
            public void onCameraSwitchError(String errorDescription) {
                setStatus("Camera switch failed: " + errorDescription);
            }
        });
    }

    private void findActiveCameraName(boolean isFront) {
        CameraEnumerator enumerator = getCameraEnumerator();
        for (String n : enumerator.getDeviceNames()) {
            if ((isFront && enumerator.isFrontFacing(n)) || (!isFront && !enumerator.isFrontFacing(n))) {
                activeCameraName = n;
                return;
            }
        }
    }

    private void setMicMuted(boolean muted) {
        isMicMuted = muted;
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(!isMicMuted);
        }
    }

    private void setTorchEnabled(boolean enabled) {
        isTorchEnabled = enabled;
        if (useScreenShare && enabled) {
            return;
        }
        if (flashlightCapturer == null) {
            if (enabled) setStatus("Torch capturer is not ready yet.");
            return;
        }

        boolean applied = flashlightCapturer.setFlashlightActive(enabled);
        if (!applied && enabled) {
            setStatus("Torch is not supported by current camera/capturer.");
        }
    }

    private void toggleScreenShare(boolean enable) {
        if (enable) {
            if (screenCapturePermissionData != null) {
                useScreenShare = true;
                restartVideoTrackForCurrentSource();
                return;
            }
            if (mediaProjectionManager == null) {
                setStatus("Screen sharing is unavailable on this device.");
                return;
            }
            setStatus("Requesting screen capture permission...");
            screenCapturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent());
            return;
        }

        if (useScreenShare) {
            useScreenShare = false;
            restartVideoTrackForCurrentSource();
        }
    }

    private void updateWebStreamState() {
        if (callRef == null) return;
        callRef.child("state").child("videoSource").setValue(useScreenShare ? VIDEO_SOURCE_SCREEN : VIDEO_SOURCE_CAMERA);
        callRef.child("state").child("screenShareActive").setValue(useScreenShare);
    }

    private void resetPeerConnectionForNextCall() {
        setTorchEnabled(false);
        pendingRemoteCandidates.clear();
        localVideoSender = null;
        localAudioSender = null;

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        disposeVideoCaptureOnly();
        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }

        createPeerConnection();
        createAndAddLocalTracks();
        setMicMuted(isMicMuted);
        setTorchEnabled(isTorchEnabled);
    }

    private void listenForOfferAndCandidates() {
        detachCallListeners(callRef);

        offerListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                if (peerConnection == null) return;

                String type = snapshot.child("type").getValue(String.class);
                String sdp = snapshot.child("sdp").getValue(String.class);
                if (type == null || sdp == null) return;
                if (!"offer".equalsIgnoreCase(type)) return;
                if (sdp.equals(lastHandledOfferSdp)) return;

                if (peerConnection.getRemoteDescription() != null) {
                    resetPeerConnectionForNextCall();
                }

                SessionDescription offer = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp);
                peerConnection.setRemoteDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {}

                    @Override
                    public void onSetSuccess() {
                        lastHandledOfferSdp = sdp;
                        drainPendingRemoteCandidates();
                        createAndSendAnswer();
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
        };
        callRef.child("offer").addValueEventListener(offerListener);

        browserCandidatesListener = new ChildEventListener() {
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
        };
        callRef.child("candidates").child("browser").addChildEventListener(browserCandidatesListener);
    }

    private void detachCallListeners(DatabaseReference ref) {
        if (ref == null) return;
        if (offerListener != null) {
            ref.child("offer").removeEventListener(offerListener);
        }
        if (browserCandidatesListener != null) {
            ref.child("candidates").child("browser").removeEventListener(browserCandidatesListener);
        }
        if (controlsListener != null) {
            ref.child("controls").removeEventListener(controlsListener);
        }
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
                setStatus("Auto-answered. Sending " + (useScreenShare ? "screen" : "camera") + "+audio.");
            }

            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String s) { setStatus("Answer create failed: " + s); }
            @Override public void onSetFailure(String s) { setStatus("Answer set failed: " + s); }
        }, new MediaConstraints());
    }


    private void startCallService() {
        Intent intent = new Intent(this, CallForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopCallService() {
        stopService(new Intent(this, CallForegroundService.class));
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;

        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RTC:WakeLock"
        );

        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(10 * 60 * 1000L); // 10 min
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void setStatus(String msg) {
        Log.d(TAG, msg);
        runOnUiThread(() -> statusText.setText(msg));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detachCallListeners(callRef);
        setTorchEnabled(false);
        disposeVideoCaptureOnly();
        if (localAudioTrack != null) localAudioTrack.dispose();
        if (audioSource != null) audioSource.dispose();
        if (peerConnection != null) peerConnection.close();
        if (webRtcEngine != null) webRtcEngine.dispose();
        releaseWakeLock();
        stopCallService();
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) {}
        @Override public void onSetFailure(String s) {}
    }
}
