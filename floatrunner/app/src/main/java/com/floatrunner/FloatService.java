package com.floatrunner;

import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;

public class FloatService extends Service {

    private WindowManager wm;
    private View v;
    private WindowManager.LayoutParams p;
    private float sx, sy, ix, iy;
    private boolean drag = false;

    private EditText input, nameIn, cmdIn;
    private TextView output;
    private ScrollView scroll;
    private ListView list;
    private CmdAdapter adapter;
    private ArrayList<CmdItem> items = new ArrayList<>();

    private static final String PREFS = "cmds", KEY = "cnt";

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        load();
        create();
    }

    @Override
    public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    @Override
    public IBinder onBind(Intent i) { return null; }

    private void create() {
        v = LayoutInflater.from(this).inflate(R.layout.float_layout, null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC222222);
        bg.setCornerRadius(18);
        v.setBackground(bg);

        input = v.findViewById(R.id.input_cmd);
        output = v.findViewById(R.id.output_text);
        scroll = v.findViewById(R.id.output_scroll);
        nameIn = v.findViewById(R.id.cmd_name_input);
        cmdIn = v.findViewById(R.id.cmd_content_input);
        list = v.findViewById(R.id.cmd_list);

        output.setOnClickListener(vv -> {
            String t = output.getText().toString();
            if (t.length() > 0) {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("o", t));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });

        View.OnClickListener kb = vv -> showKeyboard((EditText) vv);
        input.setOnClickListener(kb);
        nameIn.setOnClickListener(kb);
        cmdIn.setOnClickListener(kb);

        input.setOnEditorActionListener((vv, a, e) -> {
            if (a == EditorInfo.IME_ACTION_GO || a == EditorInfo.IME_ACTION_DONE ||
                    (e != null && e.getKeyCode() == 66 && e.getAction() == 0)) { exec(); return true; }
            return false;
        });

        cmdIn.setOnEditorActionListener((vv, a, e) -> {
            if (a == EditorInfo.IME_ACTION_DONE) { save(); return true; }
            return false;
        });

        v.findViewById(R.id.btn_exec).setOnClickListener(vv -> exec());
        v.findViewById(R.id.btn_save_cmd).setOnClickListener(vv -> save());

        adapter = new CmdAdapter();
        list.setAdapter(adapter);

        v.findViewById(R.id.btn_close).setOnClickListener(vv -> stopSelf());

        v.findViewById(R.id.drag_handle).setOnTouchListener((vv, e) -> {
            switch (e.getAction()) {
                case 0:
                    hideKeyboard(); drag = false;
                    sx = e.getRawX(); sy = e.getRawY(); ix = p.x; iy = p.y;
                    return true;
                case 2:
                    float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) drag = true;
                    p.x = (int) (ix + dx); p.y = (int) (iy + dy);
                    wm.updateViewLayout(v, p);
                    return true;
                case 1: return drag;
            }
            return false;
        });

        p = new WindowManager.LayoutParams();
        p.type = Build.VERSION.SDK_INT >= 26 ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;
        p.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        p.format = PixelFormat.TRANSLUCENT;
        p.gravity = Gravity.TOP | Gravity.START;

        int sw = getResources().getDisplayMetrics().widthPixels;
        p.width = (int) (sw * 0.5f);
        p.height = WindowManager.LayoutParams.WRAP_CONTENT;
        p.x = sw - p.width - 20;
        p.y = getResources().getDisplayMetrics().heightPixels / 3;

        wm.addView(v, p);
    }

    private void showKeyboard(EditText et) {
        et.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View f = v.findFocus();
        if (f != null) { f.clearFocus(); imm.hideSoftInputFromWindow(f.getWindowToken(), 0); }
    }

    private void exec() {
        String cmd = input.getText().toString().trim();
        if (cmd.length() == 0) return;
        hideKeyboard();
        try {
            if (rikka.shizuku.Shizuku.checkSelfPermission()
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 未授权", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show();
            return;
        }
        new ExecThread(cmd).start();
    }

    private class ExecThread extends Thread {
        String c;
        ExecThread(String c) { this.c = c; }
        @Override
        public void run() {
            try {
                long t = System.currentTimeMillis();
                Process proc = ShizukuShell.exec("sh");
                if (proc == null) { post("Shizuku 执行失败"); return; }
                OutputStream os = proc.getOutputStream();
                os.write((c + "\nexit\n").getBytes()); os.flush(); os.close();
                StringBuilder sb = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String l;
                while ((l = r.readLine()) != null) { if (sb.length() > 4000) break; sb.append(l).append("\n"); }
                r.close();
                BufferedReader er = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
                while ((l = er.readLine()) != null) { if (sb.length() > 4000) break; sb.append("[err] ").append(l).append("\n"); }
                er.close();
                proc.waitFor();
                int ev = proc.exitValue();
                String res = sb.toString().trim();
                long el = System.currentTimeMillis() - t;
                post(() -> {
                    output.setText(res.length() > 0 ? res : "(无输出)");
                    output.append("\n\n--- 返回值: " + ev + " | " + String.format("%.2f秒", el / 1000f) + " ---");
                    scroll.fullScroll(ScrollView.FOCUS_DOWN);
                    input.setText("");
                });
            } catch (Exception e) { post("执行出错: " + e.getMessage()); }
        }
        void post(String s) { new Handler(Looper.getMainLooper()).post(() -> output.setText(s)); }
        void post(Runnable r) { new Handler(Looper.getMainLooper()).post(r); }
    }

    // 命令存储
    static class CmdItem { int id; String name, content; CmdItem(int i, String n, String c) { id = i; name = n; content = c; } }

    private void load() {
        SharedPreferences sp = getSharedPreferences(PREFS, 0);
        int n = sp.getInt(KEY, 0);
        for (int i = 0; i < n; i++) {
            String na = sp.getString("n_" + i, ""), co = sp.getString("c_" + i, "");
            if (co.length() > 0) items.add(new CmdItem(i, na.length() > 0 ? na : co, co));
        }
    }

    private void save() {
        String n = nameIn.getText().toString().trim(), c = cmdIn.getText().toString().trim();
        if (c.length() == 0) { Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show(); return; }
        items.add(new CmdItem(items.size(), n.length() > 0 ? n : c, c));
        persist();
        adapter.notifyDataSetChanged();
        nameIn.setText(""); cmdIn.setText("");
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private void persist() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, 0).edit();
        e.putInt(KEY, items.size());
        for (int i = 0; i < items.size(); i++) {
            e.putString("n_" + i, items.get(i).name);
            e.putString("c_" + i, items.get(i).content);
        }
        e.apply();
    }

    // 列表适配器
    private class CmdAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override
        public View getView(int pos, View cv, ViewGroup p) {
            if (cv == null) cv = LayoutInflater.from(FloatService.this).inflate(R.layout.cmd_item, null);
            CmdItem item = items.get(pos);
            ((TextView) cv.findViewById(R.id.cmd_name)).setText(item.name);
            ((TextView) cv.findViewById(R.id.cmd_content)).setText(item.content);
            ImageButton del = cv.findViewById(R.id.btn_delete);
            cv.setOnClickListener(v -> { input.setText(item.content); exec(); });
            del.setOnClickListener(v -> { items.remove(pos); persist(); notifyDataSetChanged(); });
            return cv;
        }
    }

    @Override
    public void onDestroy() {
        if (v != null && wm != null) { try { wm.removeView(v); } catch (Exception ignored) {} }
        super.onDestroy();
    }
}
