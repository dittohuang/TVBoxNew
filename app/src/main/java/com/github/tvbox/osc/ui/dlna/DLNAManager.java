package com.github.tvbox.osc.ui.dlna;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.message.header.STAllHeader;
import org.fourthline.cling.model.message.header.UDADeviceTypeHeader;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;

import java.util.ArrayList;
import java.util.List;

public class DLNAManager {
    private static final String TAG = "DLNAManager";
    private static volatile DLNAManager instance;
    
    private Context context;
    private AndroidUpnpService upnpService;
    private List<DLNADevice> deviceList = new ArrayList<>();
    private OnDeviceChangeListener listener;
    private boolean isBound = false;
    private DeviceRegistryListener registryListener;

    public interface OnDeviceChangeListener {
        void onDeviceAdded(DLNADevice device);
        void onDeviceRemoved(DLNADevice device);
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
            upnpService = (AndroidUpnpService) service;
            registryListener = new DeviceRegistryListener();
            upnpService.getRegistry().addListener(registryListener);
            
            // 添加已发现的设备
            for (Device device : upnpService.getRegistry().getDevices()) {
                if (isMediaRenderer(device)) {
                    addDevice(new DLNADevice(device));
                }
            }
            
            // 发起搜索
            upnpService.getControlPoint().search(new UDADeviceTypeHeader(new UDADeviceType("MediaRenderer")));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            upnpService = null;
            isBound = false;
        }
    };

    public void startSearch() {
        if (context == null) {
            Log.e(TAG, "DLNAManager not initialized, call init() first");
            return;
        }
        deviceList.clear();
        Intent intent = new Intent(context, DLNAService.class);
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        isBound = true;
    }

    public void stopSearch() {
        if (isBound && upnpService != null) {
            if (registryListener != null) {
                upnpService.getRegistry().removeListener(registryListener);
            }
            context.unbindService(serviceConnection);
            isBound = false;
        }
    }

    public void search() {
        if (upnpService != null) {
            upnpService.getControlPoint().search(new UDADeviceTypeHeader(new UDADeviceType("MediaRenderer")));
        }
    }

    public List<DLNADevice> getDeviceList() {
        return deviceList;
    }

    public void setOnDeviceChangeListener(OnDeviceChangeListener listener) {
        this.listener = listener;
    }

    public AndroidUpnpService getUpnpService() {
        return upnpService;
    }

    public void destroy() {
        stopSearch();
        deviceList.clear();
        listener = null;
    }

    private void addDevice(DLNADevice device) {
        if (!deviceList.contains(device)) {
            deviceList.add(device);
            if (listener != null) {
                listener.onDeviceAdded(device);
            }
        }
    }

    private void removeDevice(DLNADevice device) {
        deviceList.remove(device);
        if (listener != null) {
            listener.onDeviceRemoved(device);
        }
    }

    private boolean isMediaRenderer(Device device) {
        return device.getType().getType().equals("MediaRenderer");
    }

    private class DeviceRegistryListener extends DefaultRegistryListener {
        @Override
        public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
            if (isMediaRenderer(device)) {
                addDevice(new DLNADevice(device));
            }
        }

        @Override
        public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
            if (isMediaRenderer(device)) {
                removeDevice(new DLNADevice(device));
            }
        }

        @Override
        public void localDeviceAdded(Registry registry, LocalDevice device) {
            // 不处理本地设备
        }

        @Override
        public void localDeviceRemoved(Registry registry, LocalDevice device) {
            // 不处理本地设备
        }
    }
}
