/*
    CODE CREDIT:
    - Based on https://www.youtube.com/watch?v=zCYQBIcePaw
*/

package com.example.littyling.beatsbyneigh;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    // Logcat tag
    static final String TAG = "Main";

    // bluetooth
    BluetoothAdapter btAdapter;
    Set<BluetoothDevice> bondedDevices;
    BluetoothDevice btDevice;
    final String DEVICE_NAME = "HC-06";
    final UUID PORT_UUID = UUID.fromString("0001101-0000-1000-8000-00805F9B34FB");
    BluetoothSocket btSocket;
    static OutputStream btOS;

    // GUI elements
    Button playBtn;
    SeekBar positionBar;
    SeekBar volumeBar;
    SeekBar latencyBar;
    TextView elapsedTimeLabel;
    TextView remainingTimeLabel;
    TextView latencyLabel;

    // media players
    MediaPlayer redboneMP;
    int totalTime;

    // servo data
    InputStream servoDataIS;
    BufferedReader servoDataBR;
    int servoDataSet;
    ArrayList<String> servoData = new ArrayList<String>();
    int[] tsData;
    int[] servoValData;
    int servoDataIndex;
    int latency = 270;

    // setup bluetooth
    private void startBluetooth() {
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        bondedDevices = btAdapter.getBondedDevices();

        if(!btAdapter.isEnabled()) {
            Intent enableAdapter = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableAdapter, 0);
        }

        if (bondedDevices.isEmpty()) {
            Toast.makeText(getApplicationContext(), "Pair the device...", Toast.LENGTH_SHORT).show();
        } else {
            for (BluetoothDevice bondedDevice : bondedDevices) {
                if (bondedDevice.getName().equals(DEVICE_NAME)) {
                    btDevice = bondedDevice;
                    break;
                }
            }
        }

        try {
            btSocket = btDevice.createRfcommSocketToServiceRecord(PORT_UUID);
            btSocket.connect();
            btOS = btSocket.getOutputStream();
            Log.d(TAG, "Bluetooth setup successfully...");
        } catch (Exception e) {
            Log.e(TAG, "Bluetooth setup failed...\n" + e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // setup bluetooth
        startBluetooth();
        btSendMsg("init", "0 0", true);

        // setup media player
        redboneMP = MediaPlayer.create(this, R.raw.redbone);
        redboneMP.setLooping(true);
        redboneMP.seekTo(0);
        redboneMP.setVolume(0.5f, 0.5f);
        totalTime = redboneMP.getDuration();

        Log.d(TAG, "Media players setup...");

        // setup GUI elements
        playBtn = (Button) findViewById(R.id.playBtn);

        elapsedTimeLabel = (TextView) findViewById(R.id.elapsedTimeLabel);
        remainingTimeLabel = (TextView) findViewById(R.id.remainingTimeLabel);
        latencyLabel = (TextView) findViewById(R.id.latencyLabel);

        positionBar = (SeekBar) findViewById(R.id.positionBar);
        positionBar.setMax(totalTime);
        positionBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekbar, int progress, boolean fromUser) {
                        if (fromUser) {
                            redboneMP.seekTo(progress);
                            positionBar.setProgress(progress);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {

                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekbar) {
                        int searchIndex = binarySearch(tsData, redboneMP.getCurrentPosition() + latency);
                        btSendMsg("skip", "" + redboneMP.getCurrentPosition(), true);

                        if (searchIndex < tsData.length) {
                            servoDataIndex = searchIndex;
                            Log.d(TAG, "Music Time: " + redboneMP.getCurrentPosition() + " TS Index: " + servoDataIndex + " TS Value: " + tsData[servoDataIndex]);
                        }
                    }
                }
        );

        volumeBar = (SeekBar) findViewById(R.id.volumeBar);
        volumeBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekbar, int progress, boolean fromUser) {
                        float volume = progress/100f;
                        redboneMP.setVolume(volume, volume);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {

                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {

                    }
                }
        );

        latencyBar = (SeekBar) findViewById(R.id.latencyBar);
        latencyBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekbar, int progress, boolean fromUser) {
                        if (fromUser) {
                            latency = progress;
                            latencyLabel.setText("Latency: " + progress);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {

                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {

                    }
                }
        );

        // thread to update position bar and time label
        new Thread(new Runnable() {
           @Override
           public void run() {
               while (redboneMP != null) {
                   try {
                       Message msg = new Message();
                       msg.what = redboneMP.getCurrentPosition();
                       handler.sendMessage(msg);
                       Thread.sleep(1000);
                   } catch (InterruptedException e) {
                       Log.e(TAG, "Error: " + e);
                   }
               }
           }
        }).start();

        Log.d(TAG, "GUI setup...");

        // servo data file parser
        servoDataSet = 1;
        servoDataIS = getResources().openRawResource(R.raw.rbdataone);
        servoDataBR = new BufferedReader(new InputStreamReader(servoDataIS));
        String line;

        try {
            int tsIndex = 0;

            line = servoDataBR.readLine();

            while (line != null) {
                servoData.add(line);

                line = servoDataBR.readLine();
            }

            tsData = new int[servoData.size()];
            servoValData = new int[servoData.size()];

            for (int i = 0; i < servoData.size(); i++) {
                String[] data = servoData.get(i).split(" ");
                tsData[i] = Integer.parseInt(data[0]);
                servoValData[i] = Integer.parseInt(data[1]);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error: " + e);
        }

        // thread to send servo data through bluetooth
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (btOS != null) {
                    try {
                        if (redboneMP != null && redboneMP.isPlaying()) {
                            if (redboneMP.getCurrentPosition() > 220000 && servoDataSet == 1 && latency != 70) {
                                latency = 70;
                            }

                            int servoTS = tsData[servoDataIndex];
                            int servoVal = servoValData[servoDataIndex];

                            if (redboneMP.getCurrentPosition() + latency >= servoTS) {
                                btSendMsg("servo-move", servoTS + " " + servoVal, false);

                                if (servoDataIndex < tsData.length - 1)
                                    servoDataIndex++;
                            }
                        }

                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Error: " + e);
                    }
                }
            }
        }).start();

        // log initiate
        Log.d(TAG, "Initiate " + TAG + "...");
    }

    // modded binary search implementation for finding servo time stamp upon media player skip
    public int binarySearch(int[] arr, int key) {
        double m = 158.736;
        double b = 1280.958;

        if (servoDataSet == 1) {
            m = 150.287;
            b = 315.274;
        } else if (servoDataSet == 2) {
            m = 151.173;
            b = 68.239;
        } else if (servoDataSet == 3) {
            m = 152.321;
            b = -305.080;
        }

        int predictIndex = (int) ((key - b)/m);
        int tolerance = 100;
        int left = predictIndex - tolerance;
        int right = predictIndex + tolerance;

        while (left <= right) {
            int mid = left + (right - left)/2;

            if (key == arr[mid])
                return mid;

            if (key < arr[mid])
                right = mid - 1;

            if (key > arr[mid])
                left = mid + 1;
        }

        return right;
    }

    // thread handler for music time update
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            int currentPosition = msg.what;
            positionBar.setProgress(currentPosition);

            String elapsedTime = createTimeLabel(currentPosition);
            elapsedTimeLabel.setText(elapsedTime);

            String remainingTime = createTimeLabel(totalTime - currentPosition);
            remainingTimeLabel.setText(remainingTime);
        }
    };

    public String createTimeLabel(int time) {
        String timeLabel = "";
        int min = time/1000/60;
        int sec = time/1000 % 60;

        timeLabel = min + ":";

        if (sec < 10)
            timeLabel += "0";

        timeLabel += sec;

        return timeLabel;
    }

    // handle pause and play of media
    public void playBtnClick(View view) {
        if (!redboneMP.isPlaying()) {
            redboneMP.start();
            playBtn.setBackgroundResource(R.drawable.pause);
            btSendMsg("play-pause", "play", true);
        } else {
            redboneMP.pause();
            playBtn.setBackgroundResource(R.drawable.play);
            btSendMsg("play-pause", "pause", true);
        }
    }

    // write bluetooth message
    public void btSendMsg(String id, String msg, boolean log) {
        if (btOS == null)
            return;

        String finalMsg = "<" + id + " " + msg + ">";
        try {
            btOS.write(finalMsg.getBytes());

            if (log)
                Log.d(TAG, finalMsg);
        } catch (Exception e) {
            Log.e(TAG, "Error writing to bluetooth output stream...\n" + e);
        }
    }
}
