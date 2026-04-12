package com.mokardder.androidrtcvideocall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
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
    private static final String CHANNEL_ID = "webrtc_call_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final String TAG = "CallForegroundService";
    private static final String CALL_ID = "demo-call-001";
    private DatabaseReference callRef;
    private ValueEventListener offerListener;

    @Override
    public void onCreate() {
        super.onCreate();
//        createChannelIfNeeded();
//        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
//                .setContentTitle("Video call running")
//                .setContentText("Waiting for call / streaming in background")
//                .setSmallIcon(android.R.drawable.presence_video_online)
//                .setOngoing(true)
//                .build();
//        startForeground(NOTIFICATION_ID, notification);
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        createChannelIfNeeded();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Video call running")
                .setContentText("Waiting for call / streaming in background")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
        startOfferListener();

        return START_STICKY;
    }
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), CallForegroundService.class);
        restartServiceIntent.setPackage(getPackageName());
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
        stopOfferListener();
        super.onDestroy();
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

    private void startOfferListener() {
        if (offerListener != null) return;

        FirebaseApp firebaseApp = FirebaseApp.initializeApp(this);
        if (firebaseApp == null) {
            Log.w(TAG, "Firebase init failed in service");
            return;
        }

        callRef = FirebaseDatabase.getInstance(firebaseApp).getReference("calls").child(CALL_ID);
        offerListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                if (MainActivity.isActivityVisible()) return;

                String type = snapshot.child("type").getValue(String.class);
                String sdp = snapshot.child("sdp").getValue(String.class);
                if (type == null || sdp == null) return;
                if (!"offer".equalsIgnoreCase(type)) return;

                Intent openApp = new Intent(CallForegroundService.this, MainActivity.class);
                openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(openApp);
                Log.d(TAG, "Offer received while app backgrounded. Bringing activity to foreground.");
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Offer listener cancelled: " + error.getMessage());
            }
        };
        callRef.child("offer").addValueEventListener(offerListener);
    }

    private void stopOfferListener() {
        if (callRef != null && offerListener != null) {
            callRef.child("offer").removeEventListener(offerListener);
        }
        offerListener = null;
    }
}
