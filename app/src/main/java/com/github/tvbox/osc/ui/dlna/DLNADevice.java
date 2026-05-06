package com.github.tvbox.osc.ui.dlna;

import org.fourthline.cling.model.meta.Device;

/**
 * DLNA设备封装类
 */
public class DLNADevice {
    private String name;
    private String uuid;
    private Device device; // Cling设备对象

    public DLNADevice(Device device) {
        this.device = device;
        this.name = device.getDetails() != null && device.getDetails().getFriendlyName() != null 
                ? device.getDetails().getFriendlyName() 
                : "未知设备";
        this.uuid = device.getIdentity().getUdn().getIdentifierString();
    }

    public String getName() { return name; }
    public String getUuid() { return uuid; }
    public Device getDevice() { return device; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DLNADevice that = (DLNADevice) o;
        return uuid != null && uuid.equals(that.uuid);
    }

    @Override
    public int hashCode() {
        return uuid != null ? uuid.hashCode() : 0;
    }

    @Override
    public String toString() {
        return name;
    }
}
