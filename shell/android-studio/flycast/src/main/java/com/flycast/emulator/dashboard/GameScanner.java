package com.flycast.emulator.dashboard;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scans a folder picked with the system folder picker (Storage Access Framework) for game images.
 * The URIs returned are tree-based document URIs, which is what Flycast expects so it can find the
 * track files that sit next to a .gdi/.cue file.
 */
public class GameScanner {
    private static final String[] EXTENSIONS = {"gdi", "cdi", "chd", "cue", "zip", "7z", "lst"};
    private static final int MAX_DEPTH = 4;
    private static final int MAX_GAMES = 2000;

    public static List<DashboardStore.Game> scan(Context context, Uri treeUri) {
        List<DashboardStore.Game> result = new ArrayList<>();
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            scanDir(context, treeUri, rootId, 0, result);
        } catch (Exception ignored) {
            // invalid tree uri or permission lost: return what we have
        }
        return result;
    }

    private static void scanDir(Context context, Uri treeUri, String parentId, int depth,
                                List<DashboardStore.Game> out) {
        if (depth > MAX_DEPTH || out.size() >= MAX_GAMES)
            return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        Cursor c = null;
        List<String> subDirs = new ArrayList<>();
        try {
            c = context.getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (c == null)
                return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name == null)
                    continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    subDirs.add(id);
                } else if (isGameFile(name)) {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    out.add(new DashboardStore.Game(doc.toString(), DashboardStore.stripExtension(name)));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null)
                c.close();
        }
        for (String dirId : subDirs)
            scanDir(context, treeUri, dirId, depth + 1, out);
    }

    private static boolean isGameFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0)
            return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : EXTENSIONS) {
            if (e.equals(ext))
                return true;
        }
        return false;
    }
}
