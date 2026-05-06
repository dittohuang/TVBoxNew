package com.github.tvbox.osc.ui.dlna;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.dialog.BaseDialog;

import java.util.ArrayList;
import java.util.List;

public class DLNADeviceDialog extends BaseDialog {

    private OnDeviceSelectedListener deviceSelectedListener;
    private DeviceAdapter adapter;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;
    private DLNAManager.OnDeviceChangeListener deviceChangeListener;

    public interface OnDeviceSelectedListener {
        void onDeviceSelected(DLNADevice device);
    }

    public DLNADeviceDialog(@NonNull Context context) {
        super(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_dlna_device);

        TextView title = findViewById(R.id.title);
        title.setText("选择投屏设备");

        RecyclerView recyclerView = findViewById(R.id.device_list);
        adapter = new DeviceAdapter(new DiffUtil.ItemCallback<DLNADevice>() {
            @Override
            public boolean areItemsTheSame(@NonNull DLNADevice oldItem, @NonNull DLNADevice newItem) {
                return oldItem.getUuid().equals(newItem.getUuid());
            }

            @Override
            public boolean areContentsTheSame(@NonNull DLNADevice oldItem, @NonNull DLNADevice newItem) {
                return oldItem.getUuid().equals(newItem.getUuid());
            }
        });
        recyclerView.setAdapter(adapter);

        // 加载已有设备
        refreshDeviceList();

        // 监听设备变化
        deviceChangeListener = new DLNAManager.OnDeviceChangeListener() {
            @Override
            public void onDeviceAdded(DLNADevice device) {
                mainHandler.post(() -> refreshDeviceList());
            }

            @Override
            public void onDeviceRemoved(DLNADevice device) {
                mainHandler.post(() -> refreshDeviceList());
            }
        };
        DLNAManager.getInstance().setOnDeviceChangeListener(deviceChangeListener);

        // 定期刷新
        refreshRunnable = () -> {
            refreshDeviceList();
            mainHandler.postDelayed(refreshRunnable, 3000);
        };
        mainHandler.postDelayed(refreshRunnable, 3000);

        // 刷新按钮
        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            DLNAManager.getInstance().search();
        });
    }

    private void refreshDeviceList() {
        List<DLNADevice> devices = DLNAManager.getInstance().getDeviceList();
        adapter.submitList(new ArrayList<>(devices));

        // 更新空提示
        TextView emptyTip = findViewById(R.id.empty_tip);
        if (emptyTip != null) {
            emptyTip.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    public void setOnDeviceSelectedListener(OnDeviceSelectedListener listener) {
        this.deviceSelectedListener = listener;
    }

    @Override
    public void dismiss() {
        mainHandler.removeCallbacks(refreshRunnable);
        DLNAManager.getInstance().setOnDeviceChangeListener(null);
        super.dismiss();
    }

    private class DeviceAdapter extends ListAdapter<DLNADevice, DeviceViewHolder> {

        protected DeviceAdapter(@NonNull DiffUtil.ItemCallback<DLNADevice> diffCallback) {
            super(diffCallback);
        }

        @NonNull
        @Override
        public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView textView = new TextView(getContext());
            textView.setTextSize(16);
            textView.setTextColor(0xCC000000);
            textView.setPadding(40, 30, 40, 30);
            textView.setBackgroundResource(R.drawable.button_dialog_main);
            textView.setSingleLine(true);
            textView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textView.setFocusable(true);
            textView.setClickable(true);
            textView.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new DeviceViewHolder(textView);
        }

        @Override
        public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
            DLNADevice device = getItem(position);
            holder.textView.setText(device.getName());
            holder.textView.setOnClickListener(v -> {
                if (deviceSelectedListener != null) {
                    deviceSelectedListener.onDeviceSelected(device);
                }
                dismiss();
            });
        }
    }

    private static class DeviceViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        DeviceViewHolder(@NonNull TextView itemView) {
            super(itemView);
            this.textView = itemView;
        }
    }
}
