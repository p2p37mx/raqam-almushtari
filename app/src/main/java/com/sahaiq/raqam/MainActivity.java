package com.sahaiq.raqam;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;

public class MainActivity extends Activity {

    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private static final int REQ_PICK = 11;

    private WebView web;
    private long dlId = -1;
    private boolean rcvOn = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0f1a24"));
        setContentView(web);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        if (b != null) web.restoreState(b);
        else web.loadUrl(HOME);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.onResumeApp&&window.onResumeApp()", null);
    }

    @Override
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.onBack?window.onBack():false", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (rcvOn) { try { unregisterReceiver(dlDone); } catch (Exception ignored) {} }
        super.onDestroy();
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("ما لكيت تطبيق يفتح هذا الرابط");
        }
    }

    private void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    /* ===== اختيار اسم من جهات الاتصال ===== */
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        String name = "", num = "";
        try (Cursor c = getContentResolver().query(data.getData(), new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0) == null ? "" : c.getString(0);
                num = c.getString(1) == null ? "" : c.getString(1);
            }
        } catch (Exception e) {
            toast("ما كدرت أقرا جهة الاتصال");
            return;
        }
        web.evaluateJavascript("window.onContactPicked&&window.onContactPicked("
                + JSONObject.quote(name) + "," + JSONObject.quote(num) + ")", null);
    }

    /* ===== التحديث من داخل التطبيق ===== */
    private final BroadcastReceiver dlDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent in) {
            long id = in.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != dlId) return;
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            boolean ok = false;
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (c != null && c.moveToFirst()) {
                    int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    ok = st == DownloadManager.STATUS_SUCCESSFUL;
                }
            } catch (Exception ignored) {}
            if (!ok) { toast("فشل تنزيل التحديث — جرّب مرة ثانية"); return; }
            installApk();
        }
    };

    private File apkFile() {
        return new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "raqam-update.apk");
    }

    private void installApk() {
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("ما كدرت أفتح ملف التحديث");
        }
    }

    private void startDownload(String url) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast("فعّل «السماح من هذا المصدر» وارجع اضغط تحديث مرة ثانية");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        File f = apkFile();
        if (f.exists()) f.delete();
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        r.setTitle("تحديث رقم المشتري");
        r.setMimeType("application/vnd.android.package-archive");
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "raqam-update.apk");
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (!rcvOn) {
            ContextCompat.registerReceiver(this, dlDone,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED);
            rcvOn = true;
        }
        dlId = dm.enqueue(r);
    }

    /* ===== الجسر بين الواجهة والأندرويد ===== */
    class Bridge {
        @JavascriptInterface
        public void call(String phone) {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone))));
                } catch (Exception e) { toast("ما كدرت أفتح الاتصال"); }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) { runOnUiThread(() -> openExternal(url)); }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("رقم", text));
            });
        }

        @JavascriptInterface
        public void pickContact() {
            runOnUiThread(() -> {
                try {
                    startActivityForResult(new Intent(Intent.ACTION_PICK,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_PICK);
                } catch (Exception e) { toast("ما لكيت تطبيق جهات الاتصال"); }
            });
        }

        @JavascriptInterface
        public String versionCode() { return String.valueOf(BuildConfig.VERSION_CODE); }

        @JavascriptInterface
        public String versionName() { return BuildConfig.VERSION_NAME; }

        @JavascriptInterface
        public String appKey() {
            try {
                return new String(android.util.Base64.decode(BuildConfig.APP_KEY_B64, android.util.Base64.DEFAULT), "UTF-8");
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public String deviceName() {
            String n = null;
            try { n = Settings.Global.getString(getContentResolver(), "device_name"); } catch (Exception ignored) {}
            if (n == null || n.isEmpty()) n = Build.MANUFACTURER + " " + Build.MODEL;
            return n;
        }

        @JavascriptInterface
        public void downloadUpdate(String url) { runOnUiThread(() -> startDownload(url)); }

        @JavascriptInterface
        public void setBars(int dark) {
            runOnUiThread(() -> {
                getWindow().setNavigationBarColor(Color.parseColor(dark == 1 ? "#0c141c" : "#0f1a24"));
                View d = getWindow().getDecorView();
                d.setSystemUiVisibility(0);
            });
        }

        @JavascriptInterface
        public void toast(String m) { MainActivity.this.toast(m); }
    }
}
