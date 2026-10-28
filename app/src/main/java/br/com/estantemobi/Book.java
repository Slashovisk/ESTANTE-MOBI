package br.com.estantemobi;

import java.util.Locale;

/** Um item da estante: um arquivo copiado para o app ("app") ou lido de uma pasta do celular ("folder"). */
final class Book {
    String id;
    String source;      // "app" ou "folder"
    String path;        // arquivo local (source = app)
    String uri;         // documento da pasta (source = folder)
    String folderUri;   // pasta raiz adicionada
    String folder;      // nome da pasta raiz
    String group;       // série: pasta onde o arquivo está (relativa, começando pelo nome da raiz)
    String name;        // nome do arquivo
    String format;      // mobi, epub, fb2, txt, cbz, cbr, zip, pdf, azw3, djvu
    String title;
    String author;
    String hash;
    String kind;        // categoria escolhida pelo usuário; nulo = automática pelo formato
    long size, mtime, added, lastRead;
    double pos;
    int pages;

    static final String[] FORMATS = {"mobi", "prc", "azw", "azw3", "epub", "fb2", "txt", "cbz", "cbr", "zip", "pdf", "djvu"};

    static String extOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean accepted(String name) {
        String e = extOf(name);
        for (String f : FORMATS) if (f.equals(e)) return true;
        return false;
    }

    /** Formato pelo conteúdo do arquivo; o nome serve de desempate. */
    static String detect(String name, byte[] head, int n) {
        String ext = extOf(name);
        if (n >= 68 && ascii(head, 60, 8).equals("BOOKMOBI")) return ext.equals("azw3") ? "azw3" : "mobi";
        if (n >= 68 && ascii(head, 60, 8).equals("TEXtREAd")) return "mobi";
        if (n >= 5 && ascii(head, 0, 5).equals("%PDF-")) return "pdf";
        if (n >= 4 && ascii(head, 0, 4).equals("Rar!")) return "cbr";
        if (n >= 8 && ascii(head, 0, 8).equals("AT&TFORM")) return "djvu";
        if (n >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            if (n >= 58 && ascii(head, 30, 8).equals("mimetype") && ascii(head, 38, 20).contains("epub")) return "epub";
            if (ext.equals("epub")) return "epub";
            return ext.equals("zip") ? "zip" : "cbz";
        }
        String start = ascii(head, 0, Math.min(n, 600));
        if (start.contains("<FictionBook")) return "fb2";
        if (ext.equals("txt") || ext.equals("text")) return "txt";
        return ext;
    }

    static String kind(String format) {
        switch (format == null ? "" : format) {
            case "cbz": case "cbr": case "zip": return "comic";
            case "pdf": case "djvu": return "doc";
            default: return "book";
        }
    }

    static String cleanName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        String s = dot > 0 ? name.substring(0, dot) : name;
        return s.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String ascii(byte[] b, int off, int len) {
        if (off >= b.length) return "";
        len = Math.min(len, b.length - off);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int c = b[off + i] & 0xFF;
            sb.append(c >= 32 && c < 127 ? (char) c : ' ');
        }
        return sb.toString();
    }
}
