package com.example.zeromqclient;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;

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

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;
import org.zeromq.ZThread;

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


    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        ZContext zContext = new ZContext();

        final long[] startTime = new long[1];
        final FileHandler fileHandler = new FileHandler(this);
        ZThread.IAttachedRunnable attachedRunnable = new ZThread.IAttachedRunnable() {
            @Override
            public void run(Object[] args, ZContext ctx, ZMQ.Socket pipe) {
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
                            shouldEnd = fileHandler.isFileSizeMatchExpect();
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
                pipe.send("OK");
            }
        };

        binding.fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                ZThread.fork(zContext, attachedRunnable, new Object[]{"client"});
            }
        });
    }
}