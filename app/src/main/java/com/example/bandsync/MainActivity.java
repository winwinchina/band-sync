package com.example.bandsync;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * BandSync v0.1 —— 诊断版最小 BLE 客户端
 * 目标：确认 Z06 是否跑 Veepoo/S226 协议；能读到数据就写成 CSV。
 * 前提：使用Veepoo方案的F008服务。若扫不到该服务，日志会明确提示“非Veepoo”。
 */
public class MainActivity extends Activity {

    // ===== 协议常量（已联网核对：两个独立开源源一致）=====
    static final UUID UUID_SERVICE = UUID.fromString("f0080001-0451-4000-b000-000000000000");
    static final UUID UUID_NOTIFY  = UUID.fromString("f0080002-0451-4000-b000-000000000000"); // 设备->手机
    static final UUID UUID_WRITE   = UUID.fromString("f0080003-0451-4000-b000-000000000000"); // 手机->设备
    static final UUID UUID_CCCD    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    TextView txtDevices, txtLog;
    BluetoothAdapter btAdapter;
    BluetoothLeScanner scanner;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic writeChar, notifyChar;
    Handler handler = new Handler(Looper.getMainLooper());

    final List<ScanResult> found = new ArrayList<>();
    // 本次同步收集到的所有 notify 原始包（hex）
    final List<String> notifyPackets = new ArrayList<>();
    StringBuilder logBuf = new StringBuilder();
    String deviceName = "unknown";

    static final int REQ_PERM = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        txtDevices = findViewById(R.id.txtDevices);
        txtLog = findViewById(R.id.txtLog);

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = bm != null ? bm.getAdapter() : null;
        if (btAdapter == null) {
            log("❌ 本设备无蓝牙");
        }

        findViewById(R.id.btnScan).setOnClickListener(v -> {
            if (!ensurePerm()) return;
            startScan();
        });
        findViewById(R.id.btnSync).setOnClickListener(v -> {
            if (!ensurePerm()) return;
            connectBestAndSync();
        });
        findViewById(R.id.btnCopy).setOnClickListener(v -> {
            File csvFile = csvPath();
            toast("CSV 目录：" + csvFile.getParent());
            log("CSV 完整路径：" + csvFile.getAbsolutePath());
        });

