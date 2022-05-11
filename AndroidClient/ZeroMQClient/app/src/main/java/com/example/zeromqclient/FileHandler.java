package com.example.zeromqclient;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileHandler {

    private Context context;
    private File currentFile;
    private FileOutputStream currentFileStream;
    private int totalSize;
    private int currentSize;
    //working thread
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    public FileHandler(Context context) {
        this.context = context;
    }

    public void createFile(String filename, int totalSize) {
        executor.submit(() -> {
            try {
                Log.e(MainActivity.TAG, "create file");
                File fs = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), filename);
                Log.e(MainActivity.TAG, fs.getAbsolutePath());
                currentFile = fs;
                currentFileStream = new FileOutputStream(currentFile);
                this.totalSize = totalSize;
                this.currentSize = 0;
            } catch (FileNotFoundException e) {
                Log.e(MainActivity.TAG, "file create failed: "+ e);
            }
        });
    }

    public void writeFile(byte[] bytes) {
        if (currentFileStream == null) {
            throw new RuntimeException("file not created");
        }
        executor.submit(() -> {
            try {
                int length = bytes.length;
                if (currentSize + length > totalSize) {
                    currentFileStream.write(bytes, 0, totalSize - currentSize);
                    currentSize = totalSize;
                    currentFileStream.flush();
                    currentFileStream.close();
                    Log.e(MainActivity.TAG, "write file complete: " + currentSize);
                } else {
                    currentFileStream.write(bytes);
                    currentSize += bytes.length;
                }
            } catch (IOException e) {
                Log.e(MainActivity.TAG, "write file chunk failed");
            }
        });
    }
}
