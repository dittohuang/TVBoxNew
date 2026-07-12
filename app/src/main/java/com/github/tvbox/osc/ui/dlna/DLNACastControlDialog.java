package com.github.tvbox.osc.ui.dlna;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.dialog.BaseDialog;

public class DLNACastControlDialog extends BaseDialog {

    private DLNAPlayer dlnaPlayer;
    private OnStopCastListener onStopCastListener;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable positionUpdateRunnable;
    private TextView tvPosition;
    private TextView tvDuration;
    private TextView tvTitle;
    private TextView tvDeviceName;

    public interface OnStopCastListener {
        void onStopCast();
    }

    public DLNACastControlDialog(@NonNull Context context, DLNAPlayer dlnaPlayer) {
        super(context);
        this.dlnaPlayer = dlnaPlayer;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_dlna_cast_control);

        tvTitle = findViewById(R.id.tv_cast_title);
        tvDeviceName = findViewById(R.id.tv_cast_device);
        tvPosition = findViewById(R.id.tv_cast_position);
        tvDuration = findViewById(R.id.tv_cast_duration);

        // 播放/暂停
        findViewById(R.id.btn_cast_play_pause).setOnClickListener(v -> {
            if (dlnaPlayer != null) {
                if (dlnaPlayer.isPlaying()) {
                    dlnaPlayer.pause();
                } else {
                    dlnaPlayer.resume();
                }
            }
        });

        // 停止投屏
        findViewById(R.id.btn_cast_stop).setOnClickListener(v -> {
            if (dlnaPlayer != null) {
                dlnaPlayer.stop();
            }
            if (onStopCastListener != null) {
                onStopCastListener.onStopCast();
            }
            dismiss();
        });

        // 快退15秒
        findViewById(R.id.btn_cast_backward).setOnClickListener(v -> {
            if (dlnaPlayer != null && tvPosition != null) {
                try {
                    long currentPos = parseTime(tvPosition.getText().toString());
                    long seekPos = Math.max(0, currentPos - 15);
                    dlnaPlayer.seek(formatTime(seekPos));
                } catch (Exception ignored) {
                }
            }
        });

        // 快进15秒
        findViewById(R.id.btn_cast_forward).setOnClickListener(v -> {
            if (dlnaPlayer != null && tvPosition != null && tvDuration != null) {
                try {
                    long currentPos = parseTime(tvPosition.getText().toString());
                    long dur = parseTime(tvDuration.getText().toString());
                    long seekPos = Math.min(dur, currentPos + 15);
                    dlnaPlayer.seek(formatTime(seekPos));
                } catch (Exception ignored) {
                }
            }
        });

        // 设置DLNA状态监听
        if (dlnaPlayer != null) {
            dlnaPlayer.setStateListener(new DLNAPlayer.OnDLNAStateListener() {
                @Override
                public void onConnected(DLNADevice device) {}

                @Override
                public void onDisconnected() {}

                @Override
                public void onPlay() {
                    mainHandler.post(() -> {
                        TextView btn = findViewById(R.id.btn_cast_play_pause);
                        if (btn != null) btn.setText("暂停");
                    });
                }

                @Override
                public void onPause() {
                    mainHandler.post(() -> {
                        TextView btn = findViewById(R.id.btn_cast_play_pause);
                        if (btn != null) btn.setText("播放");
                    });
                }

                @Override
                public void onStop() {}

                @Override
                public void onError(String errorMsg) {
                    mainHandler.post(() -> Toast.makeText(getContext(),
                            errorMsg == null ? "投屏失败" : errorMsg,
                            Toast.LENGTH_LONG).show());
                }

                @Override
                public void onPositionUpdate(long position, long duration) {
                    mainHandler.post(() -> {
                        if (tvPosition != null) tvPosition.setText(formatTime(position));
                        if (tvDuration != null) tvDuration.setText(formatTime(duration));
                    });
                }
            });
        }

        // 定期获取播放进度
        positionUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (dlnaPlayer != null) {
                    dlnaPlayer.getPositionInfo();
                }
                mainHandler.postDelayed(this, 2000);
            }
        };
        mainHandler.postDelayed(positionUpdateRunnable, 2000);
    }

    public void setOnStopCastListener(OnStopCastListener listener) {
        this.onStopCastListener = listener;
    }

    public void setTitle(String title) {
        if (tvTitle != null) {
            tvTitle.setText(title);
        }
    }

    public void setDeviceName(String name) {
        if (tvDeviceName != null) {
            tvDeviceName.setText(name);
        }
    }

    @Override
    public void dismiss() {
        mainHandler.removeCallbacks(positionUpdateRunnable);
        super.dismiss();
    }

    private String formatTime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    private long parseTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        String[] parts = timeStr.split(":");
        try {
            if (parts.length == 3) {
                return Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
            } else if (parts.length == 2) {
                return Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }
}
