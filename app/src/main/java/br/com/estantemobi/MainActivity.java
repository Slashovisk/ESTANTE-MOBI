package br.com.estantemobi;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Estante MOBI: uma WebView com a interface do leitor (assets/www/index.html) e uma ponte
 * "Android" que guarda os livros na memória interna do app (files/books/).
 *
 * Cada livro vira dois arquivos: <id>.bin (o .mobi original) e <id>.json (título, autor,
 * capa em miniatura, progresso). A página lê o livro em https://appassets.androidplatform.net/books/<id>.bin.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_PICK = 4101;
    private static final long MAX_BYTES = 300L * 1024 * 1024;

    private WebView web;
    private FrameLayout root;
    private File booksDir;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object metaLock = new Object();
    private final List<String> pendingJs = new ArrayList<>();
    private volatile boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        booksDir = new File(getFilesDir(), "books");
        //noinspection ResultOfMethodCallIgnored
        booksDir.mkdirs();

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#FBFAF7"));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        setupEdgeToEdge();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serve(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // links externos dentro de um livro abrem no navegador
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.loadUrl(START_URL);
        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // pede para a página salvar a posição de leitura
        if (pageReady) web.evaluateJavascript("window.dispatchEvent(new Event('pagehide'))", null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!pageReady) { super.onBackPressed(); return; }
        web.evaluateJavascript("(window.appBack && window.appBack()) ? '1' : '0'", value -> {
            if (!"\"1\"".equals(value)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- barras do sistema

    @SuppressWarnings("deprecation")
    private void setupEdgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                l = bars.left; t = bars.top; r = bars.right; b = Math.max(bars.bottom, ime.bottom);
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
    }

    @SuppressWarnings("deprecation")
    private void applyBars(int color, boolean dark) {
        root.setBackgroundColor(color);
        Window w = getWindow();
        if (Build.VERSION.SDK_INT < 35) {
            w.setStatusBarColor(color);
            w.setNavigationBarColor(color);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else {
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) light |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(dark ? (flags & ~light) : (flags | light));
        }
    }

    // ---------------------------------------------------------------- servidor local da WebView

    private WebResourceResponse serve(Uri u) {
        if (!HOST.equals(u.getHost())) return null; // fontes do Google etc. vão para a rede normalmente
        String path = u.getPath();
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) return notFound();
        try {
            if (path.startsWith("/books/")) {
                String name = path.substring("/books/".length());
                if (!name.matches("[A-Za-z0-9_-]{1,64}\\.bin")) return notFound();
                File f = new File(booksDir, name);
                if (!f.isFile()) return notFound();
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "no-store");
                h.put("Content-Length", String.valueOf(f.length()));
                return new WebResourceResponse("application/octet-stream", null, 200, "OK", h, new FileInputStream(f));
            }
            InputStream in = getAssets().open("www" + path);
            return new WebResourceResponse(mimeOf(path), "UTF-8", in);
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- chegada de arquivos

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri u = streamExtra(intent);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = streamListExtra(intent);
            if (list != null) uris.addAll(list);
        }
        boolean openAfter = uris.size() == 1;
        for (Uri u : uris) importUri(u, openAfter);
        // não importa de novo se a tela for recriada
        setIntent(new Intent(Intent.ACTION_MAIN));
    }

    @SuppressWarnings("deprecation")
    private static Uri streamExtra(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        Parcelable p = i.getParcelableExtra(Intent.EXTRA_STREAM);
        return p instanceof Uri ? (Uri) p : null;
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<Uri> streamListExtra(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) return i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        return i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        ClipData clip = data.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            for (int i = 0; i < clip.getItemCount(); i++) importUri(clip.getItemAt(i).getUri(), false);
        } else if (data.getData() != null) {
            importUri(data.getData(), false);
        }
    }

    @SuppressWarnings("deprecation")
    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            // aparelhos sem o seletor de documentos
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("*/*");
            g.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(Intent.createChooser(g, getString(R.string.pick_title)), REQ_PICK);
            } catch (Exception e2) {
                js("window.onImportError && window.onImportError(" + JSONObject.quote("Não há um seletor de arquivos disponível neste aparelho.") + ")");
            }
        }
    }

    private void importUri(Uri uri, boolean openAfter) {
        js("window.onImportStart && window.onImportStart()");
        io.execute(() -> {
            String name = displayName(uri);
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("O arquivo não pôde ser aberto.");
                saveBook(in, name, openAfter);
            } catch (SecurityException e) {
                importFailed(name, "Sem permissão para ler este arquivo. Tente escolher pelo botão Adicionar livro.");
            } catch (Exception e) {
                importFailed(name, e.getMessage());
            }
        });
    }

    private void importBytes(byte[] bytes, String name, boolean openAfter) {
        js("window.onImportStart && window.onImportStart()");
        io.execute(() -> {
            try {
                saveBook(new ByteArrayInputStream(bytes), name, openAfter);
            } catch (Exception e) {
                importFailed(name, e.getMessage());
            }
        });
    }

    private void importFailed(String name, String msg) {
        String text = "\"" + (name == null ? "arquivo" : name) + "\": " + (msg == null ? "não foi possível copiar." : msg);
        js("window.onImportError && window.onImportError(" + JSONObject.quote(text) + ")");
    }

    /** Copia o arquivo para files/books, evitando duplicados pelo SHA-1. Roda fora da thread de interface. */
    private void saveBook(InputStream in, String name, boolean openAfter) throws Exception {
        String id = Long.toString(System.currentTimeMillis(), 36) + UUID.randomUUID().toString().substring(0, 6);
        File out = new File(booksDir, id + ".bin");
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        long total = 0;
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) throw new IOException("Arquivo grande demais (mais de 300 MB).");
                sha.update(buf, 0, n);
                os.write(buf, 0, n);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
        if (total == 0) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new IOException("O arquivo está vazio.");
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        String hash = hex.toString();

        synchronized (metaLock) {
            String existing = findByHash(hash);
            if (existing != null) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                js("window.onBookImported && window.onBookImported(" + JSONObject.quote(existing) + ", true, " + openAfter + ")");
                return;
            }
            JSONObject meta = new JSONObject();
            meta.put("id", id);
            meta.put("fileName", name == null ? "livro.mobi" : name);
            meta.put("size", total);
            meta.put("added", System.currentTimeMillis());
            meta.put("hash", hash);
            meta.put("pos", 0);
            meta.put("lastRead", 0);
            meta.put("pending", true);
            writeText(new File(booksDir, id + ".json"), meta.toString());
        }
        js("window.onBookImported && window.onBookImported(" + JSONObject.quote(id) + ", false, " + openAfter + ")");
    }

    private String findByHash(String hash) {
        File[] files = booksDir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return null;
        for (File f : files) {
            try {
                JSONObject m = new JSONObject(readText(f));
                if (hash.equals(m.optString("hash")) && new File(booksDir, m.optString("id") + ".bin").isFile()) return m.optString("id");
            } catch (Exception ignored) { }
        }
        return null;
    }

    private String displayName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) { }
        }
        if (name == null) name = uri.getLastPathSegment();
        return name;
    }

    // ---------------------------------------------------------------- utilidades

    private static String readText(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static void writeText(File f, String s) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("Não foi possível salvar os dados do livro.");
        }
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    /** Executa JavaScript na página; se ela ainda não carregou, guarda para depois. */
    private void js(String code) {
        ui.post(() -> {
            if (pageReady) web.evaluateJavascript(code, null);
            else pendingJs.add(code);
        });
    }

    // ---------------------------------------------------------------- ponte com a página

    private class Bridge {

        @JavascriptInterface
        public void ready() {
            ui.post(() -> {
                pageReady = true;
                for (String c : pendingJs) web.evaluateJavascript(c, null);
                pendingJs.clear();
            });
        }

        @JavascriptInterface
        public String listBooks() {
            JSONArray arr = new JSONArray();
            synchronized (metaLock) {
                File[] files = booksDir.listFiles((d, n) -> n.endsWith(".json"));
                if (files != null) {
                    for (File f : files) {
                        try {
                            JSONObject m = new JSONObject(readText(f));
                            if (new File(booksDir, m.optString("id") + ".bin").isFile()) arr.put(m);
                        } catch (Exception ignored) { }
                    }
                }
            }
            return arr.toString();
        }

        @JavascriptInterface
        public void updateMeta(String id, String patchJson) {
            if (!validId(id)) return;
            synchronized (metaLock) {
                File f = new File(booksDir, id + ".json");
                if (!f.isFile()) return;
                try {
                    JSONObject m = new JSONObject(readText(f));
                    JSONObject patch = new JSONObject(patchJson);
                    JSONArray keys = patch.names();
                    if (keys != null) {
                        for (int i = 0; i < keys.length(); i++) {
                            String k = keys.getString(i);
                            if (k.equals("id") || k.equals("hash")) continue;
                            m.put(k, patch.get(k));
                        }
                    }
                    writeText(f, m.toString());
                } catch (Exception ignored) { }
            }
        }

        @JavascriptInterface
        public void deleteBook(String id) {
            if (!validId(id)) return;
            synchronized (metaLock) {
                //noinspection ResultOfMethodCallIgnored
                new File(booksDir, id + ".bin").delete();
                //noinspection ResultOfMethodCallIgnored
                new File(booksDir, id + ".json").delete();
            }
        }

        @JavascriptInterface
        public void pickBook() {
            ui.post(MainActivity.this::openPicker);
        }

        @JavascriptInterface
        public void importBase64(String name, String b64, boolean openAfter) {
            try {
                importBytes(Base64.decode(b64, Base64.DEFAULT), name, openAfter);
            } catch (IllegalArgumentException e) {
                importFailed(name, "Dados inválidos.");
            }
        }

        @JavascriptInterface
        public void setBars(String hexColor, boolean dark) {
            ui.post(() -> {
                try { applyBars(Color.parseColor(hexColor), dark); } catch (IllegalArgumentException ignored) { }
            });
        }
    }
}
