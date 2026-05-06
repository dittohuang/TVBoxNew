package com.github.tvbox.osc.ui.dlna;

/**
 * DLNA设备封装类
 */
public class DLNADevice {
    private String name;
    private String uuid;
    private String location; // 设备描述文件URL
    private String avTransportControlUrl; // AVTransport控制URL
    private String baseUrl; // 设备基础URL

    public DLNADevice(String name, String uuid, String location) {
        this.name = name != null ? name : "未知设备";
        this.uuid = uuid != null ? uuid : "";
        this.location = location;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }
    public String getLocation() { return location; }
    public String getAvTransportControlUrl() { return avTransportControlUrl; }
    public void setAvTransportControlUrl(String url) { this.avTransportControlUrl = url; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

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
