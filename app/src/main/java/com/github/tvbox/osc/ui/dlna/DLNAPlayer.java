package com.github.tvbox.osc.ui.dlna;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.StringReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public class DLNAPlayer {
    private static final String TAG = "DLNAPlayer";
    private static final MediaType SOAP_MEDIA_TYPE = MediaType.parse("text/xml; charset=\"utf-8\"");
    private static final String AVT_NS = "urn:schemas-upnp-org:service:AVTransport:1";

    private DLNADevice currentDevice;
    private OkHttpClient httpClient = new OkHttpClient();
    private ExecutorService executor = Executors.newSingleThreadExecutor();
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

    public DLNAPlayer() {}

    public void setStateListener(OnDLNAStateListener listener) {
        this.stateListener = listener;
    }

    public void play(DLNADevice device, String url, String title) {
        this.currentDevice = device;
        this.currentUrl = url;

        String controlUrl = device.getAvTransportControlUrl();
        if (controlUrl == null) {
            notifyError("设备不支持AVTransport服务");
            return;
        }

        executor.execute(() -> {
            try {
                // 先Stop当前播放
                sendSoapAction(controlUrl, "Stop", buildStopBody());
                Thread.sleep(300);

                // SetAVTransportURI
                String metadata = createMetadata(title, url);
                String setUriBody = buildSetAVTransportURIBody(url, metadata);
                String setUriResponse = sendSoapAction(controlUrl, "SetAVTransportURI", setUriBody);

                if (setUriResponse != null) {
                    Thread.sleep(300);
                    // Play
                    String playBody = buildPlayBody();
                    String playResponse = sendSoapAction(controlUrl, "Play", playBody);

                    if (playResponse != null) {
                        isPlaying = true;
                        mainHandler.post(() -> {
                            if (stateListener != null) {
                                stateListener.onConnected(device);
                                stateListener.onPlay();
                            }
                        });
                    } else {
                        notifyError("播放失败");
                    }
                } else {
                    notifyError("设置播放地址失败");
                }
            } catch (Exception e) {
                Log.e(TAG, "Play error", e);
                notifyError("投屏失败: " + e.getMessage());
            }
        });
    }

    public void pause() {
        if (currentDevice == null) return;
        executor.execute(() -> {
            String response = sendSoapAction(currentDevice.getAvTransportControlUrl(), "Pause", buildPauseBody());
            if (response != null) {
                isPlaying = false;
                mainHandler.post(() -> { if (stateListener != null) stateListener.onPause(); });
            } else {
                notifyError("暂停失败");
            }
        });
    }

    public void resume() {
        if (currentDevice == null) return;
        executor.execute(() -> {
            String response = sendSoapAction(currentDevice.getAvTransportControlUrl(), "Play", buildPlayBody());
            if (response != null) {
                isPlaying = true;
                mainHandler.post(() -> { if (stateListener != null) stateListener.onPlay(); });
            } else {
                notifyError("恢复播放失败");
            }
        });
    }

    public void stop() {
        if (currentDevice == null) return;
        executor.execute(() -> {
            String response = sendSoapAction(currentDevice.getAvTransportControlUrl(), "Stop", buildStopBody());
            if (response != null) {
                isPlaying = false;
                mainHandler.post(() -> { if (stateListener != null) stateListener.onStop(); });
            }
        });
    }

    public void seek(String target) {
        if (currentDevice == null) return;
        executor.execute(() -> {
            String body = buildSeekBody(target);
            sendSoapAction(currentDevice.getAvTransportControlUrl(), "Seek", body);
        });
    }

    public void getPositionInfo() {
        if (currentDevice == null) return;
        executor.execute(() -> {
            String response = sendSoapAction(currentDevice.getAvTransportControlUrl(),
                "GetPositionInfo", buildGetPositionInfoBody());
            if (response != null) {
                parsePositionInfo(response);
            }
        });
    }

    public boolean isPlaying() { return isPlaying; }
    public DLNADevice getCurrentDevice() { return currentDevice; }

    public void disconnect() {
        stop();
        currentDevice = null;
        currentUrl = null;
        isPlaying = false;
        mainHandler.post(() -> { if (stateListener != null) stateListener.onDisconnected(); });
    }

    private String sendSoapAction(String controlUrl, String action, String body) {
        try {
            Request request = new Request.Builder()
                .url(controlUrl)
                .addHeader("Content-Type", "text/xml; charset=\"utf-8\"")
                .addHeader("SOAPAction", "\"" + AVT_NS + "#" + action + "\"")
                .post(RequestBody.create(SOAP_MEDIA_TYPE, body))
                .build();
            Response response = httpClient.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            } else {
                Log.e(TAG, action + " failed: " + response.code());
            }
        } catch (Exception e) {
            Log.e(TAG, action + " error", e);
        }
        return null;
    }

    private void parsePositionInfo(String xml) {
        try {
            long position = parseTimeFromXml(xml, "RelTime");
            long duration = parseTimeFromXml(xml, "TrackDuration");
            mainHandler.post(() -> {
                if (stateListener != null) {
                    stateListener.onPositionUpdate(position, duration);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Parse position error", e);
        }
    }

    private long parseTimeFromXml(String xml, String tagName) {
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int eventType = parser.getEventType();
            boolean foundTag = false;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.getName().equals(tagName)) {
                    foundTag = true;
                } else if (eventType == XmlPullParser.TEXT && foundTag) {
                    return parseTimeString(parser.getText().trim());
                } else if (eventType == XmlPullParser.END_TAG) {
                    foundTag = false;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    private long parseTimeString(String time) {
        // 格式: HH:MM:SS 或 H:MM:SS.xxx
        if (time == null || time.isEmpty() || time.startsWith("NOT_IMPLEMENTED")) return 0;
        try {
            String[] parts = time.split(":");
            if (parts.length == 3) {
                long h = Long.parseLong(parts[0]);
                long m = Long.parseLong(parts[1]);
                String secStr = parts[2].contains(".") ? parts[2].substring(0, parts[2].indexOf('.')) : parts[2];
                long s = Long.parseLong(secStr);
                return h * 3600 + m * 60 + s;
            }
        } catch (Exception e) { }
        return 0;
    }

    // SOAP Body构建方法
    private String buildSetAVTransportURIBody(String url, String metadata) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:SetAVTransportURI xmlns:u=\"" + AVT_NS + "\">" +
            "<InstanceID>0</InstanceID>" +
            "<CurrentURI>" + escapeXml(url) + "</CurrentURI>" +
            "<CurrentURIMetaData>" + escapeXml(metadata) + "</CurrentURIMetaData>" +
            "</u:SetAVTransportURI></s:Body></s:Envelope>";
    }

    private String buildPlayBody() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Play xmlns:u=\"" + AVT_NS + "\">" +
            "<InstanceID>0</InstanceID><Speed>1</Speed>" +
            "</u:Play></s:Body></s:Envelope>";
    }

    private String buildPauseBody() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Pause xmlns:u=\"" + AVT_NS + "\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:Pause></s:Body></s:Envelope>";
    }

    private String buildStopBody() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Stop xmlns:u=\"" + AVT_NS + "\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:Stop></s:Body></s:Envelope>";
    }

    private String buildSeekBody(String target) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Seek xmlns:u=\"" + AVT_NS + "\">" +
            "<InstanceID>0</InstanceID>" +
            "<Unit>REL_TIME</Unit>" +
            "<Target>" + target + "</Target>" +
            "</u:Seek></s:Body></s:Envelope>";
    }

    private String buildGetPositionInfoBody() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:GetPositionInfo xmlns:u=\"" + AVT_NS + "\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:GetPositionInfo></s:Body></s:Envelope>";
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
        mainHandler.post(() -> { if (stateListener != null) stateListener.onError(msg); });
    }
}
