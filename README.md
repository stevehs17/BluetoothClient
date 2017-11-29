An Android Bluetooth client.

This project is based on Google's sample Bluetooth project, BluetoothChat.
It differs from BluetoothChat in the following ways:

1. It does not also provide a Bluetooth server.
2. It fixes a bug that could lead to the corruption of incoming messages.
3. It performs writes off the main application thread.
4. It includes checks of whether Bluetooth is supported and enabled in
the Bluetooth code itself rather than requiring the user of the code to
do them.
5. It implements the Bluetooth code in a genuine service. 

Note that to use the code, you will need to provide the address of the server
you want to connect to.
