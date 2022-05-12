package com.example.zeromqclient;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileHandler {

    private Context context;
    private File currentFile;
    private FileOutputStream currentFileStream;
    private RandomAccessFile randomAccessFile;
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
                randomAccessFile = new RandomAccessFile(currentFile, "rwd");
                this.totalSize = totalSize;
                this.currentSize = 0;
            } catch (FileNotFoundException e) {
                Log.e(MainActivity.TAG, "file create failed: "+ e);
            }
        });
    }

    public void writeFile(byte[] bytes, int chunkSize, int index, int realSize) {
        if (randomAccessFile == null) {
            throw new RuntimeException("file not created");
        }
        if (index == -1) {
            return;
        }
        executor.submit(() -> {
            try {
                long writePos = (long) index * chunkSize;
                randomAccessFile.seek(writePos);
                if (realSize < chunkSize) {
                    randomAccessFile.write(bytes, 0, realSize);
                    currentSize += realSize;
                } else {
                    randomAccessFile.write(bytes);
                    currentSize += chunkSize;
                }
                Log.e(MainActivity.TAG, "write " + realSize + " bytes from " + writePos);
            } catch (IOException e) {
                Log.e(MainActivity.TAG, "write file chunk failed: " + e);
            }
        });
    }

    public boolean isFileSizeMatchExpect() {
        return currentSize == totalSize;
    }

    public void closeFile() {
        try {
            randomAccessFile.close();
            Log.e(MainActivity.TAG, "write file complete");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}
