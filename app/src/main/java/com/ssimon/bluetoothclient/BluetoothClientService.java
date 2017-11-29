/*
 Copyright (c) 2017 Steven H. Simon
 Licensed under the Apache License, Version 2.0 (the "License"); you may not
 use this file except in compliance with the License. You may obtain	a copy
 of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required
 by applicable law or agreed to in writing, software distributed under the
 License is distributed on an "AS IS" BASIS,	WITHOUT	WARRANTIES OR CONDITIONS
 OF ANY KIND, either express or implied. See the License for the specific
 language governing permissions and limitations under the License.
*/

 package com.ssimon.bluetoothclient;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class BluetoothClientService extends Service {
    static final String ACTION_START = "ACTION_START";
    static final String ACTION_WRITE = "ACTION_WRITE";
    static final String ACTION_STOP = "ACTION_STOP";
    static final String EXTRA_MESSAGE = "EXTRA_MESSAGE";

    enum Status { UNSTARTED, CONNECTING, CONNECTED, STOPPED };
    enum Error { UNSUPPORTED, DISABLED, EXCEPTION, WRITE_FAILURE };

    static final private Object LOCK = new Object();
    static final private BluetoothAdapter ADAPTER = BluetoothAdapter.getDefaultAdapter();
    static final private String TAG = "BluetoothClientService";

    static private ConnectThread sConnectThread = null;
    static private ConnectedThread sConnectedThread = null;

    @Override
    public void onCreate() {
        super.onCreate();
        postStatus(Status.UNSTARTED);
    }

    @Override
    public int onStartCommand(Intent intent, int unused1, int unused2) {
        notNull(intent);
        String action = intent.getAction();
        notNullOrEmpty(action);
        switch (action) {
            case ACTION_START:
                start();
                break;
            case ACTION_WRITE:
                write(intent);
                break;
            case ACTION_STOP:
                stopSelf();
                break;
            default:
                throw new IllegalArgumentException("unknown action: " + action);
        }
        return START_NOT_STICKY;
    }

    private void start() {
        synchronized (LOCK) {
            if (sConnectThread != null || sConnectedThread != null)
                return;
        }
        if (ADAPTER == null) {
            postError(Error.UNSUPPORTED);
            stopSelf();
        } else {
            sConnectThread = new ConnectThread(ADAPTER);
            sConnectThread.start();
        }
    }

    private void write(Intent i) {
        notNull(i);
        String msg = i.getStringExtra(EXTRA_MESSAGE);
        notNullOrEmpty(msg);
        synchronized (LOCK) {
            if (sConnectedThread != null) {
                sConnectedThread.write(msg);
            } else {
                postError(Error.WRITE_FAILURE, msg);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        synchronized (LOCK) {
            if (sConnectThread != null)
                sConnectThread.cancel();
            if (sConnectedThread != null)
                sConnectedThread.cancel();
            postStatus(Status.STOPPED);
        }
        super.onDestroy();
    }

    static private class ConnectThread extends BackgroundThread {
        final private BluetoothAdapter mmAdapter;
        private volatile boolean mmIsCancelled = false;

        ConnectThread(BluetoothAdapter a) {
            notNull(a);
            mmAdapter = a;
            postStatus(Status.CONNECTING);
        }

        @Override
        public void run() {
            super.run();
            while (!mmIsCancelled) {
                if (!mmAdapter.isEnabled())
                    postError(Error.DISABLED);
                else if (connect())
                    return;
                final long sleepMillis = 500;
                SystemClock.sleep(sleepMillis);
            }
            synchronized (LOCK) {
                sConnectThread = null;
            }
        }

        private boolean connect() {
            String address = getServerAddress();
            validateBluetoothAddress(address);
            BluetoothDevice dev = mmAdapter.getRemoteDevice(address);
            UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

            BluetoothSocket sock = null;
            InputStream is = null;
            OutputStream os = null;
            try {
                sock = dev.createRfcommSocketToServiceRecord(uuid);
                mmAdapter.cancelDiscovery();
                sock.connect();
                is = sock.getInputStream();
                os = sock.getOutputStream();
                synchronized (LOCK) {
                    if (mmIsCancelled)
                        throw new IOException("cancelled");
                    sConnectThread = null;
                    WriteThread wt = new WriteThread(os, sock);
                    sConnectedThread = new ConnectedThread(is, sock, wt);
                    sConnectedThread.start();
                }
                return true;
            } catch (IOException e) {
                if (sock != null) close(sock);
                if (is != null) close(is);
                if (os != null) close(os);
                return false;
            }
        }

        void cancel() {
            synchronized (LOCK) {
                mmIsCancelled = true;
            }
        }
    }

    static private class ConnectedThread extends BackgroundThread {
        final private InputStream mmInputStream;
        final private BluetoothSocket mmSocket;
        final private WriteThread mmWriteThread;
        private boolean mmIsCanceled = false;

        ConnectedThread(InputStream is, BluetoothSocket s, WriteThread ws) {
            notNull(is);
            notNull(s);
            notNull(ws);
            mmInputStream = is;
            mmSocket = s;
            mmWriteThread = ws;
        }

        @Override
        public void run() {
            super.run();
            mmWriteThread.start();
            postStatus(Status.CONNECTED);
            try {
                final int messageLength = 6;
                final int bufsize = 1;
                for (;;) {
                    StringBuilder sb = new StringBuilder();
                    while (sb.length() < messageLength) {
                        byte[] buf = new byte[bufsize];
                        int nbytes = mmInputStream.read(buf);
                        sb.append(new String(buf, 0, nbytes));
                    }
                    postReadMessage(sb.toString());
                }
            } catch (IOException e) {
                postError(Error.EXCEPTION, e.getMessage());
                reconnect();
            } finally {
                mmWriteThread.cancel();
                close(mmSocket);
                close(mmInputStream);
            }
        }

        private void reconnect() {
            synchronized (LOCK) {
                sConnectedThread = null;
                if (mmIsCanceled)
                    return;
                sConnectThread = new ConnectThread(ADAPTER);
                sConnectThread.start();
            }
        }


        void write(String msg) {
            mmWriteThread.write(msg);
        }

        void cancel() {
            synchronized (LOCK) {
                mmIsCanceled = true;
                close(mmSocket);
            }
         }
    }

    static private class WriteThread extends BackgroundThread {
        static final private String TERMINATE_WRITETHREAD = "TERMINATE_WRITETHREAD";
        final private BlockingQueue<String> mmQueue = new LinkedBlockingQueue<>();
        final private OutputStream mmOutputStream;
        final private BluetoothSocket mmSocket;

        WriteThread(OutputStream os, BluetoothSocket s) {
            notNull(os);
            notNull(s);
            mmOutputStream = os;
            mmSocket = s;
        }

        @Override
        public void run() {
            super.run();
            try {
                for (;;) {
                    String msg = mmQueue.take();
                    if (msg.equals(TERMINATE_WRITETHREAD))
                        throw new InterruptedException("terminated");
                    mmOutputStream.write(msg.getBytes());
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                postError(Error.EXCEPTION, e.getMessage());
                close(mmSocket);
            } finally {
                close(mmOutputStream);
            }
        }

        void write(String msg) {
            notNullOrEmpty(msg);
            if (!mmQueue.offer(msg))
                throw new IllegalStateException("Queue unexpectedly full");
        }

        void cancel() {
            write(TERMINATE_WRITETHREAD);
        }
    }
    
  static private void close(BluetoothSocket s) {
        notNull(s);
        try {
            s.close();
        } catch (IOException ignored) {}
    }

    static private void close(InputStream is) {
        notNull(is);
        try {
            is.close();
        } catch (IOException ignored) {}
    }

    static private void close(OutputStream os) {
        notNull(os);
        try {
            os.close();
        } catch (IOException ignored) {}
    }

    static private String getServerAddress() {
        return "YOUR ADDRESS HERE";
    }

    static private void postError(Error e) {
        notNull(e);
        Log.v(TAG, e.name());
    }

    static private void postError(Error e, String msg) {
        notNull(e);
        notNullOrEmpty(msg);
        Log.v(TAG, e.name() + " : " + msg);
    }

    static private void postReadMessage(String msg) {
        notNull(msg);
        Log.v(TAG, msg);
    }

    static private void postStatus(Status s) {
        notNull(s);
        Log.v(TAG, s.name());
    }

    static void notEmpty(String s) {
        if (s.isEmpty())
            throw new IllegalStateException("string is null");
    }

    static void notNull(Object o) {
        if (o == null)
            throw new NullPointerException();
    }

    static void notNullOrEmpty(String s) {
        notNull(s);
        notEmpty(s);
    }

    static void validateBluetoothAddress(String address) {
        notNullOrEmpty(address);
        final String pattern = "^([0-9A-F]{2}:){5}([0-9A-F]{2})$";
        if (!address.matches(pattern))
            throw new IllegalStateException("address has incorrect format: " + address);
    }

    abstract static private class BackgroundThread extends Thread {
        @Override
        public void run() {
            super.run();
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        }
    }
}



