package com.mokardder.androidrtcvideocall;

import androidx.annotation.NonNull;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.util.HashMap;
import java.util.Map;

public class CallDatabaseManager {
    private DatabaseReference callRef;
    private String callId;

    public void bind(@NonNull FirebaseApp app, @NonNull String nextCallId) {
        callId = nextCallId;
        callRef = FirebaseDatabase.getInstance(app).getReference("calls").child(nextCallId);
    }

    public void switchCall(@NonNull String nextCallId) {
        callId = nextCallId;
        callRef = FirebaseDatabase.getInstance().getReference("calls").child(nextCallId);
    }

    public String getCallId() {
        return callId;
    }

    public boolean isBound() {
        return callRef != null;
    }

    public void clearSignaling() {
        if (callRef == null) return;
        callRef.child("offer").removeValue();
        callRef.child("answer").removeValue();
        callRef.child("candidates").removeValue();
    }

    public void sendAndroidCandidate(@NonNull IceCandidate iceCandidate) {
        if (callRef == null) return;
        DatabaseReference cRef = callRef.child("candidates").child("android").push();
        Map<String, Object> json = new HashMap<>();
        json.put("candidate", iceCandidate.sdp);
        json.put("sdpMid", iceCandidate.sdpMid);
        json.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
        cRef.setValue(json);
    }

    public void publishAnswer(@NonNull SessionDescription sessionDescription) {
        if (callRef == null) return;
        Map<String, Object> answer = new HashMap<>();
        answer.put("type", sessionDescription.type.canonicalForm());
        answer.put("sdp", sessionDescription.description);
        callRef.child("answer").setValue(answer);
    }

    public void updateStreamState(@NonNull String videoSource, boolean screenShareActive) {
        if (callRef == null) return;
        callRef.child("state").child("videoSource").setValue(videoSource);
        callRef.child("state").child("screenShareActive").setValue(screenShareActive);
    }

    public void addOfferListener(@NonNull ValueEventListener listener) {
        if (callRef == null) return;
        callRef.child("offer").addValueEventListener(listener);
    }

    public void removeOfferListener(ValueEventListener listener) {
        if (callRef == null || listener == null) return;
        callRef.child("offer").removeEventListener(listener);
    }

    public void addBrowserCandidatesListener(@NonNull ChildEventListener listener) {
        if (callRef == null) return;
        callRef.child("candidates").child("browser").addChildEventListener(listener);
    }

    public void removeBrowserCandidatesListener(ChildEventListener listener) {
        if (callRef == null || listener == null) return;
        callRef.child("candidates").child("browser").removeEventListener(listener);
    }

    public void addControlsListener(@NonNull ValueEventListener listener) {
        if (callRef == null) return;
        callRef.child("controls").addValueEventListener(listener);
    }

    public void removeControlsListener(ValueEventListener listener) {
        if (callRef == null || listener == null) return;
        callRef.child("controls").removeEventListener(listener);
    }

    public void detachAll(ValueEventListener offerListener,
                          ChildEventListener browserCandidatesListener,
                          ValueEventListener controlsListener) {
        removeOfferListener(offerListener);
        removeBrowserCandidatesListener(browserCandidatesListener);
        removeControlsListener(controlsListener);
    }
}
