package br.com.estantemobi;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Percorre uma pasta escolhida pelo usuário (e as subpastas) e registra os arquivos suportados, sem copiar. */
final class FolderScanner {

    interface Progress {
        void onProgress(int found);
    }

    static final class Result {
        int total, added, removed;
    }

    private static final String[] COLS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    private final ContentResolver cr;
    private final LibraryDb db;

    FolderScanner(ContentResolver cr, LibraryDb db) {
        this.cr = cr;
        this.db = db;
    }

    static String displayName(ContentResolver cr, Uri tree) {
        try {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
            }
        } catch (Exception ignored) { }
        String last = tree.getLastPathSegment();
        if (last == null) return "Pasta";
        int colon = last.lastIndexOf(':');
        String s = colon >= 0 ? last.substring(colon + 1) : last;
        if (s.contains("/")) s = s.substring(s.lastIndexOf('/') + 1);
        return s.isEmpty() ? "Pasta" : s;
    }

    Result scan(String treeUri, String rootName, Progress progress) {
        Uri tree = Uri.parse(treeUri);
        Result res = new Result();
        Set<String> before = db.idsInFolder(treeUri);
        Set<String> seen = new HashSet<>();
        List<Book> batch = new ArrayList<>();
        ArrayDeque<String[]> stack = new ArrayDeque<>();
        stack.push(new String[]{DocumentsContract.getTreeDocumentId(tree), rootName});
        long now = System.currentTimeMillis();
        int lastReport = 0;
        while (!stack.isEmpty()) {
            String[] dir = stack.pop();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir[0]);
            try (Cursor c = cr.query(children, COLS, null, null, null)) {
                if (c == null) continue;
                while (c.moveToNext()) {
                    String docId = c.getString(0), name = c.getString(1), mime = c.getString(2);
                    if (name == null || name.startsWith(".")) continue;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        stack.push(new String[]{docId, dir[1] + "/" + name});
                        continue;
                    }
                    if (!Book.accepted(name)) continue;
                    Book b = new Book();
                    b.uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString();
                    b.id = idFor(b.uri);
                    b.folderUri = treeUri;
                    b.folder = rootName;
                    b.group = dir[1];
                    b.name = name;
                    String ext = Book.extOf(name);
                    b.format = ext.equals("prc") || ext.equals("azw") ? "mobi" : ext;
                    b.size = c.isNull(3) ? 0 : c.getLong(3);
                    b.mtime = c.isNull(4) ? 0 : c.getLong(4);
                    b.added = now;
                    if (seen.add(b.id)) batch.add(b);
                    if (batch.size() >= 300) { res.added += db.upsertFolderItems(batch); batch.clear(); }
                    if (progress != null && seen.size() - lastReport >= 250) { lastReport = seen.size(); progress.onProgress(seen.size()); }
                }
            } catch (Exception ignored) {
                // pasta sem permissão ou removida no meio da leitura: segue com as outras
            }
        }
        if (!batch.isEmpty()) res.added += db.upsertFolderItems(batch);
        before.removeAll(seen);
        if (!before.isEmpty()) db.deleteIds(before);
        res.removed = before.size();
        res.total = seen.size();
        db.markScanned(treeUri);
        return res;
    }

    static String idFor(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("f");
            for (int i = 0; i < 10; i++) sb.append(String.format(Locale.ROOT, "%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "f" + Integer.toHexString(key.hashCode());
        }
    }
}
