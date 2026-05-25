package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.analytics.AnalyticsCollector;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsManifest;
import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.EventLogger;
import com.google.android.exoplayer2.util.UriUtil;
import com.google.android.exoplayer2.video.VideoSize;

import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.VideoViewManager;


public class ExoMediaPlayer extends AbstractPlayer implements Player.Listener {

    // Buffer window: start fetching when below 4 min, stop at 6 min to reduce buffer fluctuation.
    private static final int EXO_MIN_BUFFER_MS = 480000;
    private static final int EXO_MAX_BUFFER_MS = 600000;
    private static final int EXO_BUFFER_FOR_PLAYBACK_MS = 10000;
    private static final int EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 20000;
    private static final int EXO_PREBUFFER_START_PERCENT = 12;
    private static final long EXO_PREBUFFER_MAX_WAIT_MS = 20000L;
    private static final float AD_RESOLUTION_CHANGE_THRESHOLD = 0.25f;
    private static final String AD_DETECT_TAG = "AdDetect";
    private static final boolean AD_DETECT_TOAST_ENABLED = true;
    private static final boolean AD_SEGMENT_SKIP_ENABLED = true;
    private static final long AD_SKIP_MIN_INTERVAL_MS = 250L;
    private static final long AD_SKIP_TARGET_OFFSET_MS = 120L;
    private static final long AD_SKIP_LOOP_INTERVAL_MS = 120L;
    private static final int AD_SKIP_MAX_CONSECUTIVE = 20;
    private static final int AD_SKIP_MAX_STEP_SEGMENTS = 6;
    private static final long AD_SKIP_LOOKAHEAD_MS = 900L;
    private static final boolean AD_PREPROBE_ENABLED = true;
    private static final int AD_PREPROBE_WINDOW_SEGMENTS = 20;
    private static final int AD_PREPROBE_MAX_BYTES = 256 * 1024;
    private static final int AD_PREPROBE_CONCURRENT_TASKS = 4;

    protected Context mAppContext;
    protected SimpleExoPlayer mInternalPlayer;
    protected MediaSource mMediaSource;
    protected ExoMediaSourceHelper mMediaSourceHelper;

    private PlaybackParameters mSpeedPlaybackParameters;

    private boolean mIsPreparing;
    private boolean mPreparedNotified;
    private boolean mRenderingStartedNotified;
    private long mPrepareStartElapsedMs;
    private int mBaselineVideoWidth = -1;
    private int mBaselineVideoHeight = -1;
    private boolean mAdResolutionDetected = false;
    private final List<SegmentWindow> mVodSegments = new ArrayList<>();
    private boolean mIsVodPlaylist = false;
    private long mLastAdSkipElapsedMs = 0L;
    private int mConsecutiveAdSkipCount = 0;
    private final Set<Integer> mAdLikeSegmentIndices = new HashSet<>();
    private final Set<Integer> mProbedSegmentIndices = new HashSet<>();
    private boolean mAdSkipLoopRunning = false;
    private Map<String, String> mCurrentHeaders;
    private final ExecutorService mAdProbeExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mAdProbeConcurrentExecutor = Executors.newFixedThreadPool(AD_PREPROBE_CONCURRENT_TASKS);
    private final OkHttpClient mAdProbeHttpClient = new OkHttpClient.Builder().build();

    private LoadControl mLoadControl;
    private RenderersFactory mRenderersFactory;
    private TrackSelector mTrackSelector;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private final Runnable mPrepareBufferRunnable = new Runnable() {
        @Override
        public void run() {
            if (mInternalPlayer == null || !mIsPreparing) {
                return;
            }
            maybeStartAfterPreBuffer();
            if (mIsPreparing) {
                mMainHandler.postDelayed(this, 500L);
            }
        }
    };

