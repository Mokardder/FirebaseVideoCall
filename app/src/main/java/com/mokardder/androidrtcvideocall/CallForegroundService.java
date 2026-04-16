package com.mokardder.androidrtcvideocall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class CallForegroundService extends Service {
    public static final String EXTRA_LISTEN_CALL_ID = "extra_listen_call_id";
    private static final String CHANNEL_ID = "webrtc_call_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String TAG = "CallForegroundService";
    private static final String DEFAULT_CALL_ID = "demo-call-001";

    private DatabaseReference callRef;
    private ValueEventListener offerListener;
    private String listeningCallId = DEFAULT_CALL_ID;
    private String lastSeenOfferSdp;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannelIfNeeded();
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for call / streaming in background"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String requestedCallId = null;
        if (intent != null) {
            requestedCallId = intent.getStringExtra(EXTRA_LISTEN_CALL_ID);
        }
        if (!TextUtils.isEmpty(requestedCallId)) {
            listeningCallId = requestedCallId;
        }

        startListeningForOffer(listeningCallId);
        updateNotification("Listening for offer on callId: " + listeningCallId);

        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), CallForegroundService.class);
        restartServiceIntent.setPackage(getPackageName());
        restartServiceIntent.putExtra(EXTRA_LISTEN_CALL_ID, listeningCallId);
        startService(restartServiceIntent);
        super.onTaskRemoved(rootIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopListeningForOffer();
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "WebRTC Background Call",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String contentText) {
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        contentIntent.putExtra(MainActivity.EXTRA_START_FROM_FCM, true);
        contentIntent.putExtra(MainActivity.EXTRA_CALL_ID, listeningCallId);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Video call running")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void updateNotification(String contentText) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(NOTIFICATION_ID, buildNotification(contentText));
    }

    private void startListeningForOffer(String callId) {
        FirebaseApp app = FirebaseApp.initializeApp(this);
        if (app == null) {
            app = FirebaseApp.getInstance();
        }

        if (callRef != null && callRef.getKey() != null && callRef.getKey().equals(callId) && offerListener != null) {
            return;
        }

        stopListeningForOffer();
        callRef = FirebaseDatabase.getInstance(app).getReference("calls").child(callId);
        offerListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                String type = snapshot.child("type").getValue(String.class);
                String sdp = snapshot.child("sdp").getValue(String.class);
                if (!"offer".equalsIgnoreCase(type) || TextUtils.isEmpty(sdp)) return;
                if (sdp.equals(lastSeenOfferSdp)) return;

                lastSeenOfferSdp = sdp;
                Log.d(TAG, "Offer detected for callId=" + listeningCallId);
                updateNotification("Offer received for callId: " + listeningCallId + ". Tap to join.");
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.w(TAG, "Offer listener cancelled: " + error.getMessage());
                updateNotification("Offer listener error: " + error.getMessage());
            }
        };
        callRef.child("offer").addValueEventListener(offerListener);
    }

    private void stopListeningForOffer() {
        if (callRef != null && offerListener != null) {
            callRef.child("offer").removeEventListener(offerListener);
        }
        offerListener = null;
        callRef = null;
        lastSeenOfferSdp = null;
    }
}
