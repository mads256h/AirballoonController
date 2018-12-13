package com.aatg.elev.bluetoothdebugger;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.design.widget.BottomNavigationView;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.aatg.elev.bluetoothdebugger.SelectDeviceActivity.EXTRA_MESSAGE;

public class ControlDeviceActivity extends AppCompatActivity implements IBluetoothController, IPacketHandler {


    private BluetoothDevice device;
    private BluetoothSocket socket;
    private OutputStream outputStream;

    private LinearLayout masterLayout;

    private Activity activity;

    private BottomNavigationView navigation;

    private AddFragment addFragment = new AddFragment();
    private ControlFragment controlFragment = new ControlFragment();
    private DebugViewFragment debugViewFragment = new DebugViewFragment();

    private FragmentManager fragmentManager;

    private InputThread inputThread;

    private List<IPacketHookListener> packetHookListeners = new ArrayList<>();

    private boolean ignoreErrors = false;

    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");


    private BottomNavigationView.OnNavigationItemSelectedListener mOnNavigationItemSelectedListener
            = new BottomNavigationView.OnNavigationItemSelectedListener() {

        @Override
        public boolean onNavigationItemSelected(@NonNull MenuItem item) {
            switch (item.getItemId()) {
                case R.id.navigation_add:
                    showAddFragment();
                    return true;


                case R.id.navigation_control:
                    showControlFragment();
                    return true;


                case R.id.navigation_debug:
                    showDebugFragment();
                    return true;
            }
            return false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control_device);

        navigation = (BottomNavigationView) findViewById(R.id.navigation);
        navigation.setSelectedItemId(R.id.navigation_control);
        navigation.setOnNavigationItemSelectedListener(mOnNavigationItemSelectedListener);

        masterLayout = findViewById(R.id.master_layout);

        fragmentManager = getSupportFragmentManager();
        fragmentManager.beginTransaction().add(masterLayout.getId(), controlFragment).commit();

        Intent intent = getIntent();
        device = intent.getParcelableExtra(EXTRA_MESSAGE);

        activity = this;

        if (device == null) return;

        connect();

        try {
            inputThread = new InputThread(socket, this);
        } catch (IOException e) {
            showError("Could not create Input Thread", false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (device == null) return;

        inputThread.keepRunning = false;

        try {
            inputThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            outputStream.close();
            socket.close();
        }
        catch (IOException e)
        {
            showError("Could not close output stream and socket", false);
        }
    }

    private void showAddFragment(){
        fragmentManager.beginTransaction().replace(masterLayout.getId(), addFragment).commit();

    }

    private void showControlFragment(){
        fragmentManager.beginTransaction().replace(masterLayout.getId(), controlFragment).commit();
    }

    private void showDebugFragment(){
        fragmentManager.beginTransaction().replace(masterLayout.getId(), debugViewFragment).commit();
    }


    private void connect()
    {
        if (device == null) return;

        try {
            socket = device.createRfcommSocketToServiceRecord(MY_UUID);
            outputStream = socket.getOutputStream();
        }
        catch (IOException e)
        {
            showError("Could not create socket or output stream.", false);
        }
    }

    private AlertDialog showError(String message) {
        return showError(message, true);
    }

    private AlertDialog showError(String message, final boolean ignorable){

        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);

        alertBuilder.setTitle(R.string.error)
                .setMessage(message)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!ignorable) finish();
                    }
                });

        if (ignorable)
        {
            if (!ignoreErrors) {

                alertBuilder.setNegativeButton(R.string.ignore_future_errors, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ignoreErrors = true;
                    }
                });
            }
        }

        return alertBuilder.show();
    }

    @Override
    public void handlePacket(BluetoothPacket packet)
    {
        for (IPacketHookListener listener :
                packetHookListeners) {
            listener.getHookedPacket(packet);
        }

        if (packet.id == 2){

            showError("Air balloon reported error: " + BluetoothPacket.ErrorIdToString((int)packet.data));

            return;
        }

        for (IControlFragment fragment :
                controlFragment.controlFragments) {
            if (fragment.getPacketId() == packet.id)
            {
                fragment.handlePacket(packet);
            }
        }

    }

    @Override
    public BluetoothDevice getDevice() {
        return device;
    }

    @Override
    public BluetoothSocket getSocket() {
        return socket;
    }

    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public void setPacketHook(IPacketHookListener listener) {
        packetHookListeners.add(listener);
    }

    @Override
    public void removePacketHook(IPacketHookListener listener) {
        packetHookListeners.remove(listener);
    }

    private class InputThread extends Thread
    {
        public volatile boolean keepRunning = true;

        private InputStream stream;
        private IPacketHandler packetHandler;

        public InputThread(BluetoothSocket socket, IPacketHandler packetHandler) throws IOException {
            stream = socket.getInputStream();
            this.packetHandler = packetHandler;
        }

        @Override
        public void run(){
            Looper.prepare();

            while (keepRunning)
            {
                try {
                    if(stream.available() >= BluetoothPacket.packetLength){
                        final BluetoothPacket packet = BluetoothPacket.readPacket(stream);

                        if (packet == null) continue;

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                packetHandler.handlePacket(packet);
                            }
                        });
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            close();
        }

        private void close(){
            try {
                stream.close();
            } catch (IOException ignored) { }

            packetHandler = null;
        }
    }
}
