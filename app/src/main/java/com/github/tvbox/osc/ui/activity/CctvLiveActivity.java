package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CctvLiveActivity extends BaseActivity {
    private static final String DESKTOP_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 Edg/128.0.0.0";
    private static final long CHANNEL_LABEL_HIDE_DELAY_MS = 2500L;
    private static final long CHANNEL_MENU_HIDE_DELAY_MS = 5000L;
    private static final long CHANNEL_LOAD_TIMEOUT_MS = 15000L;

    private WebView webView;
    private TextView tvChannelName;
    private LinearLayout channelMenuContainer;
    private TvRecyclerView channelRecyclerView;
    private View loadingOverlay;
    private TextView tvLoadingStatus;
    private ChannelMenuAdapter channelMenuAdapter;
    private GestureDetector gestureDetector;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideChannelLabel = new Runnable() {
        @Override
        public void run() {
            if (tvChannelName != null) {
                tvChannelName.setVisibility(View.GONE);
            }
        }
    };
    private final Runnable hideChannelMenuTask = new Runnable() {
        @Override
        public void run() {
            hideChannelMenu();
        }
    };
    private final Runnable channelLoadTimeoutTask = new Runnable() {
        @Override
        public void run() {
            if (loadingOverlay != null && loadingOverlay.getVisibility() == View.VISIBLE && tvLoadingStatus != null) {
                tvLoadingStatus.setText("加载失败，按上/下键切台重试");
            }
        }
    };

    private final List<ChannelItem> channels = new ArrayList<>();
    private int currentChannelIndex = 0;
    private int menuCursorIndex = 0;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_cctv_live;
    }

    @Override
    protected void init() {
        webView = findViewById(R.id.webView);
        tvChannelName = findViewById(R.id.tvChannelName);
        channelMenuContainer = findViewById(R.id.channelMenuContainer);
        channelRecyclerView = findViewById(R.id.channelRecyclerView);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        tvLoadingStatus = findViewById(R.id.tvLoadingStatus);
        channelMenuContainer.bringToFront();
        tvChannelName.bringToFront();
        loadingOverlay.bringToFront();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            channelMenuContainer.setElevation(20f);
            tvChannelName.setElevation(21f);
            loadingOverlay.setElevation(19f);
        }
        initChannels();
        // 恢复上次播放的频道
        String lastChannel = Hawk.get(HawkConfig.LIVE_CHANNEL, "");
        if (!lastChannel.isEmpty()) {
            for (int i = 0; i < channels.size(); i++) {
                if (lastChannel.equals(channels.get(i).name)) {
                    currentChannelIndex = i;
                    break;
                }
            }
        }
        initChannelMenu();
        initWebView();
        loadCurrentChannel();
    }

    private void initChannelMenu() {
        channelRecyclerView.setHasFixedSize(true);
        channelRecyclerView.setLayoutManager(new V7LinearLayoutManager(this, 1, false));
        channelMenuAdapter = new ChannelMenuAdapter();
        channelMenuAdapter.setNewData(channels);
        channelRecyclerView.setAdapter(channelMenuAdapter);
        channelRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (isChannelMenuVisible()) {
                    restartChannelMenuHideTimer();
                }
            }
        });
        channelRecyclerView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (position < 0) {
                    return;
                }
                channelMenuAdapter.setFocusedIndex(position);
                restartChannelMenuHideTimer();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                selectChannel(position);
            }
        });
        channelMenuAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectChannel(position);
            }
        });
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void initWebView() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                showChannelMenu();
                return true;
            }
        });
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setUserAgentString(DESKTOP_UA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setBackgroundColor(0xFF000000);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.addJavascriptInterface(new WebAppBridge(), "AndroidCctvBridge");
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (gestureDetector != null) {
                    gestureDetector.onTouchEvent(event);
                }
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return super.onConsoleMessage(consoleMessage);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectPlaybackScript();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectPlaybackScript();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        injectPlaybackScript();
                    }
                }, 500L);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        injectPlaybackScript();
                    }
                }, 1500L);
            }
        });
    }

    private void initChannels() {
        // 尝试从配置文件 lives 中读取"央视"分组
        List<LiveChannelGroup> groups = ApiConfig.get().getChannelGroupList();
        if (groups != null) {
            for (LiveChannelGroup group : groups) {
                if ("央视".equals(group.getGroupName())) {
                    for (LiveChannelItem item : group.getLiveChannels()) {
                        List<String> urls = item.getChannelUrls();
                        if (urls != null && !urls.isEmpty()) {
                            channels.add(new ChannelItem(item.getChannelName(), urls.get(0)));
                        }
                    }
                    break;
                }
            }
        }
        // 降级：配置中没有"央视"分组时使用内置列表
        if (channels.isEmpty()) {
            channels.add(new ChannelItem("CCTV-13 新闻", "https://tv.cctv.com/live/cctv13/"));
            channels.add(new ChannelItem("CCTV-1 综合", "https://tv.cctv.com/live/cctv1/"));
            channels.add(new ChannelItem("CCTV-2 财经", "https://tv.cctv.com/live/cctv2/"));
            channels.add(new ChannelItem("CCTV-3 综艺", "https://tv.cctv.com/live/cctv3/"));
            channels.add(new ChannelItem("CCTV-4 中文国际", "https://tv.cctv.com/live/cctv4/"));
            channels.add(new ChannelItem("CCTV-5 体育", "https://tv.cctv.com/live/cctv5/"));
            channels.add(new ChannelItem("CCTV-5+ 体育赛事", "https://tv.cctv.com/live/cctv5plus/"));
            channels.add(new ChannelItem("CCTV-6 电影", "https://tv.cctv.com/live/cctv6/"));
            channels.add(new ChannelItem("CCTV-7 国防军事", "https://tv.cctv.com/live/cctv7/"));
            channels.add(new ChannelItem("CCTV-8 电视剧", "https://tv.cctv.com/live/cctv8/"));
            channels.add(new ChannelItem("CCTV-9 纪录", "https://tv.cctv.com/live/cctvjilu/"));
            channels.add(new ChannelItem("CCTV-10 科教", "https://tv.cctv.com/live/cctv10/"));
            channels.add(new ChannelItem("CCTV-11 戏曲", "https://tv.cctv.com/live/cctv11/"));
            channels.add(new ChannelItem("CCTV-12 社会与法", "https://tv.cctv.com/live/cctv12/"));
            channels.add(new ChannelItem("CCTV-14 少儿", "https://tv.cctv.com/live/cctvchild/"));
            channels.add(new ChannelItem("CCTV-15 音乐", "https://tv.cctv.com/live/cctv15/"));
            channels.add(new ChannelItem("CCTV-16 奥林匹克", "https://tv.cctv.com/live/cctv16/"));
            channels.add(new ChannelItem("CCTV-17 农业农村", "https://tv.cctv.com/live/cctv17/"));
        }
    }

    private void loadCurrentChannel() {
        if (channels.isEmpty()) {
            return;
        }
        showLoadingOverlay();
        ChannelItem item = channels.get(currentChannelIndex);
        if (channelMenuAdapter != null) {
            channelMenuAdapter.setSelectedIndex(currentChannelIndex);
            channelMenuAdapter.setFocusedIndex(currentChannelIndex);
        }
        showChannelLabel(item, currentChannelIndex + 1, channels.size());
        webView.loadUrl(item.url);
        handler.removeCallbacks(channelLoadTimeoutTask);
        handler.postDelayed(channelLoadTimeoutTask, CHANNEL_LOAD_TIMEOUT_MS);
    }

    private void selectChannel(int position) {
        if (position < 0 || position >= channels.size()) {
            return;
        }
        currentChannelIndex = position;
        Hawk.put(HawkConfig.LIVE_CHANNEL, channels.get(position).name);
        loadCurrentChannel();
        hideChannelMenu();
    }

    private void showChannelLabel(ChannelItem item, int position, int total) {
        String label = String.format(Locale.getDefault(), "%d/%d %s", position, total, item.name);
        tvChannelName.setText(label);
        tvChannelName.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideChannelLabel);
        handler.postDelayed(hideChannelLabel, CHANNEL_LABEL_HIDE_DELAY_MS);
    }

    private void playNextChannel() {
        if (channels.isEmpty()) {
            return;
        }
        currentChannelIndex++;
        if (currentChannelIndex >= channels.size()) {
            currentChannelIndex = 0;
        }
        loadCurrentChannel();
    }

    private void playPreviousChannel() {
        if (channels.isEmpty()) {
            return;
        }
        currentChannelIndex--;
        if (currentChannelIndex < 0) {
            currentChannelIndex = channels.size() - 1;
        }
        loadCurrentChannel();
    }

    private boolean isChannelMenuVisible() {
        return channelMenuContainer != null && channelMenuContainer.getVisibility() == View.VISIBLE;
    }

    private void showChannelMenu() {
        if (channels.isEmpty()) {
            return;
        }
        channelMenuContainer.bringToFront();
        tvChannelName.bringToFront();
        if (loadingOverlay.getVisibility() == View.VISIBLE) {
            return;
        }
        menuCursorIndex = currentChannelIndex;
        channelMenuContainer.setVisibility(View.VISIBLE);
        channelMenuAdapter.setSelectedIndex(currentChannelIndex);
        channelMenuAdapter.setFocusedIndex(menuCursorIndex);
        channelRecyclerView.scrollToPosition(currentChannelIndex);
        channelRecyclerView.setSelection(currentChannelIndex);
        channelRecyclerView.post(new Runnable() {
            @Override
            public void run() {
                RecyclerView.ViewHolder holder = channelRecyclerView.findViewHolderForAdapterPosition(currentChannelIndex);
                if (holder != null) {
                    holder.itemView.requestFocus();
                } else {
                    channelRecyclerView.requestFocus();
                }
            }
        });
    }

    private void hideChannelMenu() {
        handler.removeCallbacks(hideChannelMenuTask);
        if (channelMenuContainer != null) {
            channelMenuContainer.setVisibility(View.GONE);
        }
        if (webView != null) {
            webView.requestFocus();
        }
    }

    private void restartChannelMenuHideTimer() {
        handler.removeCallbacks(hideChannelMenuTask);
        handler.postDelayed(hideChannelMenuTask, CHANNEL_MENU_HIDE_DELAY_MS);
    }

    private void moveMenuCursor(int delta) {
        if (channels.isEmpty()) {
            return;
        }
        menuCursorIndex += delta;
        if (menuCursorIndex < 0) {
            menuCursorIndex = channels.size() - 1;
        } else if (menuCursorIndex >= channels.size()) {
            menuCursorIndex = 0;
        }
        if (channelMenuAdapter != null) {
            channelMenuAdapter.setFocusedIndex(menuCursorIndex);
        }
        channelRecyclerView.scrollToPosition(menuCursorIndex);
        channelRecyclerView.setSelection(menuCursorIndex);
        restartChannelMenuHideTimer();
    }

    private void injectPlaybackScript() {
        String script = "(function(){"
                + "if(window.__tvboxCctvInjected){return;}"
                + "window.__tvboxCctvInjected=true;"
                + "var style=document.createElement('style');"
                + "style.textContent='html,body{background:#000 !important;overflow:hidden !important;} body>*:not(#tvbox-cctv-container){visibility:hidden !important;} video{background:#000 !important;} .vjs-big-play-button,.video-play-btn,.play-btn,[class*=play-btn],[class*=play-button],[class*=player-control],[class*=toolbar],[class*=header],[class*=nav],[class*=menu]{display:none !important;opacity:0 !important;visibility:hidden !important;}';"
                + "document.head&&document.head.appendChild(style);"
                + "var notified=false;"
                + "function notifyPlaying(){if(notified){return;} notified=true; if(window.AndroidCctvBridge&&window.AndroidCctvBridge.onVideoPlaying){window.AndroidCctvBridge.onVideoPlaying();}}"
                + "function moveVideo(video){"
                + "if(!video){return false;}"
                + "try{"
                + "var container=document.getElementById('tvbox-cctv-container');"
                + "if(!container){container=document.createElement('div');container.id='tvbox-cctv-container';container.style.cssText='position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:2147483647;background:#000;display:flex;align-items:center;justify-content:center;overflow:hidden;';document.body.appendChild(container);}"
                + "video.style.cssText='width:100vw;height:100vh;object-fit:contain;background:#000;';"
                + "if(video.parentNode!==container){container.appendChild(video);}"
                + "for(var i=0;i<document.body.children.length;i++){var n=document.body.children[i];if(n.id!=='tvbox-cctv-container'){n.style.display='none';}}"
                + "video.muted=false;video.volume=1;video.autoplay=true;video.controls=false;video.setAttribute('playsinline','false');"
                + "var p=video.play(); if(p&&p.catch){p.catch(function(){});}"
                + "video.onplaying=function(){notifyPlaying();};"
                + "video.oncanplay=function(){if(video.currentTime>0||!video.paused){notifyPlaying();}};"
                + "if(video.readyState>2 && (video.currentTime>0||!video.paused)){notifyPlaying();}"
                + "return true;"
                + "}catch(e){return false;}"
                + "}"
                + "function clickMaybe(selector){var el=document.querySelector(selector);if(el){try{el.click();return true;}catch(e){}}return false;}"
                + "var count=0;"
                + "var timer=setInterval(function(){"
                + "count++;"
                + "var video=document.querySelector('video');"
                + "moveVideo(video);"
                + "clickMaybe('.vjs-big-play-button')||clickMaybe('.video-play-btn')||clickMaybe('.play-btn')||clickMaybe('[class*=play]');"
                + "if(video&&video.readyState>2){moveVideo(video); if(video.currentTime>0||!video.paused){notifyPlaying();}}"
                + "if(count>150){clearInterval(timer);window.__tvboxCctvInjected=false;}"
                + "},200);"
                + "})();";
        evaluateScript(script);
    }

    private void showLoadingOverlay() {
        hideChannelMenu();
        if (loadingOverlay != null) {
            loadingOverlay.bringToFront();
            loadingOverlay.setVisibility(View.VISIBLE);
        }
        if (tvLoadingStatus != null) {
            tvLoadingStatus.setText("频道加载中......");
        }
    }

    private void hideLoadingOverlay() {
        handler.removeCallbacks(channelLoadTimeoutTask);
        if (loadingOverlay != null) {
            loadingOverlay.setVisibility(View.GONE);
        }
        channelMenuContainer.bringToFront();
        tvChannelName.bringToFront();
    }

    private void evaluateScript(String script) {
        if (webView == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(script, null);
        } else {
            webView.loadUrl("javascript:" + script);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (isChannelMenuVisible()) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    moveMenuCursor(-1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    moveMenuCursor(1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                    selectChannel(menuCursorIndex);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_MENU) {
                    hideChannelMenu();
                    return true;
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                playPreviousChannel();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                playNextChannel();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A
                    || keyCode == KeyEvent.KEYCODE_MENU
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                showChannelMenu();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            injectPlaybackScript();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class WebAppBridge {
        @JavascriptInterface
        public void onVideoReady() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hideLoadingOverlay();
                }
            });
        }

        @JavascriptInterface
        public void onVideoPlaying() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hideLoadingOverlay();
                }
            });
        }
    }

    private static final class ChannelItem {
        private final String name;
        private final String url;

        private ChannelItem(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private static final class ChannelMenuAdapter extends BaseQuickAdapter<ChannelItem, BaseViewHolder> {
        private int selectedIndex = -1;
        private int focusedIndex = -1;

        private ChannelMenuAdapter() {
            super(R.layout.item_live_channel, new ArrayList<ChannelItem>());
        }

        @Override
        protected void convert(BaseViewHolder helper, ChannelItem item) {
            int position = helper.getAdapterPosition();
            TextView tvChannelNum = helper.getView(R.id.tvChannelNum);
            TextView tvName = helper.getView(R.id.tvChannelName);
            boolean isSelected = position == selectedIndex;
            boolean isFocused = position == focusedIndex;
            tvChannelNum.setText(String.valueOf(position + 1));
            StringBuilder prefix = new StringBuilder();
            if (isSelected) {
                prefix.append("正在播放 ");
            }
            tvName.setText(prefix + item.name);

            int bgColor;
            int textColor;
            if (isSelected && isFocused) {
                bgColor = R.color.color_0CADE2;
                textColor = R.color.color_3D3D3D;
            } else if (isFocused) {
                bgColor = R.color.color_3D3D3D;
                textColor = R.color.color_0CADE2;
            } else if (isSelected) {
                bgColor = R.color.color_3D3D3D;
                textColor = R.color.color_0CADE2;
            } else {
                bgColor = android.R.color.transparent;
                textColor = R.color.color_CCFFFFFF;
            }
            helper.itemView.setBackgroundColor(tvName.getResources().getColor(bgColor));
            tvChannelNum.setTextColor(tvName.getResources().getColor(textColor));
            tvName.setTextColor(tvName.getResources().getColor(textColor));
            helper.itemView.setAlpha(isSelected || isFocused ? 1.0f : 0.92f);
        }

        private void setSelectedIndex(int selectedIndex) {
            this.selectedIndex = selectedIndex;
            notifyDataSetChanged();
        }

        private void setFocusedIndex(int focusedIndex) {
            this.focusedIndex = focusedIndex;
            notifyDataSetChanged();
        }
    }
}