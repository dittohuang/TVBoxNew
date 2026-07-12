package com.github.tvbox.osc.ui.dlna;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public class DLNAManager {
    private static final String TAG = "DLNAManager";
    private static volatile DLNAManager instance;
    private static final int MAX_DEBUG_HISTORY = 200;

    private Context context;
    private DLNAService dlnaService;
    private boolean isBound = false;
    private List<DLNADevice> deviceList = new ArrayList<>();
    private OnDeviceChangeListener listener;
    private OkHttpClient httpClient = new OkHttpClient();
    private ExecutorService executor = Executors.newCachedThreadPool();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> debugHistory = new ArrayList<>();
    private volatile boolean debugEnabled = true;

    public interface OnDeviceChangeListener {
        void onDeviceAdded(DLNADevice device);
        void onDeviceRemoved(DLNADevice device);
        void onSearchComplete();
        void onDebugLog(String message);
    }

    private DLNAManager() {}

    public static DLNAManager getInstance() {
        if (instance == null) {
            synchronized (DLNAManager.class) {
                if (instance == null) {
                    instance = new DLNAManager();
                }
            }
        }
        return instance;
    }

    public void init(Context context) {
        this.context = context.getApplicationContext();
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            DLNAService.DLNABinder binder = (DLNAService.DLNABinder) service;
            dlnaService = binder.getService();
            debugLog("Service 已连接，开始搜索...");
            dlnaService.setDiscoveryListener(new DLNAService.OnDeviceDiscoveryListener() {
                @Override
                public void onDeviceFound(String location, String usn, String friendlyName) {
                    debugLog("正在获取设备描述: " + location);
                    // 获取设备详细信息
                    fetchDeviceDescription(location, usn);
                }

                @Override
                public void onSearchComplete() {
                    Log.d(TAG, "Search complete, found " + deviceList.size() + " devices");
                    debugLog("SSDP搜索完成，共发现 " + deviceList.size() + " 个设备");
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onSearchComplete();
                        }
                    });
                }

                @Override
                public void onDebugLog(String message) {
                    debugLog(message);
                }
            });
            dlnaService.searchDevices();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            debugLog("Service 连接断开");
            dlnaService = null;
            isBound = false;
        }
    };

    public void startSearch() {
        if (context == null) {
            Log.e(TAG, "DLNAManager not initialized, call init() first");
            return;
        }
        if (!deviceList.isEmpty()) {
            debugLog("保留上次发现结果，后台刷新中...");
        }
        Intent intent = new Intent(context, DLNAService.class);
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        isBound = true;
    }

    public void stopSearch() {
        if (isBound) {
            if (dlnaService != null) {
                dlnaService.stopSearch();
            }
            try {
                context.unbindService(serviceConnection);
            } catch (Exception e) {
                Log.e(TAG, "unbind error", e);
            }
            isBound = false;
        }
    }

    public void search() {
        if (dlnaService != null) {
            debugLog("手动刷新，重新搜索...");
            dlnaService.searchDevices();
        } else {
            debugLog("Service 未连接，无法搜索");
        }
    }

    public List<DLNADevice> getDeviceList() {
        return deviceList;
    }

    public void setOnDeviceChangeListener(OnDeviceChangeListener listener) {
        this.listener = listener;
    }

    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        debugLogInternal("调试日志已" + (enabled ? "开启" : "关闭"), true);
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public List<String> getDebugHistory() {
        synchronized (debugHistory) {
            return new ArrayList<>(debugHistory);
        }
    }

    public void pushDebugLog(String message) {
        debugLog(message);
    }

    public void destroy() {
        stopSearch();
        deviceList.clear();
        listener = null;
        executor.shutdownNow();
    }

    /**
     * 通过HTTP获取设备描述XML，解析设备名称和AVTransport控制URL
     */
    private void fetchDeviceDescription(String location, String usn) {
        executor.execute(() -> {
            try {
                debugLog("HTTP请求设备描述: " + location);
                Request request = new Request.Builder().url(location).build();
                Response response = httpClient.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    String xml = response.body().string();
                    debugLog("收到设备描述 XML (" + xml.length() + " 字节)");
                    DLNADevice device = parseDeviceXml(xml, location, usn);
                    if (device != null && device.getAvTransportControlUrl() != null) {
                        debugLog("解析成功: " + device.getName() + " | " + device.getAvTransportControlUrl());
                        addDevice(device);
                    } else {
                        debugLog("设备不支持 AVTransport 服务，跳过: " + location);
                    }
                } else {
                    debugLog("HTTP请求失败 code=" + response.code() + " url=" + location);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch device description: " + location, e);
                debugLog("获取设备描述出错: " + e.getMessage());
            }
        });
    }

    /**
     * 解析设备描述XML，提取设备名称和AVTransport服务控制URL
     */
    private DLNADevice parseDeviceXml(String xml, String location, String usn) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            String friendlyName = null;
            String udn = null;
            String avTransportControlUrl = null;
            String baseUrl = null;
            String urlBaseInXml = null;
            String currentServiceType = null;
            String controlUrl = null;
            boolean inService = false;

            // 从location提取baseUrl
            URL locationUrl = new URL(location);
            baseUrl = locationUrl.getProtocol() + "://" + locationUrl.getHost() + ":" + locationUrl.getPort();

            int eventType = parser.getEventType();
            String currentTag = "";

            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        currentTag = parser.getName();
                        if ("service".equals(currentTag)) {
                            inService = true;
                            currentServiceType = null;
                            controlUrl = null;
                        }
                        break;
                    case XmlPullParser.TEXT:
                        String text = parser.getText().trim();
                        if (!text.isEmpty()) {
                            if ("friendlyName".equals(currentTag)) {
                                friendlyName = text;
                            } else if ("UDN".equals(currentTag)) {
                                udn = text;
                            } else if ("URLBase".equals(currentTag)) {
                                urlBaseInXml = text;
                            } else if ("serviceType".equals(currentTag) && inService) {
                                currentServiceType = text;
                            } else if ("controlURL".equals(currentTag) && inService) {
                                controlUrl = text;
                            }
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        if ("service".equals(parser.getName())) {
                            if (currentServiceType != null &&
                                currentServiceType.contains("AVTransport")) {
                                avTransportControlUrl = controlUrl;
                            }
                            inService = false;
                        }
                        currentTag = "";
                        break;
                }
                eventType = parser.next();
            }

            if (friendlyName != null && avTransportControlUrl != null) {
                String uuid = buildStableUuid(udn, usn, location, avTransportControlUrl);
                DLNADevice device = new DLNADevice(friendlyName, uuid, location);

                // 处理controlUrl（可能是相对路径或绝对路径）
                String resolvedControlUrl = resolveControlUrl(baseUrl, urlBaseInXml, location, avTransportControlUrl);
                if (resolvedControlUrl == null) {
                    debugLog("控制地址解析失败，跳过: " + friendlyName + " | " + avTransportControlUrl);
                    return null;
                }
                device.setAvTransportControlUrl(resolvedControlUrl);
                device.setBaseUrl(urlBaseInXml != null && !urlBaseInXml.trim().isEmpty() ? urlBaseInXml.trim() : baseUrl);
                return device;
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse device XML error", e);
        }
        return null;
    }

    private String buildStableUuid(String udn, String usn, String location, String controlUrl) {
        String normalizedUdn = normalizeUuid(udn);
        if (!normalizedUdn.isEmpty()) {
            return normalizedUdn;
        }
        String normalizedUsn = normalizeUuid(usn);
        if (!normalizedUsn.isEmpty()) {
            return normalizedUsn;
        }
        String key = (location == null ? "" : location.trim()) + "|" +
                (controlUrl == null ? "" : controlUrl.trim());
        return key.isEmpty() ? "unknown-device" : key;
    }

    private String normalizeUuid(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("uuid:")) {
            value = value.substring(5);
        }
        int idx = value.indexOf("::");
        if (idx > 0) {
            value = value.substring(0, idx);
        }
        return value.trim();
    }

    private String resolveControlUrl(String baseUrl, String urlBaseInXml, String location, String controlUrl) {
        if (controlUrl == null) {
            return null;
        }
        String trimmed = controlUrl.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            URL locationUrl = new URL(location);
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return trimmed;
            }
            if (trimmed.startsWith("//")) {
                return locationUrl.getProtocol() + ":" + trimmed;
            }
            String effectiveBase = (urlBaseInXml != null && !urlBaseInXml.trim().isEmpty()) ? urlBaseInXml.trim() : baseUrl;
            URL base = new URL(effectiveBase);
            return new URL(base, trimmed).toString();
        } catch (Exception e) {
            Log.e(TAG, "Resolve control url error", e);
            return null;
        }
    }

    private void addDevice(DLNADevice device) {
        mainHandler.post(() -> {
            if (!deviceList.contains(device)) {
                deviceList.add(device);
                debugLog("已添加设备: " + device.getName());
                if (listener != null) {
                    listener.onDeviceAdded(device);
                }
            }
        });
    }

    private void debugLog(String message) {
        debugLogInternal(message, false);
    }

    private void debugLogInternal(String message, boolean forceNotify) {
        Log.d(TAG, message);
        synchronized (debugHistory) {
            debugHistory.add(message);
            if (debugHistory.size() > MAX_DEBUG_HISTORY) {
                debugHistory.remove(0);
            }
        }
        if (!debugEnabled && !forceNotify) {
            return;
        }
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onDebugLog(message);
            }
        });
    }
}
