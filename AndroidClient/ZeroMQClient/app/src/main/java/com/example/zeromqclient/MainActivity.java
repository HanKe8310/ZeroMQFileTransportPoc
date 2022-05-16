package com.example.zeromqclient;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;

import androidx.core.app.ActivityCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.example.zeromqclient.databinding.ActivityMainBinding;

import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;
import org.zeromq.ZThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

public class MainActivity extends AppCompatActivity {
    public static final String TAG = "ZeroMQ";
    private static final String START_MSG = "fetch";
    private static final String SYNC_MSG = "sync";
    private static final int CHUNK_SIZE = 250000;
    private static final int CHUNK_COUNT = 4;


    private Button recv;
    private Button send;
    private InputStream sharedFileStream = null;
    private String sharedFileName = null;
    private long sharedFileSize = 0;
    private byte[] buffer = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.e(TAG, "ON CREATE");
        recv = findViewById(R.id.recv);
        send = findViewById(R.id.send);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        ZContext zContext = new ZContext();

        Intent  intent = getIntent();
        if (intent != null && intent.getAction().equals(Intent.ACTION_SEND))  {
            Log.e(TAG, intent.toString());
            Uri returnUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            Cursor returnCursor =
                    getContentResolver().query(returnUri, null, null, null, null);
            /*
             * Get the column indexes of the data in the Cursor,
             * move to the first row in the Cursor, get the data,
             * and display it.
             */
            int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
            returnCursor.moveToFirst();
            sharedFileName = returnCursor.getString(nameIndex);
            sharedFileSize = returnCursor.getLong(sizeIndex);
            buffer = new byte[CHUNK_SIZE];
            Log.e(TAG, " filename: " + sharedFileName + " size: " + sharedFileSize);

            try {
                sharedFileStream = getContentResolver().openInputStream(returnUri);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }

        final long[] startTime = new long[1];
        final FileHandler fileHandler = new FileHandler(this);
        ZThread.IAttachedRunnable recvRunnable = (args, ctx, pipe) -> {
            ZMQ.Socket socket = zContext.createSocket(SocketType.DEALER);
            Log.d(TAG, "start socket");
            String id;
            if (args.length == 0) {
                id = "id";
            } else {
                Log.d(TAG, "Ideneity args: " + args[0]);
                id = String.valueOf(args[0]);
            }
            socket.setIdentity(id.getBytes(StandardCharsets.UTF_8));
            boolean result = socket.connect("tcp://192.168.31.34:5556");

            socket.send(SYNC_MSG);
            ZMsg sync = ZMsg.recvMsg(socket);

            String filename = sync.popString();
            int filesize = Integer.parseInt(sync.popString());
            Log.e(MainActivity.TAG, filename + " : " + filesize + " bytes");
            fileHandler.createFile(filename,filesize);

            startTime[0] = System.currentTimeMillis();

            int total = 0;
            int chunks = 0;
            boolean shouldEnd = false;
            while (true) {
                socket.sendMore(START_MSG);
                socket.sendMore(String.valueOf(CHUNK_SIZE));
                socket.send(String.valueOf(CHUNK_COUNT));
                int count = CHUNK_COUNT;

                while (count > 0) {
                    ZMsg msg = ZMsg.recvMsg(socket);
                    count--;
                    ZFrame chunk = msg.pop();
                    if (chunk == null) {
                        break;
                    }
                    chunks++;
                    int length = chunk.size();
                    byte[] data = chunk.getData();
                    String indexMsg = msg.popString();
                    String realSizeMsg = msg.popString();
                    int index= Integer.parseInt(indexMsg);
                    int realSize= Integer.parseInt(realSizeMsg);
                    Log.e(TAG, id +  " write to file: " + index + " " + realSize);
                    fileHandler.writeFile(data, length, index, realSize);
                    total += length;
                    if (msg.peek() != null) {
                        shouldEnd = true;
                        break;
                    }
                    msg.destroy();
                }
                if (shouldEnd) {
                    break;
                }
            }
            float size = (float)total / 1024f / 1024f;
            float time = ((float) (System.currentTimeMillis() - startTime[0])) / 1000f;
            Log.e(TAG, id +  " :" + chunks + " chunks recvs, total size is: " + total + " use: " + time + "s, average speed: " + (size/ time) + " Mb/s");
            //send fetch end msg
            pipe.send("OK");
        };

        ZThread.IAttachedRunnable sendRunnable = (args, ctx, pipe) -> {

            ZMQ.Socket socket = zContext.createSocket(SocketType.ROUTER);
            Log.d(TAG, "start server socket");
            boolean result = socket.bind("tcp://192.168.31.23:5556");
            ZMsg msg = ZMsg.recvMsg(socket);
            //[identity]["sync"]
            String identity = msg.popString();
            String commend = msg.popString();
            if (SYNC_MSG.equals(commend)) {
                ZMsg sync = new ZMsg();
                sync.append(identity);
                sync.append(sharedFileName);
                sync.append(Integer.toString((int) sharedFileSize));
                sync.send(socket);
            }
            boolean shouldEnd = false;
            while (true) {
                ZMsg datamsg = ZMsg.recvMsg(socket);
                //[identity]["fetch"][chunk_size][chunk_count]
                String dataidentity = datamsg.popString();
                String datacommand = datamsg.popString();
                if (!START_MSG.equals(datacommand)) {
                    break;
                }
                Log.e(TAG, "start fetch");
                int chunkSize = Integer.parseInt(datamsg.popString());
                int chunkcount = Integer.parseInt(datamsg.popString());
                Log.e(MainActivity.TAG, "chunk count: " + chunkcount + " size: " + chunkSize);
                try {
                    int readcount, index = 0;
                    while (chunkcount > 0) {
                        readcount = sharedFileStream.read(buffer);
                        if (readcount > 0) {
                            ZMsg data = new ZMsg();
                            data.append(identity);
                            data.append(buffer);
                            data.append(Integer.toString(index));
                            data.append(Integer.toString(readcount));
                            data.send(socket);
                        } else {
                            ZMsg data = new ZMsg();
                            data.append(identity);
                            data.append(buffer);
                            data.append(Integer.toString(index));
                            data.append(Integer.toString(readcount));
                            data.append("end");
                            data.send(socket);
                            shouldEnd = true;
                        }
                        index++;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                }
                if (shouldEnd) {
                    break;
                }
            }

        };
        recv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ZMQ.Socket pipe1 = ZThread.fork(zContext, recvRunnable, new Object[]{"client"});
                ZMQ.Socket pipe2 = ZThread.fork(zContext, recvRunnable, new Object[]{"client2"});
                String result1 = pipe1.recvStr();
                String result2 = pipe2.recvStr();
                Log.e(MainActivity.TAG, result1 + " " + result2);
                //todo add logic for missing chunks
            }
        });

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ZThread.fork(zContext, sendRunnable, new Object[]{"SERVER"});
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.e(TAG, "ON NEW INTENT");
        if (intent != null && intent.getAction().equals(Intent.ACTION_SEND))  {
            Log.e(TAG, intent.toString());
            Uri returnUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            Cursor returnCursor =
                    getContentResolver().query(returnUri, null, null, null, null);
            /*
             * Get the column indexes of the data in the Cursor,
             * move to the first row in the Cursor, get the data,
             * and display it.
             */
            int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
            returnCursor.moveToFirst();
            sharedFileName = returnCursor.getString(nameIndex);
            sharedFileSize = returnCursor.getLong(sizeIndex);
            buffer = new byte[CHUNK_SIZE];
            Log.e(TAG, " filename: " + sharedFileName + " size: " + sharedFileSize);

            try {
                sharedFileStream = getContentResolver().openInputStream(returnUri);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }
}