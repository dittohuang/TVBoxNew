package com.github.tvbox.osc.ui.dlna;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
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
    private volatile MulticastSocket socket;
    private OnDeviceDiscoveryListener discoveryListener;

    public interface OnDeviceDiscoveryListener {
        void onDeviceFound(String location, String usn, String friendlyName);
        void onSearchComplete();
        void onDebugLog(String message);
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
        debugLog("触发搜索...");
        // Close any in-progress socket to abort the current receive loop quickly
        MulticastSocket s = socket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
        executor.execute(this::runSearch);
    }

    private void runSearch() {
        isSearching.set(true);
        try {
            performSSDPSearch();
        } catch (Exception e) {
            Log.e(TAG, "SSDP search error", e);
            debugLog("搜索出错: " + e.getMessage());
        } finally {
            isSearching.set(false);
            if (discoveryListener != null) {
                discoveryListener.onSearchComplete();
            }
        }
    }

    private void performSSDPSearch() throws IOException {
        String searchMessage =
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 3\r\n" +
            "ST: ssdp:all\r\n" +
            "\r\n";

        InetAddress multicastAddress = InetAddress.getByName(SSDP_ADDRESS);
        debugLog("目标组播地址: " + SSDP_ADDRESS + ":" + SSDP_PORT);

        // Acquire MulticastLock — without this Android's WiFi chip filters out
        // multicast packets at the hardware level, so no SSDP responses arrive.
        WifiManager wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock multicastLock = null;
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("dlna_search");
            multicastLock.acquire();
            debugLog("已获取 MulticastLock");
        } else {
            debugLog("警告: 无法获取 WifiManager，组播可能被过滤");
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            socket = new MulticastSocket();
            socket.setReuseAddress(true);
            socket.setSoTimeout(5000);

            // Join the multicast group so responses are received on all devices.
            // Use the WifiManager IP to find the correct WiFi network interface instead
            // of InetAddress.getLocalHost(), which may return 127.0.0.1 on Android.
            NetworkInterface wifiNetIf = null;
            if (wifiManager != null) {
                try {
                    int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                    if (ipInt != 0) {
                        byte[] ipBytes = {
                            (byte) (ipInt & 0xff),
                            (byte) ((ipInt >> 8) & 0xff),
                            (byte) ((ipInt >> 16) & 0xff),
                            (byte) ((ipInt >> 24) & 0xff)
                        };
                        InetAddress wifiAddr = InetAddress.getByAddress(ipBytes);
                        wifiNetIf = NetworkInterface.getByInetAddress(wifiAddr);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not resolve WiFi interface", e);
                }
            }
            if (wifiNetIf != null) {
                socket.joinGroup(new java.net.InetSocketAddress(multicastAddress, SSDP_PORT),
                        wifiNetIf);
                debugLog("已加入组播组，WiFi网卡: " + wifiNetIf.getDisplayName());
            } else {
                socket.joinGroup(multicastAddress);
                debugLog("已加入组播组 (默认网卡)");
            }

            debugLog("Socket 已创建，本地端口: " + socket.getLocalPort());

            // 发送M-SEARCH
            byte[] sendData = searchMessage.getBytes("UTF-8");
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length,
                    multicastAddress, SSDP_PORT);
            socket.send(sendPacket);
            debugLog("M-SEARCH 请求已发送，等待响应 (超时5秒)...");

            // 接收响应
            byte[] receiveData = new byte[2048];
            long endTime = System.currentTimeMillis() + 5000;
            int responseCount = 0;

            while (System.currentTimeMillis() < endTime && isSearching.get()) {
                try {
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    socket.receive(receivePacket);
                    responseCount++;
                    String senderIp = receivePacket.getAddress().getHostAddress();
                    String response = new String(receivePacket.getData(), 0,
                            receivePacket.getLength(), "UTF-8");
                    debugLog("收到响应 #" + responseCount + " 来自: " + senderIp);
                    parseResponse(response, senderIp);
                } catch (SocketTimeoutException e) {
                    debugLog("接收超时，共收到 " + responseCount + " 个响应");
                    break;
                }
            }

            if (responseCount == 0) {
                debugLog("未收到任何 SSDP 响应，请确认:");
                debugLog("  1. 投屏设备已开机并在同一 WiFi");
                debugLog("  2. 路由器未禁止组播(Multicast)");
            }
        } finally {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (Exception ignored) {}
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
                debugLog("已释放 MulticastLock");
            }
        }
    }

    private void parseResponse(String response, String senderIp) {
        String location = extractHeader(response, "LOCATION");
        String usn = extractHeader(response, "USN");
        String st = extractHeader(response, "ST");
        String nt = extractHeader(response, "NT");

        if (location != null) {
            String type = st != null ? st : (nt != null ? nt : "(unknown)");
            debugLog("发现候选设备: " + location + " | type=" + type);
            if (discoveryListener != null) {
                discoveryListener.onDeviceFound(location, usn != null ? usn : "", "");
            }
        } else {
            String stInfo = st != null ? st : (nt != null ? nt : "(无ST/NT)");
            debugLog("忽略无LOCATION响应 [" + senderIp + "]: " + stInfo);
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

    private void debugLog(String message) {
        Log.d(TAG, message);
        if (discoveryListener != null) {
            discoveryListener.onDebugLog(message);
        }
    }

    @Override
    public void onDestroy() {
        stopSearch();
        executor.shutdownNow();
        super.onDestroy();
    }
}