    private final Runnable mAdSkipLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shouldKeepAdSkipLoopRunning()) {
                mAdSkipLoopRunning = false;
                return;
            }
            skipCurrentAdSegmentIfNeeded();
            if (shouldKeepAdSkipLoopRunning()) {
                mAdSkipLoopRunning = true;
                mMainHandler.postDelayed(this, AD_SKIP_LOOP_INTERVAL_MS);
            } else {
                mAdSkipLoopRunning = false;
            }
        }
    };

    public ExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        mMediaSourceHelper = ExoMediaSourceHelper.getInstance(context);
    }

    @Override
    public void initPlayer() {
        if (mLoadControl == null) {
            mLoadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    EXO_MIN_BUFFER_MS,
                    EXO_MAX_BUFFER_MS,
                    EXO_BUFFER_FOR_PLAYBACK_MS,
                    EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        }

        mInternalPlayer = new SimpleExoPlayer.Builder(
                mAppContext,
                mRenderersFactory == null ? mRenderersFactory = new DefaultRenderersFactory(mAppContext) : mRenderersFactory,
                mTrackSelector == null ? mTrackSelector = new DefaultTrackSelector(mAppContext) : mTrackSelector,
                new DefaultMediaSourceFactory(mAppContext),
            mLoadControl,
                DefaultBandwidthMeter.getSingletonInstance(mAppContext),
                new AnalyticsCollector(Clock.DEFAULT))
                .build();
        setOptions();

        //播放器日志
        if (VideoViewManager.getConfig().mIsEnableLog && mTrackSelector instanceof MappingTrackSelector) {
            mInternalPlayer.addAnalyticsListener(new EventLogger((MappingTrackSelector) mTrackSelector, "ExoPlayer"));
        }

        mInternalPlayer.addListener(this);
    }

    public void setTrackSelector(TrackSelector trackSelector) {
        mTrackSelector = trackSelector;
    }

    public void setRenderersFactory(RenderersFactory renderersFactory) {
        mRenderersFactory = renderersFactory;
    }

    public void setLoadControl(LoadControl loadControl) {
        mLoadControl = loadControl;
    }

    @Override
    public void setDataSource(String path, Map<String, String> headers) {
        // Enable disk cache for Exo sources so fetched segments can be reused and buffered more steadily.
        resetAdDetectionState();
        mCurrentHeaders = headers;
        mMediaSource = mMediaSourceHelper.getMediaSource(path, headers, true);
    }

    @Override
    public void setDataSource(AssetFileDescriptor fd) {
        //no support
    }

    @Override
    public void start() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void pause() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void stop() {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.stop();
    }

    @Override
    public void prepareAsync() {
        if (mInternalPlayer == null)
            return;
        if (mMediaSource == null) return;
        if (mSpeedPlaybackParameters != null) {
            mInternalPlayer.setPlaybackParameters(mSpeedPlaybackParameters);
        }
        mPreparedNotified = false;
        mRenderingStartedNotified = false;
        mPrepareStartElapsedMs = SystemClock.elapsedRealtime();
        mIsPreparing = true;
        mInternalPlayer.setMediaSource(mMediaSource);
        mInternalPlayer.setPlayWhenReady(false);
        mInternalPlayer.prepare();
        schedulePrepareBufferCheck();
    }

    @Override
    public void reset() {
        if (mInternalPlayer != null) {
            mInternalPlayer.stop();
            mInternalPlayer.clearMediaItems();
            mInternalPlayer.setVideoSurface(null);
            mIsPreparing = false;
        }
        stopAdSkipLoop();
        resetAdDetectionState();
        mPreparedNotified = false;
        mRenderingStartedNotified = false;
        stopPrepareBufferCheck();
    }

    @Override
    public boolean isPlaying() {
        if (mInternalPlayer == null)
            return false;
        int state = mInternalPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mInternalPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long time) {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.seekTo(time);
    }

    @Override
    public void release() {
        if (mInternalPlayer != null) {
            mInternalPlayer.removeListener(this);
            mInternalPlayer.release();
            mInternalPlayer = null;
        }

        mIsPreparing = false;
        mPreparedNotified = false;
        mRenderingStartedNotified = false;
        stopPrepareBufferCheck();
        stopAdSkipLoop();
        resetAdDetectionState();
        mAdProbeExecutor.shutdownNow();
        mAdProbeConcurrentExecutor.shutdownNow();
        mSpeedPlaybackParameters = null;
    }

    @Override
    public long getCurrentPosition() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mInternalPlayer == null)
            return 0;
        long duration = mInternalPlayer.getDuration();
        return duration == C.TIME_UNSET || duration < 0 ? 0 : duration;
    }

    @Override
    public int getBufferedPercentage() {
        return mInternalPlayer == null ? 0 : mInternalPlayer.getBufferedPercentage();
    }

    @Override
    public void setSurface(Surface surface) {
        if (mInternalPlayer != null) {
            mInternalPlayer.setVideoSurface(surface);
        }
    }

    @Override
    public void setDisplay(SurfaceHolder holder) {
        if (holder == null)
            setSurface(null);
        else
            setSurface(holder.getSurface());
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (mInternalPlayer != null)
            mInternalPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public void setLooping(boolean isLooping) {
        if (mInternalPlayer != null)
            mInternalPlayer.setRepeatMode(isLooping ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
    }

    @Override
    public void setOptions() {
        // Delay autoplay until startup prebuffer threshold is reached.
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void setSpeed(float speed) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed);
        mSpeedPlaybackParameters = playbackParameters;
        if (mInternalPlayer != null) {
            mInternalPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    @Override
    public float getSpeed() {
        if (mSpeedPlaybackParameters != null) {
            return mSpeedPlaybackParameters.speed;
        }
        return 1f;
    }

    @Override
    public long getTcpSpeed() {
        // no support
        return 0;
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (!mIsPreparing && playbackState == Player.STATE_READY) {
            maybeEnsureAdSkipLoopRunning();
        }
        if (!mIsPreparing && playbackState == Player.STATE_READY && isCurrentSegmentPredictedAd()) {
            if (!mAdResolutionDetected) {
                mAdResolutionDetected = true;
                mConsecutiveAdSkipCount = 0;
                long currentIndex = findSegmentIndexByPosition(mInternalPlayer == null ? 0L : mInternalPlayer.getCurrentPosition());
                Log.i(AD_DETECT_TAG, "Predicted ad segment reached (idx=" + currentIndex + "), start skipping");
                showAdDetectToast("预探测命中疑似广告片段");
                startAdSkipLoop();
            }
        }
        if (!mIsPreparing && playbackState == Player.STATE_READY) {
            skipCurrentAdSegmentIfNeeded();
        }
        if (mPlayerEventListener == null) return;
        if (mIsPreparing) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                maybeStartAfterPreBuffer();
            }
            return;
        }
        switch (playbackState) {
            case Player.STATE_BUFFERING:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, getBufferedPercentage());
                break;
            case Player.STATE_READY:
                mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_END, getBufferedPercentage());
                break;
            case Player.STATE_ENDED:
                mPlayerEventListener.onCompletion();
                break;
        }
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (!isPlaying || mPlayerEventListener == null) {
            return;
        }
        maybeEnsureAdSkipLoopRunning();
        skipCurrentAdSegmentIfNeeded();
        if (!mRenderingStartedNotified) {
            mRenderingStartedNotified = true;
            mPlayerEventListener.onInfo(MEDIA_INFO_RENDERING_START, 0);
        }
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        maybeEnsureAdSkipLoopRunning();
        skipCurrentAdSegmentIfNeeded();
    }

    @Override
    public void onTimelineChanged(Timeline timeline, int reason) {
        refreshVodSegmentTimeline();
    }

    private void schedulePrepareBufferCheck() {
        mMainHandler.removeCallbacks(mPrepareBufferRunnable);
        mMainHandler.post(mPrepareBufferRunnable);
    }

    private void stopPrepareBufferCheck() {
        mMainHandler.removeCallbacks(mPrepareBufferRunnable);
    }

    private void notifyPreparedIfNeeded() {
        if (!mPreparedNotified && mPlayerEventListener != null) {
            mPlayerEventListener.onPrepared();
            mPreparedNotified = true;
        }
    }

    private void maybeStartAfterPreBuffer() {
        if (mInternalPlayer == null || !mIsPreparing) {
            return;
        }

        int bufferedPercent = getBufferedPercentage();
        long elapsedMs = SystemClock.elapsedRealtime() - mPrepareStartElapsedMs;
        long durationMs = mInternalPlayer.getDuration();
        boolean unknownDuration = durationMs <= 0;
        boolean reachPreBuffer = bufferedPercent >= EXO_PREBUFFER_START_PERCENT;
        boolean waitTimeout = elapsedMs >= EXO_PREBUFFER_MAX_WAIT_MS;

        if (reachPreBuffer || waitTimeout || unknownDuration) {
            notifyPreparedIfNeeded();
            mIsPreparing = false;
            stopPrepareBufferCheck();
            mInternalPlayer.setPlayWhenReady(true);
        } else if (mPlayerEventListener != null) {
            mPlayerEventListener.onInfo(MEDIA_INFO_BUFFERING_START, bufferedPercent);
        }
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        stopAdSkipLoop();
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onError();
        }
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        detectAdByResolution(videoSize.width, videoSize.height);
        if (mPlayerEventListener != null) {
            mPlayerEventListener.onVideoSizeChanged(videoSize.width, videoSize.height);
            if (videoSize.unappliedRotationDegrees > 0) {
                mPlayerEventListener.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, videoSize.unappliedRotationDegrees);
            }
        }
    }

    private void resetAdDetectionState() {
        mBaselineVideoWidth = -1;
        mBaselineVideoHeight = -1;
        mAdResolutionDetected = false;
        mVodSegments.clear();
        mIsVodPlaylist = false;
        mLastAdSkipElapsedMs = 0L;
        mConsecutiveAdSkipCount = 0;
        mAdLikeSegmentIndices.clear();
        mProbedSegmentIndices.clear();
        mAdSkipLoopRunning = false;
        mCurrentHeaders = null;
    }

    private void detectAdByResolution(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }

        if (mBaselineVideoWidth <= 0 || mBaselineVideoHeight <= 0) {
            mBaselineVideoWidth = width;
            mBaselineVideoHeight = height;
            Log.i(AD_DETECT_TAG, "Baseline resolution set: " + width + "x" + height);
            return;
        }

        float widthDelta = Math.abs(width - mBaselineVideoWidth) / (float) mBaselineVideoWidth;
        float heightDelta = Math.abs(height - mBaselineVideoHeight) / (float) mBaselineVideoHeight;
        boolean adLike = widthDelta >= AD_RESOLUTION_CHANGE_THRESHOLD || heightDelta >= AD_RESOLUTION_CHANGE_THRESHOLD;

        if (adLike) {
            if (!mAdResolutionDetected) {
                mAdResolutionDetected = true;
                mConsecutiveAdSkipCount = 0;
                Log.w(AD_DETECT_TAG, "Possible ad segment, resolution changed from "
                        + mBaselineVideoWidth + "x" + mBaselineVideoHeight
                        + " to " + width + "x" + height
                        + " (dw=" + widthDelta + ", dh=" + heightDelta + ")");
                showAdDetectToast("检测到疑似广告片段");
            }
            startAdSkipLoop();
            skipCurrentAdSegmentIfNeeded();
        } else if (mAdResolutionDetected) {
            mAdResolutionDetected = false;
            mConsecutiveAdSkipCount = 0;
            Log.i(AD_DETECT_TAG, "Resolution returned to baseline range at " + width + "x" + height);
            showAdDetectToast("画面已恢复正常");
            maybeEnsureAdSkipLoopRunning();
        }
    }

    private void startAdSkipLoop() {
        if (!shouldKeepAdSkipLoopRunning()) {
            return;
        }
        mAdSkipLoopRunning = true;
        mMainHandler.removeCallbacks(mAdSkipLoopRunnable);
        mMainHandler.post(mAdSkipLoopRunnable);
    }

    private void stopAdSkipLoop() {
        mAdSkipLoopRunning = false;
        mMainHandler.removeCallbacks(mAdSkipLoopRunnable);
    }

    private boolean shouldKeepAdSkipLoopRunning() {
        return mInternalPlayer != null
                && mIsVodPlaylist
                && !mVodSegments.isEmpty()
                && !mAdLikeSegmentIndices.isEmpty();
    }

    private void maybeEnsureAdSkipLoopRunning() {
        if (!mAdSkipLoopRunning && shouldKeepAdSkipLoopRunning()) {
            startAdSkipLoop();
        }
    }

    private void refreshVodSegmentTimeline() {
        if (mInternalPlayer == null) {
            return;
        }
        Object manifest = mInternalPlayer.getCurrentManifest();
        if (!(manifest instanceof HlsManifest)) {
            mVodSegments.clear();
            mIsVodPlaylist = false;
            return;
        }

        HlsMediaPlaylist mediaPlaylist = ((HlsManifest) manifest).mediaPlaylist;
        if (mediaPlaylist == null || mediaPlaylist.segments == null || mediaPlaylist.segments.isEmpty()) {
            mVodSegments.clear();
            mIsVodPlaylist = false;
            return;
        }

        mIsVodPlaylist = mediaPlaylist.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_VOD || mediaPlaylist.hasEndTag;
        if (!mIsVodPlaylist) {
            mVodSegments.clear();
            return;
        }

        mVodSegments.clear();
        for (int i = 0; i < mediaPlaylist.segments.size(); i++) {
            HlsMediaPlaylist.Segment segment = mediaPlaylist.segments.get(i);
            long startMs = C.usToMs(segment.relativeStartTimeUs);
            long durationMs = Math.max(1L, C.usToMs(segment.durationUs));
            String absoluteUrl = UriUtil.resolve(mediaPlaylist.baseUri, segment.url);
            mVodSegments.add(new SegmentWindow(i, startMs, durationMs, absoluteUrl));
        }

        scheduleSegmentPreProbe();
        maybeEnsureAdSkipLoopRunning();
    }

    private void scheduleSegmentPreProbe() {
        if (!AD_PREPROBE_ENABLED || !mIsVodPlaylist || mVodSegments.isEmpty()) {
            return;
        }
        Log.i(AD_DETECT_TAG, "Scheduling full-manifest segment pre-probe for " + mVodSegments.size() + " segments");
        
        final int totalSegments = mVodSegments.size();
        
        mAdProbeExecutor.execute(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < totalSegments; i++) {
                    if (mProbedSegmentIndices.contains(i)) {
                        continue;
                    }
                    SegmentWindow window = mVodSegments.get(i);
                    final int segmentIndex = i;
                    mAdProbeConcurrentExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            SegmentResolution sr = probeSegmentResolution(window.absoluteUrl, mCurrentHeaders);
                            if (sr == null) {
                                return;
                            }
                            mProbedSegmentIndices.add(segmentIndex);
                            if (mBaselineVideoWidth <= 0 || mBaselineVideoHeight <= 0) {
                                mBaselineVideoWidth = sr.width;
                                mBaselineVideoHeight = sr.height;
                                Log.i(AD_DETECT_TAG, "Preprobe baseline: " + sr.width + "x" + sr.height);
                                return;
                            }

                            float widthDelta = Math.abs(sr.width - mBaselineVideoWidth) / (float) mBaselineVideoWidth;
                            float heightDelta = Math.abs(sr.height - mBaselineVideoHeight) / (float) mBaselineVideoHeight;
                            boolean adLike = widthDelta >= AD_RESOLUTION_CHANGE_THRESHOLD || heightDelta >= AD_RESOLUTION_CHANGE_THRESHOLD;
                            if (adLike) {
                                mAdLikeSegmentIndices.add(segmentIndex);
                                Log.i(AD_DETECT_TAG, "Preprobe: segment[" + segmentIndex + "]=" + sr.width + "x" + sr.height + " [AD-LIKE]");
                            }
                        }
                    });
                }
            }
        });
    }

    private SegmentResolution probeSegmentResolution(String url, Map<String, String> headers) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        Request.Builder rb = new Request.Builder().url(url).get().addHeader("Range", "bytes=0-" + (AD_PREPROBE_MAX_BYTES - 1));
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    rb.header(entry.getKey(), entry.getValue());
                }
            }
        }
        try (Response response = mAdProbeHttpClient.newCall(rb.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            byte[] data = response.body().bytes();
            return extractResolutionFromAnnexB(data);
        } catch (Throwable th) {
            return null;
        }
    }

    private SegmentResolution extractResolutionFromAnnexB(byte[] data) {
        if (data == null || data.length < 16) {
            return null;
        }
        for (int i = 0; i < data.length - 6; i++) {
            int startCodeLen = 0;
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                startCodeLen = 3;
            } else if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                startCodeLen = 4;
            }
            if (startCodeLen == 0) {
                continue;
            }
            int nalOffset = i + startCodeLen;
            int nalType = data[nalOffset] & 0x1F;
            if (nalType == 7) {
                int next = findNextStartCode(data, nalOffset + 1);
                if (next < 0) {
                    next = data.length;
                }
                byte[] rbsp = unescapeRbsp(data, nalOffset + 1, next);
                if (rbsp == null || rbsp.length == 0) {
                    continue;
                }
                SegmentResolution sr = parseSpsResolution(rbsp);
                if (sr != null && sr.width > 0 && sr.height > 0) {
                    return sr;
                }
            }
        }
        return null;
    }

    private int findNextStartCode(byte[] data, int from) {
        for (int i = from; i < data.length - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || (data[i + 2] == 0 && data[i + 3] == 1))) {
                return i;
            }
        }
        return -1;
    }

    private byte[] unescapeRbsp(byte[] src, int start, int end) {
        if (start >= end) {
            return null;
        }
        byte[] out = new byte[end - start];
        int outPos = 0;
        int zeros = 0;
        for (int i = start; i < end; i++) {
            byte b = src[i];
            if (zeros == 2 && b == 0x03) {
                zeros = 0;
                continue;
            }
            out[outPos++] = b;
            if (b == 0) {
                zeros++;
            } else {
                zeros = 0;
            }
        }
        byte[] result = new byte[outPos];
        System.arraycopy(out, 0, result, 0, outPos);
        return result;
    }

    private SegmentResolution parseSpsResolution(byte[] rbsp) {
        BitReader br = new BitReader(rbsp);
        int profileIdc = br.readBits(8);
        br.readBits(8); // constraint flags + reserved
        br.readBits(8); // level_idc
        br.readUE(); // seq_parameter_set_id

        if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244
                || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118
                || profileIdc == 128 || profileIdc == 138 || profileIdc == 144) {
            int chromaFormatIdc = br.readUE();
            if (chromaFormatIdc == 3) {
                br.readBit();
            }
            br.readUE();
            br.readUE();
            br.readBit();
            if (br.readBit() == 1) {
                int count = chromaFormatIdc != 3 ? 8 : 12;
                for (int i = 0; i < count; i++) {
                    if (br.readBit() == 1) {
                        skipScalingList(br, i < 6 ? 16 : 64);
                    }
                }
            }
        }

        br.readUE();
        int picOrderCntType = br.readUE();
        if (picOrderCntType == 0) {
            br.readUE();
        } else if (picOrderCntType == 1) {
            br.readBit();
            br.readSE();
            br.readSE();
            int cnt = br.readUE();
            for (int i = 0; i < cnt; i++) {
                br.readSE();
            }
        }
        br.readUE();
        br.readBit();
        int picWidthInMbsMinus1 = br.readUE();
        int picHeightInMapUnitsMinus1 = br.readUE();
        int frameMbsOnlyFlag = br.readBit();
        if (frameMbsOnlyFlag == 0) {
            br.readBit();
        }
        br.readBit();
        int frameCropLeft = 0;
        int frameCropRight = 0;
        int frameCropTop = 0;
        int frameCropBottom = 0;
        if (br.readBit() == 1) {
            frameCropLeft = br.readUE();
            frameCropRight = br.readUE();
            frameCropTop = br.readUE();
            frameCropBottom = br.readUE();
        }

        int width = (picWidthInMbsMinus1 + 1) * 16;
        int height = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16;

        int cropUnitX = 1;
        int cropUnitY = 2 - frameMbsOnlyFlag;
        width -= (frameCropLeft + frameCropRight) * cropUnitX;
        height -= (frameCropTop + frameCropBottom) * cropUnitY;
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new SegmentResolution(width, height);
    }

    private void skipScalingList(BitReader br, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int i = 0; i < size; i++) {
            if (nextScale != 0) {
                int deltaScale = br.readSE();
                nextScale = (lastScale + deltaScale + 256) % 256;
            }
            lastScale = nextScale == 0 ? lastScale : nextScale;
        }
    }

    private boolean isCurrentSegmentPredictedAd() {
        if (!mIsVodPlaylist || mVodSegments.isEmpty() || mAdLikeSegmentIndices.isEmpty() || mInternalPlayer == null) {
            return false;
        }
        int index = findSegmentIndexByPosition(mInternalPlayer.getCurrentPosition());
        boolean isPredictedAd = index >= 0 && mAdLikeSegmentIndices.contains(index);
        if (isPredictedAd) {
            Log.d(AD_DETECT_TAG, "Current segment index=" + index + " is predicted ad");
        }
        return isPredictedAd;
    }

    private void skipCurrentAdSegmentIfNeeded() {
        if (!AD_SEGMENT_SKIP_ENABLED || mInternalPlayer == null) {
            return;
        }
        if (!mIsVodPlaylist || mVodSegments.isEmpty() || mAdLikeSegmentIndices.isEmpty()) {
            return;
        }
        if (mConsecutiveAdSkipCount >= AD_SKIP_MAX_CONSECUTIVE) {
            Log.w(AD_DETECT_TAG, "Skip stopped: reached max consecutive skips=" + AD_SKIP_MAX_CONSECUTIVE);
            showAdDetectToast("广告跳过次数达到上限");
            stopAdSkipLoop();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - mLastAdSkipElapsedMs < AD_SKIP_MIN_INTERVAL_MS) {
            return;
        }

        long currentPosMs = mInternalPlayer.getCurrentPosition();
        int currentIndex = findSegmentIndexByPosition(currentPosMs);
        if (currentIndex < 0) {
            return;
        }

        // Strategy 1: Already entered ad segment (fallback safety check)
        boolean shouldSkip = false;
        int skipStartIndex = currentIndex;
        if (mAdLikeSegmentIndices.contains(currentIndex)) {
            shouldSkip = true;
            Log.d(AD_DETECT_TAG, "Detected: already in ad segment idx=" + currentIndex);
        } else {
            // Strategy 2: Proactively seek before entering upcoming ad segment (lookahead)
            for (int i = currentIndex + 1; i < mVodSegments.size(); i++) {
                if (mAdLikeSegmentIndices.contains(i)) {
                    SegmentWindow adSegment = mVodSegments.get(i);
                    long timeUntilAd = adSegment.startMs - currentPosMs;
                    if (timeUntilAd > 0 && timeUntilAd <= AD_SKIP_LOOKAHEAD_MS) {
                        shouldSkip = true;
                        skipStartIndex = i;
                        Log.d(AD_DETECT_TAG, "Lookahead: ad segment idx=" + i + " in " + timeUntilAd + "ms, seek now");
                    }
                    break; // Only check first upcoming ad segment
                }
            }
        }

        if (!shouldSkip || skipStartIndex + 1 >= mVodSegments.size()) {
            return;
        }

        int nextSafeIndex = skipStartIndex + 1;
        while (nextSafeIndex < mVodSegments.size() && mAdLikeSegmentIndices.contains(nextSafeIndex)) {
            nextSafeIndex++;
        }
        if (nextSafeIndex >= mVodSegments.size()) {
            Log.w(AD_DETECT_TAG, "Skip ignored: no non-ad segment after index=" + skipStartIndex);
            return;
        }

        int stepSegments = Math.min(1 + (mConsecutiveAdSkipCount / 2), AD_SKIP_MAX_STEP_SEGMENTS);
        int targetIndex = Math.min(Math.max(skipStartIndex + stepSegments, nextSafeIndex), mVodSegments.size() - 1);
        if (targetIndex <= skipStartIndex) {
            return;
        }

        SegmentWindow nextSegment = mVodSegments.get(targetIndex);
        long targetMs = nextSegment.startMs + AD_SKIP_TARGET_OFFSET_MS;
        if (targetMs <= currentPosMs + 50L) {
            return;
        }

        mLastAdSkipElapsedMs = now;
        mConsecutiveAdSkipCount++;
        mInternalPlayer.seekTo(targetMs);
        Log.i(AD_DETECT_TAG, "Skip: idx[" + skipStartIndex + "]→[" + targetIndex + "]"
                + ", step=" + stepSegments
                + ", count=" + mConsecutiveAdSkipCount
                + ", ahead=" + (skipStartIndex != currentIndex));
        showAdDetectToast("已跳过疑似广告片段");
    }

    private int findSegmentIndexByPosition(long positionMs) {
        if (mVodSegments.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < mVodSegments.size(); i++) {
            SegmentWindow window = mVodSegments.get(i);
            long endMs = window.startMs + window.durationMs;
            if (positionMs >= window.startMs && positionMs < endMs) {
                return i;
            }
        }
        return mVodSegments.size() - 1;
    }

    private void showAdDetectToast(String message) {
        if (!AD_DETECT_TOAST_ENABLED || message == null || message.isEmpty()) {
            return;
        }
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(mAppContext, message, Toast.LENGTH_SHORT).show();
                } catch (Throwable th) {
                    Log.w(AD_DETECT_TAG, "Failed to show toast: " + th.getMessage());
                }
            }
        });
    }

    private static final class SegmentWindow {
        final int index;
        final long startMs;
        final long durationMs;
        final String absoluteUrl;

        SegmentWindow(int index, long startMs, long durationMs, String absoluteUrl) {
            this.index = index;
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.absoluteUrl = absoluteUrl;
        }
    }

    private static final class SegmentResolution {
        final int width;
        final int height;

        SegmentResolution(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private static final class BitReader {
        private final byte[] data;
        private int byteOffset;
        private int bitOffset;

        BitReader(byte[] data) {
            this.data = data;
        }

        int readBit() {
            if (byteOffset >= data.length) {
                return 0;
            }
            int bit = (data[byteOffset] >> (7 - bitOffset)) & 0x01;
            bitOffset++;
            if (bitOffset == 8) {
                bitOffset = 0;
                byteOffset++;
            }
            return bit;
        }

        int readBits(int n) {
            int val = 0;
            for (int i = 0; i < n; i++) {
                val = (val << 1) | readBit();
            }
            return val;
        }

        int readUE() {
            int zeros = 0;
            while (readBit() == 0 && zeros < 31) {
                zeros++;
            }
            int value = (1 << zeros) - 1;
            if (zeros > 0) {
                value += readBits(zeros);
            }
            return value;
        }

        int readSE() {
            int codeNum = readUE();
            int sign = ((codeNum & 1) == 0) ? -1 : 1;
            return sign * ((codeNum + 1) / 2);
        }
    }
}
