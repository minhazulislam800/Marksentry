package com.example.webviewwrapper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;

/**
 * Thin native WebView wrapper around a Google Apps Script web app.
 *
 * Design notes (see README for the full explanation):
 *  - The active URL lives in SharedPreferences, not hard-coded.
 *  - Only *static* WebView resources are ever cached, via WebView's normal
 *    HTTP cache (WebSettings.LOAD_DEFAULT). No custom data cache, no SQLite,
 *    no offline queue is created anywhere in this app.
 *  - Every time the active URL differs from the URL that was active last
 *    time the app loaded something, the WebView cache/history/storage for
 *    the previous web app is cleared before the new URL is loaded, so a
 *    freshly redeployed Apps Script app never gets confused by old assets.
 */
public class MainActivity extends Activity {

    public static final String PREFS_NAME = "app_prefs";
    public static final String KEY_ACTIVE_URL = "active_url";
    public static final String KEY_LAST_LOADED_URL = "last_loaded_url";
    public static final String DEFAULT_URL =
            "https://script.google.com/macros/s/AKfycbwhk7D9TI68hjE15PFQAQ78oCjNvkVkLJ0GNjSa496VXMeRw5JFflRpDbNkNiTEYRLG/exec";

    private WebView webView;
    private ProgressBar progressBar;
    private View errorView;
    private Button retryButton;

    private SharedPreferences prefs;
    private String urlLoadedInThisSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress_bar);
        errorView = findViewById(R.id.error_view);
        retryButton = findViewById(R.id.btn_retry);

        setupWebView();

        retryButton.setOnClickListener(v -> loadActiveUrl(false));

        // First launch of the process: load whatever is currently the active URL.
        loadActiveUrl(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The user may have just come back from Settings with a new URL.
        // Only act if it actually changed vs. what is on screen right now.
        String activeUrl = getActiveUrl();
        if (urlLoadedInThisSession != null && !urlLoadedInThisSession.equals(activeUrl)) {
            loadActiveUrl(false);
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setDomStorageEnabled(true); // Apps Script pages may use localStorage
        settings.setDatabaseEnabled(false);  // no need for WebSQL/local DB
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // Standard HTTP caching only: honors Cache-Control/ETag headers from the
        // server for static resources (CSS/JS/images). This is NOT a custom data
        // cache and never serves stale HTML/data when the server says not to.
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isWithinWebApp(uri)) {
                    return false; // let the WebView handle it
                }
                // Genuinely external link: open in the system browser.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    // No browser available; fall back to loading in-app rather than crashing.
                    return false;
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                showLoading();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                showContent();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400) {
                    showError();
                }
            }
        });
    }

    /**
     * A link is considered "part of the web app" (and stays in-app) if it shares
     * the host of the currently configured URL, or is a Google auth/redirect host
     * that Apps Script apps commonly bounce through.
     */
    private boolean isWithinWebApp(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        String activeHost = Uri.parse(getActiveUrl()).getHost();
        if (host.equalsIgnoreCase(activeHost)) return true;
        return host.endsWith("script.google.com")
                || host.endsWith("script.googleusercontent.com")
                || host.endsWith("accounts.google.com")
                || host.endsWith("docs.google.com")
                || host.endsWith("googleusercontent.com");
    }

    private String getActiveUrl() {
        return prefs.getString(KEY_ACTIVE_URL, DEFAULT_URL);
    }

    /**
     * Loads the active URL. If the active URL differs from the URL that was
     * loaded last time the app ran (persisted in KEY_LAST_LOADED_URL), this is
     * treated as a URL change: static WebView caches/history/storage from the
     * previous web app are cleared first, then the new URL is loaded fresh.
     *
     * @param forceReload if true, reloads even when the URL has not changed
     *                    (used by pull-to-retry / manual Reload).
     */
    private void loadActiveUrl(boolean forceReload) {
        if (!isNetworkAvailable()) {
            showError();
            return;
        }

        String activeUrl = getActiveUrl();
        String lastLoadedUrl = prefs.getString(KEY_LAST_LOADED_URL, null);
        boolean urlChanged = lastLoadedUrl == null || !lastLoadedUrl.equals(activeUrl);

        if (urlChanged) {
            clearWebAppCache();
            prefs.edit().putString(KEY_LAST_LOADED_URL, activeUrl).apply();
        }

        urlLoadedInThisSession = activeUrl;

        if (urlChanged || forceReload) {
            webView.loadUrl(activeUrl);
        } else if (webView.getUrl() == null) {
            // First load in this Activity instance, URL unchanged from last run.
            webView.loadUrl(activeUrl);
        } else {
            showContent();
        }
    }

    /**
     * Clears only WebView-managed static resource caches, history, and site
     * storage. Does NOT touch SharedPreferences/app settings, and does not
     * exist to remove "application data" (there is none to remove, since no
     * live data is ever cached by this app in the first place).
     */
    private void clearWebAppCache() {
        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void showLoading() {
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void showContent() {
        progressBar.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private void showError() {
        progressBar.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        errorView.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (id == R.id.action_reload) {
            loadActiveUrl(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        // Belt-and-braces: make sure nothing lingers that could be mistaken
        // for a persisted application-data cache.
        webView.destroy();
        super.onDestroy();
    }
}
