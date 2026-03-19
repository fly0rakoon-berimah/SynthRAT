private boolean checkAllPermissions() {
    // Check camera permission (this IS a runtime permission)
    boolean hasCameraPermission = ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    
    Log.d(TAG, "Camera permission granted: " + hasCameraPermission);
    
    if (!hasCameraPermission) {
        Log.e(TAG, "Camera permission not granted");
        return false;
    }
    
    // FOREGROUND_SERVICE_CAMERA is NOT a runtime permission on Android 10+
    // It just needs to be in the manifest and the service must declare the type
    // So we don't check it at runtime - it's automatically considered granted
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Log.d(TAG, "FOREGROUND_SERVICE_CAMERA is a manifest permission - considered granted if in manifest");
        // Just log that it should be in manifest
        Log.d(TAG, "Make sure AndroidManifest.xml has: android:foregroundServiceType=\"camera\"");
    }
    
    return true;
}
