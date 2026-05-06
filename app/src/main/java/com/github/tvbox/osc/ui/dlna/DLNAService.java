package com.github.tvbox.osc.ui.dlna;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DLNA设备发现Service - 使用SSDP协议
 */
public class DLNAService extends Service {
    private static final String TAG = "DLNAService";
    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    private final IBinder binder = new DLNABinder();
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private AtomicBoolean isSearching = new AtomicBoolean(false);
    private MulticastSocket socket;
    private OnDeviceDiscoveryListener discoveryListener;

    public interface OnDeviceDiscoveryListener {
        void onDeviceFound(String location, String usn, String friendlyName);
        void onSearchComplete();
    }

    public class DLNABinder extends Binder {
        public DLNAService getService() {
            return DLNAService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setDiscoveryListener(OnDeviceDiscoveryListener listener) {
        this.discoveryListener = listener;
    }

    public void searchDevices() {
        if (isSearching.get()) return;
        isSearching.set(true);

        executor.execute(() -> {
            try {
                performSSDPSearch();
            } catch (Exception e) {
                Log.e(TAG, "SSDP search error", e);
            } finally {
                isSearching.set(false);
                if (discoveryListener != null) {
                    discoveryListener.onSearchComplete();
                }
            }
        });
    }

    private void performSSDPSearch() throws IOException {
        String searchMessage =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "\r\n";

        InetAddress multicastAddress = InetAddress.getByName(SSDP_ADDRESS);

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        socket = new MulticastSocket();
        socket.setReuseAddress(true);
        socket.setSoTimeout(5000);

        // 发送M-SEARCH
        byte[] sendData = searchMessage.getBytes("UTF-8");
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, multicastAddress, SSDP_PORT);
        socket.send(sendPacket);

        // 接收响应
        byte[] receiveData = new byte[2048];
        long endTime = System.currentTimeMillis() + 5000;

        while (System.currentTimeMillis() < endTime && isSearching.get()) {
            try {
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                socket.receive(receivePacket);
                String response = new String(receivePacket.getData(), 0, receivePacket.getLength(), "UTF-8");
                parseResponse(response);
            } catch (SocketTimeoutException e) {
                break;
            }
        }

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    private void parseResponse(String response) {
        String location = extractHeader(response, "LOCATION");
        String usn = extractHeader(response, "USN");
        String st = extractHeader(response, "ST");

        if (location != null && st != null && st.contains("MediaRenderer")) {
            if (discoveryListener != null) {
                discoveryListener.onDeviceFound(location, usn != null ? usn : "", "");
            }
        }
    }

    private String extractHeader(String response, String headerName) {
        String[] lines = response.split("\r\n");
        for (String line : lines) {
            if (line.toUpperCase().startsWith(headerName.toUpperCase() + ":")) {
                return line.substring(headerName.length() + 1).trim();
            }
        }
        return null;
    }

    public void stopSearch() {
        isSearching.set(false);
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Override
    public void onDestroy() {
        stopSearch();
        executor.shutdownNow();
        super.onDestroy();
    }
}
