package br.com.estantemobi;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Banco da estante (SQLite do próprio Android). Aguenta dezenas de milhares de itens. */
final class LibraryDb extends SQLiteOpenHelper {

    private static final int VERSION = 1;

    LibraryDb(Context ctx) {
        super(ctx, "estante.db", null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books (" +
                "id TEXT PRIMARY KEY, source TEXT NOT NULL, path TEXT, uri TEXT, folder_uri TEXT, folder TEXT, grp TEXT," +
                "name TEXT, format TEXT, size INTEGER DEFAULT 0, mtime INTEGER DEFAULT 0, added INTEGER DEFAULT 0," +
                "title TEXT, author TEXT, last_read INTEGER DEFAULT 0, pos REAL DEFAULT 0, hidden INTEGER DEFAULT 0," +
                "hash TEXT, probed INTEGER DEFAULT 0, pages INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX books_folder ON books(folder_uri)");
        db.execSQL("CREATE INDEX books_hash ON books(hash)");
        db.execSQL("CREATE TABLE folders (uri TEXT PRIMARY KEY, name TEXT, added INTEGER, last_scan INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    // ------------------------------------------------------------ leitura

    private static final String COLS = "id, source, path, uri, folder_uri, folder, grp, name, format, size, mtime, added, title, author, last_read, pos, hash, pages";

    private static Book fromCursor(Cursor c) {
        Book b = new Book();
        b.id = c.getString(0); b.source = c.getString(1); b.path = c.getString(2); b.uri = c.getString(3);
        b.folderUri = c.getString(4); b.folder = c.getString(5); b.group = c.getString(6); b.name = c.getString(7);
        b.format = c.getString(8); b.size = c.getLong(9); b.mtime = c.getLong(10); b.added = c.getLong(11);
        b.title = c.getString(12); b.author = c.getString(13); b.lastRead = c.getLong(14); b.pos = c.getDouble(15);
        b.hash = c.getString(16); b.pages = c.getInt(17);
        return b;
    }

    Book get(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM books WHERE id = ?", new String[]{id})) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    String findByHash(String hash) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM books WHERE hash = ? AND source = 'app' LIMIT 1", new String[]{hash})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** Lista para a página, em JSON. Nomes de campos iguais aos da versão web. */
    String listJson() {
        JSONArray arr = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM books WHERE hidden = 0", null)) {
            while (c.moveToNext()) {
                Book b = fromCursor(c);
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", b.id);
                    o.put("title", b.title == null ? Book.cleanName(b.name) : b.title);
                    o.put("author", b.author == null ? "" : b.author);
                    o.put("fileName", b.name);
                    o.put("format", b.format);
                    o.put("size", b.size);
                    o.put("added", b.added);
                    o.put("lastRead", b.lastRead);
                    o.put("pos", b.pos);
                    o.put("source", b.source);
                    if (b.group != null) o.put("group", b.group);
                    if (b.folder != null) o.put("folder", b.folder);
                    if (b.pages > 0) o.put("pages", b.pages);
                    arr.put(o);
                } catch (Exception ignored) { }
            }
        }
        return arr.toString();
    }

    // ------------------------------------------------------------ escrita

    void insertCopy(Book b) {
        ContentValues v = new ContentValues();
        v.put("id", b.id); v.put("source", "app"); v.put("path", b.path); v.put("name", b.name);
        v.put("format", b.format); v.put("size", b.size); v.put("added", b.added); v.put("hash", b.hash);
        v.put("title", b.title != null ? b.title : Book.cleanName(b.name));
        if (b.author != null) v.put("author", b.author);
        v.put("pos", b.pos); v.put("last_read", b.lastRead);
        getWritableDatabase().insertWithOnConflict("books", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void update(String id, JSONObject patch) {
        ContentValues v = new ContentValues();
        if (patch.has("title")) v.put("title", patch.optString("title"));
        if (patch.has("author")) v.put("author", patch.optString("author"));
        if (patch.has("pos")) v.put("pos", patch.optDouble("pos", 0));
        if (patch.has("lastRead")) v.put("last_read", patch.optLong("lastRead", 0));
        if (patch.has("pages")) v.put("pages", patch.optInt("pages", 0));
        if (v.size() > 0) getWritableDatabase().update("books", v, "id = ?", new String[]{id});
    }

    void setProbe(String id, String title, String author) {
        ContentValues v = new ContentValues();
        v.put("probed", 1);
        SQLiteDatabase db = getWritableDatabase();
        db.update("books", v, "id = ?", new String[]{id});
        if (title != null && !title.trim().isEmpty()) {
            // só troca o título se ele ainda for o nome do arquivo (não sobrescreve o que a página já gravou)
            Book b = get(id);
            if (b != null && (b.title == null || b.title.equals(Book.cleanName(b.name)))) {
                ContentValues t = new ContentValues();
                t.put("title", title.trim());
                if (author != null && !author.trim().isEmpty()) t.put("author", author.trim());
                db.update("books", t, "id = ?", new String[]{id});
            }
        }
    }

    void hide(String id) {
        ContentValues v = new ContentValues();
        v.put("hidden", 1);
        getWritableDatabase().update("books", v, "id = ?", new String[]{id});
    }

    void delete(String id) {
        getWritableDatabase().delete("books", "id = ?", new String[]{id});
    }

    // ------------------------------------------------------------ pastas

    void addFolder(String uri, String name) {
        ContentValues v = new ContentValues();
        v.put("uri", uri); v.put("name", name); v.put("added", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("folders", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    List<String[]> folders() {
        List<String[]> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uri, name FROM folders ORDER BY added", null)) {
            while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)});
        }
        return out;
    }

    String foldersJson() {
        JSONArray arr = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT f.uri, f.name, f.last_scan, (SELECT COUNT(*) FROM books b WHERE b.folder_uri = f.uri AND b.hidden = 0) FROM folders f ORDER BY f.added", null)) {
            while (c.moveToNext()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("uri", c.getString(0)); o.put("name", c.getString(1));
                    o.put("lastScan", c.getLong(2)); o.put("count", c.getInt(3));
                    arr.put(o);
                } catch (Exception ignored) { }
            }
        }
        return arr.toString();
    }