        log("准备就绪。第一步先点「1 扫描」。");
    }

    // ---------- 权限 ----------
    boolean ensurePerm() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (need.isEmpty()) return true;
        requestPermissions(need.toArray(new String[0]), REQ_PERM);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grant) {
        super.onRequestPermissionsResult(req, perms, grant);
        if (req == REQ_PERM) {
            boolean ok = grant.length > 0;
            for (int g : grant) if (g != PackageManager.PERMISSION_GRANTED) ok = false;
            log(ok ? "✅ 权限已授予" : "❌ 权限被拒，无法扫描 BLE");
        }
    }

    // ---------- 扫描 ----------
    void startScan() {
        if (btAdapter == null || !btAdapter.isEnabled()) {
            toast("请先打开手机蓝牙");
            return;
        }
        found.clear();
        updateDeviceList("扫描中…");
        log("---- 开始扫描 10 秒 ----");
        scanner = btAdapter.getBluetoothLeScanner();
        if (scanner == null) { log("❌ 无法获取 BLE scanner"); return; }
        scanner.startScan(scanCb);
        handler.postDelayed(() -> {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
            log("---- 扫描结束，共 " + found.size() + " 台 ----");
            renderDevices();
        }, 10000);
    }

    ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int cbType, ScanResult r) {
            // 去重：按地址
            for (ScanResult e : found) {
                if (e.getDevice().getAddress().equals(r.getDevice().getAddress())) return;
            }
            found.add(r);
        }

        @Override
        public void onScanFailed(int err) {
            log("❌ 扫描失败 code=" + err);
        }
    };

    void renderDevices() {
        if (found.isEmpty()) {
            updateDeviceList("没扫到设备。确认：①蓝牙开 ②定位/附近设备权限给了 ③手表唤醒（点亮屏幕）");
            return;
        }
        // 按 RSSI 从强到弱排
        found.sort((a, b) -> Integer.compare(b.getRssi(), a.getRssi()));
        StringBuilder sb = new StringBuilder("设备列表（信号强→弱）：\n");
        for (int i = 0; i < found.size(); i++) {
            BluetoothDevice d = found.get(i).getDevice();
            String name = d.getName();
            if (name == null || name.isEmpty()) name = "(无名)";
            sb.append(i).append(". ").append(name)
              .append("  [").append(found.get(i).getRssi()).append(" dBm]\n");
        }
        sb.append("\n记下你手表对应的序号/名字，代码会优先连信号最强且像手表的设备。");
        updateDeviceList(sb.toString());
    }

    // ---------- 连接 + 同步 ----------
    void connectBestAndSync() {
        if (found.isEmpty()) {
            toast("请先点「1 扫描」");
            return;
        }
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
        notifyPackets.clear();

        // 优先选名字像手表的，否则选信号最强
        int pick = 0;
        for (int i = 0; i < found.size(); i++) {
            String n = String.valueOf(found.get(i).getDevice().getName()).toUpperCase(Locale.US);
            if (n.contains("HBAND") || n.contains("VEEPOO") || n.contains("FB084")
                    || n.contains("S226") || n.contains("WATCH") || n.contains("BAND")) {
                pick = i; break;
            }
        }
        BluetoothDevice dev = found.get(pick).getDevice();
        deviceName = dev.getName() == null ? "unknown" : dev.getName();
        log("---- 连接：" + deviceName + " (" + dev.getAddress() + ") ----");
        dev.connectGatt(this, false, gattCb);
    }

    BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("✅ BLE 已连接，开始找服务…");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("已断开 status=" + status);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { log("❌ 发现服务失败 status=" + status); return; }
            boolean hasF008 = false;
            for (BluetoothGattService s : g.getServices()) {
                log("发现服务 UUID=" + s.getUuid());
                if (s.getUuid().equals(UUID_SERVICE)) hasF008 = true;
            }
            if (!hasF008) {
                log("⚠️ 没有 F008 服务 —— 这块表大概率不是 Veepoo/S226 协议，本客户端不适用。");
                log("   请把上面的服务 UUID 列表发给 AI，换对应协议再写。");
                g.disconnect();
                return;
            }
            log("✅ 找到 F008 服务，判定：Veepoo 系，协议对上了。");

            BluetoothGattService svc = g.getService(UUID_SERVICE);
            writeChar = svc.getCharacteristic(UUID_WRITE);
            notifyChar = svc.getCharacteristic(UUID_NOTIFY);
            if (writeChar == null || notifyChar == null) {
                log("❌ F008 服务里没找到 write/notify 特征");
                return;
            }

            // 打印特征属性，帮助判断 write/notify 是否选反
            log("写特征属性=" + writeChar.getProperties() + "，通知特征属性=" + notifyChar.getProperties());

            // 开通知：先本地允许，再写 CCCD。必须等 onDescriptorWrite 回调成功后再发命令，
            // 否则订阅未生效，设备即使回了数据也不会触发 notify。
            boolean localOk = g.setCharacteristicNotification(notifyChar, true);
            log("setCharacteristicNotification=" + localOk);
            BluetoothGattDescriptor cccd = notifyChar.getDescriptor(UUID_CCCD);
            log("CCCD descriptor=" + (cccd == null ? "不存在" : "存在"));
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean wr = g.writeDescriptor(cccd);
                log("writeDescriptor 发起=" + wr + "（真正启用要等 onDescriptorWrite 回调）");
                if (!wr) log("❌ CCCD 写入发起失败，notify 不会通");
            } else {
                log("⚠️ 通知特征没有 CCCD —— 可能特征选反了，兜底对写特征开通知");
                g.setCharacteristicNotification(writeChar, true);
                BluetoothGattDescriptor d2 = writeChar.getDescriptor(UUID_CCCD);
                if (d2 != null) {
                    d2.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    log("兜底 writeDescriptor(写特征CCCD)=" + g.writeDescriptor(d2));
                } else {
                    log("❌ 两个特征都没有 CCCD，无法启用 notify");
                }
            }
            // 命令不在此时发，改到 onDescriptorWrite 回调里发
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            boolean ok = (status == BluetoothGatt.GATT_SUCCESS);
            log("onDescriptorWrite status=" + status + (ok ? " ✅ notify 已真正启用" : " ❌ notify 启用失败"));
            startCommands();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            log("命令写入回调 status=" + status + " 特征=" + c.getUuid()
                    + (status == BluetoothGatt.GATT_SUCCESS ? " ✅" : " ❌"));
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            String hex = hex(v);
            log("◀ NOTIFY " + hex);
            notifyPackets.add(hex);
            parseNotify(v);
        }
    };

    // ---------- 命令 ----------
    void startCommands() {
        log("---- 订阅已处理，开始发命令（Write Command 无应答模式）----");
        handler.postDelayed(() -> cmdHandshake(), 400);
        handler.postDelayed(() -> cmd("A0", "读电量"), 1300);
        handler.postDelayed(() -> cmd("A8", "读步数A8"), 2100);
        handler.postDelayed(() -> cmd("D8", "读今日统计D8"), 2900);
        handler.postDelayed(() -> finishSync(), 5500);
    }

    void cmdHandshake() {
        byte[] p = new byte[20];
        p[0] = (byte) 0xA1;
        // [1][2] 密码 0000 -> 00 00 ; [3] type=0（校验+设时间）
        Calendar c = Calendar.getInstance();
        int year = c.get(Calendar.YEAR);           // 2026
        p[4] = (byte) ((year >> 8) & 0xFF);        // 0x07
        p[5] = (byte) (year & 0xFF);               // 0xEA
        p[6] = (byte) (c.get(Calendar.MONTH) + 1);
        p[7] = (byte) c.get(Calendar.DAY_OF_MONTH);
        p[8] = (byte) c.get(Calendar.HOUR_OF_DAY);
        p[9] = (byte) c.get(Calendar.MINUTE);
        p[10] = (byte) c.get(Calendar.SECOND);
        p[11] = 0x01;                              // 24 小时制
        p[12] = 0x01;
        p[13] = 0x04;                              // profile byte：H-Band 抓包原文为 01 01 04，固件可能需要
        log("▶ 握手 " + hex(p));
        writeRaw(p);
    }

    void cmd(String hexHead, String label) {
        byte[] p = new byte[20];
        p[0] = (byte) Integer.parseInt(hexHead, 16);
        log("▶ " + label + " " + hexHead + "00…");
        writeRaw(p);
    }

    void writeRaw(byte[] p) {
        if (gatt == null || writeChar == null) { log("⚠️ 写跳过：gatt/写特征为空"); return; }
        // 关键修复：Veepoo 协议的命令走 Write Command（NO_RESPONSE），不是需要应答的 Write Request。
        // 设备不会对 Write Request 回 response，导致 onCharacteristicWrite 永不回调、也不回数据。
        writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        writeChar.setValue(p);
        boolean ok = gatt.writeCharacteristic(writeChar);
        log("   写命令发起 ok=" + ok + " writeType=" + writeChar.getWriteType() + "(1=NO_RESPONSE)");
        if (!ok) log("⚠️ 写特征失败（可能上次写未完成）");
    }

    // ---------- 解析回包 ----------
    void parseNotify(byte[] v) {
        if (v.length < 1) return;
        int head = v[0] & 0xFF;
        if (head == 0xA1) {
            int status = v.length > 3 ? (v[3] & 0xFF) : -1;
            String[] st = {"校验失败", "校验成功", "设置失败", "设置成功", "读取失败", "读取成功", "校验+时间成功"};
            String s = (status >= 0 && status < st.length) ? st[status] : ("未知status=" + status);
            log("   ↳ 握手状态：" + s + (status == 1 || status == 3 || status == 6 ? " ✅ 认证通过" : ""));
        } else if (head == 0xA0) {
            // 两种说法都解析：源1 [4]=0..4 档；源2 [2]=0x80|百分比
            int lvl = v.length > 4 ? (v[4] & 0xFF) : -1;
            int b2 = v.length > 2 ? (v[2] & 0xFF) : 0;
            int pct = (b2 & 0x80) != 0 ? (b2 & 0x7F) : -1;
            log("   ↳ 电量：档位=" + lvl + " / 百分比=" + (pct >= 0 ? pct + "%" : "n/a") + " (raw[2]=" + b2 + ")");
        } else if (head == 0xA8) {
            long steps = be32(v, 1);
            if (steps == 0xFFFFFFFFL) steps = 0;
            log("   ↳ 步数A8=" + steps + (steps == 0 ? "（0 或该命令不适用）" : " ✅"));
        } else if (head == 0xD8) {
            log("   ↳ D8 今日统计原始包（布局待抓包确认）：" + hex(v));
        }
    }

    long be32(byte[] v, int off) {
        if (v.length < off + 4) return 0;
        return ((long)(v[off] & 0xFF) << 24) | ((long)(v[off+1] & 0xFF) << 16)
                | ((long)(v[off+2] & 0xFF) << 8) | (long)(v[off+3] & 0xFF);
    }

    // ---------- 收尾：写 CSV ----------
    void finishSync() {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        StringBuilder rawSb = new StringBuilder();
        for (int i = 0; i < notifyPackets.size(); i++) {
            if (i > 0) rawSb.append("|");
            rawSb.append(notifyPackets.get(i));
        }
        String raw = rawSb.toString();
        // 结构化 CSV：一行一次同步；raw 列保留诊断
        String row = ts + "," + esc(deviceName) + "," + notifyPackets.size() + "," + esc(raw) + "\n";
        try {
            File f = csvPath();
            f.getParentFile().mkdirs();
            boolean newFile = !f.exists();
            FileOutputStream fos = new FileOutputStream(f, true);
            if (newFile) fos.write("timestamp,device,notify_count,raw_notify\n".getBytes("UTF-8"));
            fos.write(row.getBytes("UTF-8"));
            fos.close();
            log("✅ CSV 已写入：" + f.getAbsolutePath());
            toast("同步完成，CSV 已保存");
        } catch (IOException e) {
            log("❌ 写 CSV 失败：" + e.getMessage());
        }
        if (notifyPackets.isEmpty()) {
            log("⚠️ 没收到任何 notify —— 对照诊断：");
            log("   ① onDescriptorWrite status=0 ✅ = 订阅成功");
            log("   ② 命令写入回调 status=0 ✅ = 命令写出去了（本次已改 Write Command 无应答模式）");
            log("   ③ 写命令发起 ok=true = 写入动作成功入队");
            log("   ④ 若三项都✅仍无回包：点亮手表屏幕重试；仍不行则该表需密码握手包 profile 字节再调");
        }
        if (gatt != null) { gatt.disconnect(); gatt.close(); gatt = null; }
    }

    String esc(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    File csvPath() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, "bandsync.csv");
    }

    // ---------- UI 工具 ----------
    void log(String s) {
        logBuf.append(s).append("\n");
        runOnUiThread(() -> txtLog.setText(logBuf.toString()));
    }
    void updateDeviceList(String s) { runOnUiThread(() -> txtDevices.setText(s)); }
    void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x & 0xFF));
        return sb.toString().trim();
    }
}
