package com.github.tvbox.osc.ui.dlna;

import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;

/**
 * DLNA UPnP Service
 * 继承Cling的AndroidUpnpServiceImpl，配置适合Android的网络参数
 */
public class DLNAService extends AndroidUpnpServiceImpl {

    @Override
    protected UpnpServiceConfiguration createConfiguration() {
        return new AndroidUpnpServiceConfiguration() {
            @Override
            public int getRegistryMaintenanceIntervalMillis() {
                return 7000; // 注册表维护间隔
            }
        };
    }
}