    void removeFolder(String uri) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("books", "folder_uri = ?", new String[]{uri});
        db.delete("folders", "uri = ?", new String[]{uri});
    }

    Set<String> idsInFolder(String folderUri) {
        Set<String> out = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id FROM books WHERE folder_uri = ?", new String[]{folderUri})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /** Grava um lote de arquivos encontrados numa pasta. Devolve quantos eram novos. */
    int upsertFolderItems(Collection<Book> items) {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        db.beginTransaction();
        try {
            for (Book b : items) {
                ContentValues v = new ContentValues();
                v.put("id", b.id); v.put("source", "folder"); v.put("uri", b.uri); v.put("folder_uri", b.folderUri);
                v.put("folder", b.folder); v.put("grp", b.group); v.put("name", b.name); v.put("format", b.format);
                v.put("size", b.size); v.put("mtime", b.mtime); v.put("added", b.added); v.put("title", Book.cleanName(b.name));
                if (db.insertWithOnConflict("books", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1) {
                    added++;
                } else {
                    ContentValues u = new ContentValues();
                    u.put("uri", b.uri); u.put("grp", b.group); u.put("name", b.name); u.put("size", b.size); u.put("mtime", b.mtime); u.put("folder", b.folder);
                    db.update("books", u, "id = ?", new String[]{b.id});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }

    void deleteIds(Collection<String> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String id : ids) db.delete("books", "id = ?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void markScanned(String folderUri) {
        ContentValues v = new ContentValues();
        v.put("last_scan", System.currentTimeMillis());
        getWritableDatabase().update("folders", v, "uri = ?", new String[]{folderUri});
    }

    int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM books", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
