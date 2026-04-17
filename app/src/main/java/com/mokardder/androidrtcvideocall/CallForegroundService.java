package com.mokardder.androidrtcvideocall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

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
    private boolean isForegroundStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannelIfNeeded();
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

        if (!ensureForegroundStarted("Waiting for call / streaming in background")) {
            Log.e(TAG, "Failed to start foreground mode; stopping service to avoid crash loop.");
            stopSelf();
            return START_NOT_STICKY;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartServiceIntent);
        } else {
            startService(restartServiceIntent);
        }
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

    private boolean ensureForegroundStarted(String contentText) {
        if (isForegroundStarted) return true;
        Notification notification = buildNotification(contentText);
        int serviceType = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType);
            isForegroundStarted = true;
            return true;
        } catch (IllegalArgumentException typeMismatch) {
            Log.w(TAG, "FGS type mismatch; retrying startForeground without explicit type.", typeMismatch);
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0);
                isForegroundStarted = true;
                return true;
            } catch (RuntimeException fallbackError) {
                Log.e(TAG, "Fallback foreground start failed.", fallbackError);
                return false;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Foreground start failed.", e);
            return false;
        }
    }

    private void startListeningForOffer(String callId) {
        if (callRef != null && callRef.getKey() != null && callRef.getKey().equals(callId) && offerListener != null) {
            Log.d(TAG, "Already listening on callId=" + callId);
            return;
        }

        stopListeningForOffer();
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this);
        }
        callRef = FirebaseDatabase.getInstance().getReference("calls").child(callId);
        Log.d(TAG, "Started listening for offer on calls/" + callId + "/offer");
        offerListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Log.d(TAG, "No offer currently for callId=" + listeningCallId);
                    return;
                }

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
            Log.d(TAG, "Stopped listening for offer on callId=" + listeningCallId);
        }
        offerListener = null;
        callRef = null;
        lastSeenOfferSdp = null;
    }
}
