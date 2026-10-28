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
 * Estante: uma WebView com a interface (assets/www/index.html) e uma ponte "Android".
 *
 * Itens da estante ficam num banco SQLite. Arquivos avulsos são copiados para files/books/;
 * pastas inteiras são lidas no lugar, com permissão persistente do Android.
 *
 * Endereços servidos para a página (https://appassets.androidplatform.net/...):
 *   /books/<id>.bin            arquivo original (livros de texto)
 *   /thumb/<id>.jpg            capa em miniatura
 *   /comic/<id>/pages.json     número de páginas de um CBZ/CBR/ZIP
 *   /comic/<id>/<n>            imagem da página n
 *   /pdf/<id>/info.json        número de páginas de um PDF
 *   /pdf/<id>/<n>.jpg?w=1200   página n desenhada na largura pedida
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_PICK = 4101;
    private static final int REQ_FOLDER = 4102;
    private static final long MAX_BYTES = 600L * 1024 * 1024;

    private WebView web;
    private FrameLayout root;
    private File booksDir;
    private LibraryDb db;
    private Sources sources;
    private Comics comics;
    private Pdfs pdfs;
    private Thumbs thumbs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final List<String> pendingJs = new ArrayList<>();
    private volatile boolean pageReady = false;
    private boolean rescannedOnStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        booksDir = new File(getFilesDir(), "books");
        //noinspection ResultOfMethodCallIgnored
        booksDir.mkdirs();
        db = new LibraryDb(this);
        sources = new Sources(this);
        comics = new Comics(sources, getCacheDir());
        pdfs = new Pdfs(sources);
        thumbs = new Thumbs(getFilesDir(), sources, db);
        io.execute(this::migrateV1);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        root = new FrameLayout(this);
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        root.setBackgroundColor(Color.parseColor(night ? "#141413" : "#FBFAF7"));
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
        // O app tem modo escuro próprio: impede que o Android escureça a página por cima (texto preto em fundo escuro)
        disableForcedDark(s);
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
        scanner.shutdown();
        pdfs.close();
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void disableForcedDark(WebSettings s) {
        if (Build.VERSION.SDK_INT >= 29) {
            root.setForceDarkAllowed(false);
            web.setForceDarkAllowed(false);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= 29) {
            s.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
    }

    // ---------------------------------------------------------------- barras do sistema

    @SuppressWarnings("deprecation")
    private void setupEdgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
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
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(dark ? (flags & ~light) : (flags | light));
        }
    }

    // ---------------------------------------------------------------- servidor local da WebView

    private WebResourceResponse serve(Uri u) {
        if (!HOST.equals(u.getHost())) return null;
        String path = u.getPath();
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..")) return status(404, null);
        try {
            String[] seg = path.substring(1).split("/");
            switch (seg[0]) {
                case "books": {
                    Book b = bookFrom(seg, 1, ".bin");
                    if (b == null) return status(404, null);
                    Map<String, String> h = new HashMap<>();
                    h.put("Cache-Control", "no-store");
                    if (b.size > 0) h.put("Content-Length", String.valueOf(b.size));
                    return new WebResourceResponse("application/octet-stream", null, 200, "OK", h, sources.open(b));
                }
                case "thumb": {
                    Book b = bookFrom(seg, 1, ".jpg");
                    File f = b == null ? null : thumbs.get(b);
                    if (f == null) return status(404, null);
                    Map<String, String> h = new HashMap<>();
                    h.put("Cache-Control", "max-age=31536000");
                    return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new FileInputStream(f));
                }
                case "comic": {
                    Book b = seg.length > 2 ? db.get(seg[1]) : null;
                    if (b == null) return status(404, null);
                    if (seg[2].equals("pages.json")) {
                        try {
                            Comics.Pages p = comics.get(b);
                            if (p.count() > 0 && b.pages != p.count()) db.update(b.id, new JSONObject().put("pages", p.count()));
                            return json("{\"pages\":" + p.count() + "}");
                        } catch (Exception e) {
                            return json(new JSONObject().put("error", message(e, "Não foi possível abrir este quadrinho.")).toString());
                        }
                    }
                    Comics.Pages p = comics.get(b);
                    int i = Integer.parseInt(seg[2]);
                    if (i < 0 || i >= p.count()) return status(404, null);
                    Map<String, String> h = new HashMap<>();
                    h.put("Cache-Control", "max-age=86400");
                    return new WebResourceResponse(Sources.imageMime(p.name(i)), null, 200, "OK", h, new ByteArrayInputStream(p.read(i)));
                }
                case "pdf": {
                    Book b = seg.length > 2 ? db.get(seg[1]) : null;
                    if (b == null) return status(404, null);
                    if (seg[2].equals("info.json")) {
                        try {
                            int n = pdfs.pageCount(b);
                            return json("{\"pages\":" + n + "}");
                        } catch (Exception e) {
                            return json(new JSONObject().put("error", message(e, "Não foi possível abrir este PDF.")).toString());
                        }
                    }
                    int i = Integer.parseInt(seg[2].replace(".jpg", ""));
                    int w = 1200;
                    try { w = Integer.parseInt(u.getQueryParameter("w")); } catch (Exception ignored) { }
                    Map<String, String> h = new HashMap<>();
                    h.put("Cache-Control", "max-age=86400");
                    return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(pdfs.render(b, i, w)));
                }
                default: {
                    InputStream in = getAssets().open("www" + path);
                    return new WebResourceResponse(mimeOf(path), "UTF-8", in);
                }
            }
        } catch (Exception e) {
            return status(500, null);
        }
    }

    private Book bookFrom(String[] seg, int i, String suffix) {
        if (seg.length <= i) return null;
        String id = seg[i];
        if (id.endsWith(suffix)) id = id.substring(0, id.length() - suffix.length());
        return validId(id) ? db.get(id) : null;
    }

    private static String message(Exception e, String fallback) {
        String m = e.getMessage();
        return m == null || m.isEmpty() || m.startsWith("java.") ? fallback : m;
    }

    private static WebResourceResponse json(String body) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("application/json", "UTF-8", 200, "OK", h, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static WebResourceResponse status(int code, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", code, reason == null ? (code == 404 ? "Not Found" : "Error") : reason, new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
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
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_FOLDER && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                js("window.onImportError && window.onImportError(" + JSONObject.quote("O Android não deu permissão para ler esta pasta.") + ")");
                return;
            }
            String name = FolderScanner.displayName(getContentResolver(), tree);
            db.addFolder(tree.toString(), name);
            scanFolder(tree.toString(), name);
            return;
        }
        if (requestCode != REQ_PICK) return;
        ClipData clip = data.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            for (int i = 0; i < clip.getItemCount(); i++) importUri(clip.getItemAt(i).getUri(), false);
        } else if (data.getData() != null) {
            importUri(data.getData(), false);
        }
    }

    @SuppressWarnings("deprecation")
    private void openPicker(boolean folder) {
        Intent i = folder ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) : new Intent(Intent.ACTION_OPEN_DOCUMENT);
        if (!folder) {
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(i, folder ? REQ_FOLDER : REQ_PICK);
        } catch (Exception e) {
            js("window.onImportError && window.onImportError(" + JSONObject.quote("Não há um seletor de arquivos disponível neste aparelho.") + ")");
        }
    }

    private void scanFolder(String treeUri, String name) {
        js("window.onScanProgress && window.onScanProgress(" + JSONObject.quote(name) + ", 0)");
        scanner.execute(() -> {
            FolderScanner fs = new FolderScanner(getContentResolver(), db);
            FolderScanner.Result r = fs.scan(treeUri, name, n ->
                    js("window.onScanProgress && window.onScanProgress(" + JSONObject.quote(name) + ", " + n + ")"));
            js("window.onScanDone && window.onScanDone(" + JSONObject.quote(name) + ", " + r.total + ", " + r.added + ")");
        });
    }

    /** Ao abrir o app, confere as pastas em segundo plano para pegar arquivos novos ou apagados. */
    private void rescanAll() {
        for (String[] f : db.folders()) {
            String uri = f[0], name = f[1];
            scanner.execute(() -> {
                boolean ok = false;
                for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                    if (p.getUri().toString().equals(uri) && p.isReadPermission()) ok = true;
                }
                if (!ok) return; // permissão perdida: a pasta continua listada; o usuário pode removê-la
                FolderScanner.Result r = new FolderScanner(getContentResolver(), db).scan(uri, name, null);
                if (r.added > 0 || r.removed > 0) js("window.onLibraryChanged && window.onLibraryChanged()");
            });
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
                importFailed(name, "Sem permissão para ler este arquivo. Tente pelo botão Adicionar.");
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
        byte[] head = new byte[4096];
        int headLen = 0;
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (headLen < head.length) {
                    int k = Math.min(n, head.length - headLen);
                    System.arraycopy(buf, 0, head, headLen, k);
                    headLen += k;
                }
                total += n;
                if (total > MAX_BYTES) throw new IOException("Arquivo grande demais (mais de 600 MB). Use \"Adicionar uma pasta inteira\" para arquivos grandes.");
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
        String format = Book.detect(name, head, headLen);
        boolean known = false;
        for (String f : Book.FORMATS) if (f.equals(format)) known = true;
        if (!known) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new IOException("Formato não suportado. A estante abre MOBI, EPUB, FB2, TXT, CBZ, CBR e PDF.");
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        String hash = hex.toString();

        String existing = db.findByHash(hash);
        if (existing != null) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            js("window.onBookImported && window.onBookImported(" + JSONObject.quote(existing) + ", true, " + openAfter + ")");
            return;
        }
        Book b = new Book();
        b.id = id; b.path = out.getAbsolutePath(); b.name = name == null ? "livro." + format : name;
        b.format = format.equals("prc") || format.equals("azw") ? "mobi" : format;
        b.size = total; b.added = System.currentTimeMillis(); b.hash = hash;
        db.insertCopy(b);
        // lê título, autor e capa já agora, para o cartão aparecer completo
        thumbs.get(db.get(id));
        js("window.onBookImported && window.onBookImported(" + JSONObject.quote(id) + ", false, " + openAfter + ")");
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

    /** Versão 1 guardava cada livro como <id>.bin + <id>.json. Passa tudo para o banco. */
    private void migrateV1() {
        File[] metas = booksDir.listFiles((d, n) -> n.endsWith(".json"));
        if (metas == null) return;
        for (File f : metas) {
            try {
                JSONObject m = new JSONObject(readText(f));
                String id = m.optString("id");
                File bin = new File(booksDir, id + ".bin");
                if (validId(id) && bin.isFile() && db.get(id) == null) {
                    Book b = new Book();
                    b.id = id; b.path = bin.getAbsolutePath(); b.name = m.optString("fileName", "livro.mobi");
                    b.format = "mobi"; b.size = bin.length(); b.added = m.optLong("added", System.currentTimeMillis());
                    b.hash = m.optString("hash", null); b.pos = m.optDouble("pos", 0); b.lastRead = m.optLong("lastRead", 0);
                    String t = m.optString("title", "");
                    b.title = t.isEmpty() ? null : t;
                    String a = m.optString("author", "");
                    b.author = a.isEmpty() ? null : a;
                    db.insertCopy(b);
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } catch (Exception ignored) { }
        }
        if (metas.length > 0) js("window.onLibraryChanged && window.onLibraryChanged()");
    }

    private static String readText(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(Comics.readFully(in), StandardCharsets.UTF_8);
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
                if (!rescannedOnStart) { rescannedOnStart = true; rescanAll(); }
            });
        }

        @JavascriptInterface
        public String listBooks() {
            return db.listJson();
        }

        @JavascriptInterface
        public void updateMeta(String id, String patchJson) {
            if (!validId(id)) return;
            try { db.update(id, new JSONObject(patchJson)); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void deleteBook(String id) {
            if (!validId(id)) return;
            Book b = db.get(id);
            if (b == null) return;
            if ("app".equals(b.source)) {
                //noinspection ResultOfMethodCallIgnored
                new File(b.path).delete();
                db.delete(id);
                thumbs.forget(id);
            } else {
                db.hide(id); // arquivo de pasta: só sai da estante, o arquivo fica no celular
            }
            comics.forget(id);
        }

        @JavascriptInterface
        public void pickBook() {
            ui.post(() -> openPicker(false));
        }

        @JavascriptInterface
        public void pickFolder() {
            ui.post(() -> openPicker(true));
        }

        @JavascriptInterface
        public String listFolders() {
            return db.foldersJson();
        }

        @JavascriptInterface
        public void rescanFolder(String uri) {
            for (String[] f : db.folders()) if (f[0].equals(uri)) scanFolder(f[0], f[1]);
        }

        @JavascriptInterface
        public void removeFolder(String uri) {
            db.removeFolder(uri);
            try {
                getContentResolver().releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
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
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
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
