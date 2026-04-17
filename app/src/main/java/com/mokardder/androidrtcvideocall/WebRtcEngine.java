package com.mokardder.androidrtcvideocall;

import android.content.Context;

import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;

import java.util.ArrayList;
import java.util.List;

public class WebRtcEngine {
    private final Context appContext;
    private EglBase eglBase;
    private PeerConnectionFactory peerConnectionFactory;

    public WebRtcEngine(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void initialize() {
        if (peerConnectionFactory != null && eglBase != null) {
            return;
        }

        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .createInitializationOptions()
        );

        eglBase = EglBase.create();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    public PeerConnection createPeerConnection(PeerConnection.Observer observer) {
        if (peerConnectionFactory == null) {
            initialize();
        }
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(defaultIceServers());
        config.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        return peerConnectionFactory.createPeerConnection(config, observer);
    }

    public PeerConnectionFactory getPeerConnectionFactory() {
        return peerConnectionFactory;
    }

    public EglBase getEglBase() {
        return eglBase;
    }

    public void dispose() {
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    private static List<PeerConnection.IceServer> defaultIceServers() {
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
        return servers;
    }
}
