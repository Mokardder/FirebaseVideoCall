package com.mokardder.androidrtcvideocall;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.google.firebase.messaging.FirebaseMessaging;

public class FcmCallService extends FirebaseMessagingService {
    private static final String TAG = "FcmCallService";
    private static final String ACTION_START_CALL = "start_call";
    private static final String CHANNEL_ID = "incoming_call";
    private static final int NOTIFICATION_ID = 2002;

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.d(TAG, "FCM token refreshed: " + token);
        FirebaseMessaging.getInstance().subscribeToTopic(MainActivity.FCM_CALL_TOPIC)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Subscribed to topic on token refresh: " + MainActivity.FCM_CALL_TOPIC);
                    } else {
                        Log.w(TAG, "Topic subscribe failed on token refresh", task.getException());
                    }
                });
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String action = remoteMessage.getData().get("action");
        String callId = remoteMessage.getData().get("callId");

        if (ACTION_START_CALL.equals(action)) {
            if (TextUtils.isEmpty(callId)) {
                Log.w(TAG, "start_call received without callId. Service will use default call path.");
            }
            startCallForegroundService(callId);
            showIncomingCallNotification(callId);
            Log.d(TAG, "Incoming call push handled for callId=" + callId);
        } else {
            Log.d(TAG, "Received non-call FCM data message: " + remoteMessage.getData());
        }
    }

    private void startCallForegroundService(String callId) {
        Intent serviceIntent = new Intent(this, CallForegroundService.class);
        serviceIntent.putExtra(CallForegroundService.EXTRA_LISTEN_CALL_ID, callId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void showIncomingCallNotification(String callId) {
        createChannelIfNeeded();

        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        contentIntent.putExtra(MainActivity.EXTRA_START_FROM_FCM, true);
        contentIntent.putExtra(MainActivity.EXTRA_CALL_ID, callId);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Incoming video call")
                .setContentText(callId == null || callId.isEmpty() ? "Tap to open app" : "Call ID: " + callId)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent);

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build());
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Incoming call",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Notifies about incoming FCM-triggered calls");

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
