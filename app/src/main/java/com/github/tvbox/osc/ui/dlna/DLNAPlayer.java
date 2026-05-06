package com.github.tvbox.osc.ui.dlna;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.support.avtransport.callback.GetPositionInfo;
import org.fourthline.cling.support.avtransport.callback.Pause;
import org.fourthline.cling.support.avtransport.callback.Play;
import org.fourthline.cling.support.avtransport.callback.Seek;
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI;
import org.fourthline.cling.support.avtransport.callback.Stop;
import org.fourthline.cling.support.model.PositionInfo;

public class DLNAPlayer {
    private static final String TAG = "DLNAPlayer";
    
    private DLNADevice currentDevice;
    private AndroidUpnpService upnpService;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnDLNAStateListener stateListener;
    
    private boolean isPlaying = false;
    private String currentUrl;

    public interface OnDLNAStateListener {
        void onConnected(DLNADevice device);
        void onDisconnected();
        void onPlay();
        void onPause();
        void onStop();
        void onError(String errorMsg);
        void onPositionUpdate(long position, long duration);
    }

    public DLNAPlayer(AndroidUpnpService upnpService) {
        this.upnpService = upnpService;
    }

    public void setStateListener(OnDLNAStateListener listener) {
        this.stateListener = listener;
    }

    public void play(DLNADevice device, String url, String title) {
        this.currentDevice = device;
        this.currentUrl = url;
        
        Service avTransportService = getAVTransportService(device);
        if (avTransportService == null) {
            notifyError("设备不支持AVTransport服务");
            return;
        }

        String metadata = createMetadata(title, url);

        SetAVTransportURI setAction = new SetAVTransportURI(avTransportService, url, metadata) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "SetAVTransportURI success");
                sendPlay();
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                Log.e(TAG, "SetAVTransportURI failed: " + defaultMsg);
                notifyError("投屏失败: " + defaultMsg);
            }
        };

        upnpService.getControlPoint().execute(setAction);
        
        mainHandler.post(() -> {
            if (stateListener != null) {
                stateListener.onConnected(device);
            }
        });
    }

    private void sendPlay() {
        Service avTransportService = getAVTransportService(currentDevice);
        if (avTransportService == null) return;

        Play playAction = new Play(avTransportService) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "Play success");
                isPlaying = true;
                mainHandler.post(() -> {
                    if (stateListener != null) stateListener.onPlay();
                });
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                Log.e(TAG, "Play failed: " + defaultMsg);
                notifyError("播放失败: " + defaultMsg);
            }
        };

        upnpService.getControlPoint().execute(playAction);
    }

    public void pause() {
        Service avTransportService = getAVTransportService(currentDevice);
        if (avTransportService == null) return;

        Pause pauseAction = new Pause(avTransportService) {
            @Override
            public void success(ActionInvocation invocation) {
                isPlaying = false;
                mainHandler.post(() -> {
                    if (stateListener != null) stateListener.onPause();
                });
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                notifyError("暂停失败: " + defaultMsg);
            }
        };

        upnpService.getControlPoint().execute(pauseAction);
    }

    public void resume() {
        sendPlay();
    }

    public void stop() {
        Service avTransportService = getAVTransportService(currentDevice);
        if (avTransportService == null) return;

        Stop stopAction = new Stop(avTransportService) {
            @Override
            public void success(ActionInvocation invocation) {
                isPlaying = false;
                mainHandler.post(() -> {
                    if (stateListener != null) stateListener.onStop();
                });
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                notifyError("停止失败: " + defaultMsg);
            }
        };

        upnpService.getControlPoint().execute(stopAction);
    }

    public void seek(String target) {
        Service avTransportService = getAVTransportService(currentDevice);
        if (avTransportService == null) return;

        Seek seekAction = new Seek(avTransportService, target) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "Seek success");
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                notifyError("跳转失败: " + defaultMsg);
            }
        };

        upnpService.getControlPoint().execute(seekAction);
    }

    public void getPositionInfo() {
        Service avTransportService = getAVTransportService(currentDevice);
        if (avTransportService == null) return;

        GetPositionInfo getPositionInfoAction = new GetPositionInfo(avTransportService) {
            @Override
            public void received(ActionInvocation invocation, PositionInfo positionInfo) {
                long position = positionInfo.getTrackElapsedSeconds();
                long duration = positionInfo.getTrackDurationSeconds();
                mainHandler.post(() -> {
                    if (stateListener != null) {
                        stateListener.onPositionUpdate(position, duration);
                    }
                });
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                Log.e(TAG, "GetPositionInfo failed: " + defaultMsg);
            }
        };

        upnpService.getControlPoint().execute(getPositionInfoAction);
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public DLNADevice getCurrentDevice() {
        return currentDevice;
    }

    public void disconnect() {
        stop();
        currentDevice = null;
        currentUrl = null;
        isPlaying = false;
        mainHandler.post(() -> {
            if (stateListener != null) stateListener.onDisconnected();
        });
    }

    private Service getAVTransportService(DLNADevice device) {
        if (device == null || device.getDevice() == null) return null;
        return device.getDevice().findService(
                new org.fourthline.cling.model.types.UDAServiceType("AVTransport"));
    }

    private String createMetadata(String title, String url) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
                "<dc:title>" + escapeXml(title) + "</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:video/mp4:*\">" + escapeXml(url) + "</res>" +
                "</item></DIDL-Lite>";
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void notifyError(String msg) {
        mainHandler.post(() -> {
            if (stateListener != null) stateListener.onError(msg);
        });
    }
}
