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

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    public void onClickStartService(View unused) {
        Intent i = new Intent(this, BluetoothClientService.class);
        i.setAction(BluetoothClientService.ACTION_START);
        startService(i);
    }

    public void onClickWriteMessage(View unused) {
        Intent i = new Intent(this, BluetoothClientService.class);
        i.setAction(BluetoothClientService.ACTION_WRITE);
        i.putExtra(BluetoothClientService.EXTRA_MESSAGE, "test write");
        startService(i);
    }

    public void onClickStopService(View unused) {
        Intent i = new Intent(this, BluetoothClientService.class);
        i.setAction(BluetoothClientService.ACTION_STOP);
        startService(i);
    }
}
