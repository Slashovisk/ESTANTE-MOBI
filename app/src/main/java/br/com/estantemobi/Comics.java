package br.com.estantemobi;

import com.github.junrar.Archive;
import com.github.junrar.exception.UnsupportedRarV5Exception;
import com.github.junrar.rarfile.FileHeader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Páginas de quadrinhos em CBZ/ZIP (lidas direto do arquivo) e CBR/RAR (extraídas para o cache). */
final class Comics {

    interface Pages {
        int count();
        String name(int i);
        byte[] read(int i) throws IOException;
        void close();
    }

    private final Sources sources;
    private final File rarCache;
    private final Map<String, Pages> open = new LinkedHashMap<String, Pages>(4, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pages> e) {
            if (size() > 2) { e.getValue().close(); return true; }
            return false;
        }
    };

    Comics(Sources sources, File cacheDir) {
        this.sources = sources;
        this.rarCache = new File(cacheDir, "rar");
        //noinspection ResultOfMethodCallIgnored
        rarCache.mkdirs();
    }

    synchronized Pages get(Book b) throws Exception {
        Pages p = open.get(b.id);
        if (p != null) return p;
        byte[] head = sources.head(b, 8);
        boolean rar = head.length >= 4 && head[0] == 'R' && head[1] == 'a' && head[2] == 'r' && head[3] == '!';
        boolean zip = head.length >= 2 && head[0] == 'P' && head[1] == 'K';
        if (head.length >= 6 && head[0] == '7' && head[1] == 'z') throw new IOException("Arquivos 7z (CB7) ainda não são suportados. Converta para CBZ.");
        if (!rar && !zip) throw new IOException("Este arquivo não é um quadrinho em ZIP nem em RAR.");
        p = rar ? openRar(b) : openZip(b);
        open.put(b.id, p);
        return p;
    }

    // ------------------------------------------------------------ ZIP / CBZ

    private Pages openZip(Book b) throws IOException {
        File f = sources.local(b);
        ZipFile zf;
        try {
            zf = openZipFile(f);
        } finally {
            Sources.release(f);
        }
        List<ZipEntry> pages = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (!e.isDirectory() && Sources.isImage(e.getName()) && e.getSize() != 0) pages.add(e);
        }
        Collections.sort(pages, (x, y) -> Sources.NATURAL.compare(x.getName(), y.getName()));
        final ZipFile z = zf;
        return new Pages() {
            public int count() { return pages.size(); }
            public String name(int i) { return pages.get(i).getName(); }
            public byte[] read(int i) throws IOException {
                synchronized (z) {
                    try (InputStream in = z.getInputStream(pages.get(i))) { return readFully(in); }
                }
            }
            public void close() { try { z.close(); } catch (IOException ignored) { } }
        };
    }

    static ZipFile openZipFile(File f) throws IOException {
        try {
            ZipFile z = new ZipFile(f, StandardCharsets.UTF_8);
            touchNames(z);
            return z;
        } catch (IllegalArgumentException | IOException e) {
            // nomes de arquivo em codificação antiga (comum em ZIPs feitos no Windows)
            Charset legacy;
            try { legacy = Charset.forName("IBM437"); } catch (Exception ce) { legacy = StandardCharsets.ISO_8859_1; }
            ZipFile z = new ZipFile(f, legacy);
            touchNames(z);
            return z;
        }
    }

    private static void touchNames(ZipFile z) {
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) en.nextElement().getName();
    }

    // ------------------------------------------------------------ RAR / CBR

    private Pages openRar(Book b) throws Exception {
        File dir = new File(rarCache, b.id);
        File done = new File(dir, ".pronto");
        if (!done.isFile()) {
            cleanRarCache();
            deleteDir(dir);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = sources.local(b);
            Archive a;
            try {
                a = new Archive(f);
            } catch (UnsupportedRarV5Exception e) {
                Sources.release(f);
                throw new IOException("Este CBR usa o formato RAR 5, que o app ainda não abre. Converta para CBZ (no Calibre ou no próprio computador).");
            }
            try {
                if (a.isEncrypted()) throw new IOException("Este arquivo está protegido por senha.");
                int n = 0;
                for (FileHeader fh : a.getFileHeaders()) {
                    if (fh.isDirectory()) continue;
                    String name = fh.getFileName().replace('\\', '/');
                    if (fh.isEncrypted()) throw new IOException("Este arquivo está protegido por senha.");
                    // extrai tudo na ordem do arquivo (RAR "sólido" exige isso); só guarda as imagens
                    OutputStream os = Sources.isImage(name)
                            ? new FileOutputStream(new File(dir, String.format(Locale.ROOT, "%05d_", n++) + safe(name)))
                            : new NullStream();
                    try { a.extractFile(fh, os); } finally { os.close(); }
                }
            } finally {
                a.close();
                Sources.release(f);
            }
            //noinspection ResultOfMethodCallIgnored
            done.createNewFile();
        }
        //noinspection ResultOfMethodCallIgnored
        dir.setLastModified(System.currentTimeMillis());
        File[] files = dir.listFiles((d, n) -> !n.startsWith("."));
        List<File> pages = new ArrayList<>();
        if (files != null) Collections.addAll(pages, files);
        // ordena pelo nome original (sem o prefixo numérico da extração)
        Collections.sort(pages, (x, y) -> Sources.NATURAL.compare(x.getName().substring(6), y.getName().substring(6)));
        return new Pages() {
            public int count() { return pages.size(); }
            public String name(int i) { return pages.get(i).getName().substring(6); }
            public byte[] read(int i) throws IOException { try (InputStream in = new FileInputStream(pages.get(i))) { return readFully(in); } }
            public void close() { }
        };
    }

    private static String safe(String name) {
        // mantém pastas no nome para ordenar certo, sem criar subpastas
        return name.replace('/', '¦').replaceAll("[\\\\:*?\"<>|]", "_");
    }

    private void cleanRarCache() {
        File[] dirs = rarCache.listFiles();
        if (dirs == null || dirs.length < 3) return;
        java.util.Arrays.sort(dirs, (a, c) -> Long.compare(a.lastModified(), c.lastModified()));
        for (int i = 0; i < dirs.length - 2; i++) deleteDir(dirs[i]);
    }

    static void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) deleteDir(f);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }

    synchronized void forget(String id) {
        Pages p = open.remove(id);
        if (p != null) p.close();
        deleteDir(new File(rarCache, id));
    }

    static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static final class NullStream extends OutputStream {
        @Override public void write(int b) { }
        @Override public void write(byte[] b, int off, int len) { }
    }

    /** Primeira página (pela ordem natural), para a capa. Não deixa nada aberto. */
    static byte[] firstPage(Sources sources, Book b) throws Exception {
        byte[] head = sources.head(b, 8);
        boolean rar = head.length >= 4 && head[0] == 'R' && head[1] == 'a' && head[2] == 'r' && head[3] == '!';
        File f = sources.local(b);
        try {
            if (!rar) {
                try (ZipFile z = openZipFile(f)) {
                    ZipEntry best = null;
                    Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        if (e.isDirectory() || !Sources.isImage(e.getName()) || e.getSize() == 0) continue;
                        if (best == null || Sources.NATURAL.compare(e.getName(), best.getName()) < 0) best = e;
                    }
                    if (best == null) return null;
                    try (InputStream in = z.getInputStream(best)) { return readFully(in); }
                }
            }
            Archive a = new Archive(f);
            try {
                if (a.isEncrypted()) return null;
                FileHeader best = null;
                for (FileHeader fh : a.getFileHeaders()) {
                    String name = fh.getFileName().replace('\\', '/');
                    if (fh.isDirectory() || !Sources.isImage(name)) continue;
                    if (best == null || Sources.NATURAL.compare(name, best.getFileName().replace('\\', '/')) < 0) best = fh;
                }
                if (best == null) return null;
                // em RAR sólido é preciso passar pelos anteriores
                for (FileHeader fh : a.getFileHeaders()) {
                    if (fh.isDirectory()) continue;
                    if (fh == best) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        a.extractFile(fh, out);
                        return out.toByteArray();
                    }
                    if (a.getMainHeader() != null && a.getMainHeader().isSolid()) a.extractFile(fh, new NullStream());
                }
                return null;
            } finally {
                a.close();
            }
        } finally {
            Sources.release(f);
        }
    }
}
