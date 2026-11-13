package org.mobilemcp.ondevice;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** File access is confined to a document tree explicitly selected by the owner. */
final class WorkspaceTools {
    private static final int LIMIT = 256 * 1024;
    static void select(Context context, Uri tree) {
        context.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit()
                .putString("tree", tree.toString()).apply();
    }
    static String selected(Context context) {
        return context.getSharedPreferences("workspace", Context.MODE_PRIVATE).getString("tree", null);
    }
    static void forget(Context context) {
        context.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit().remove("tree").apply();
    }
    private static Uri child(Context context, Uri tree, String parent, String name) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Cannot list workspace folder");
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1)))
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
            }
        }
        return null;
    }
    private static Uri resolve(Context context, Uri tree, String path, boolean create) throws Exception {
        Uri current = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        if (path.isEmpty()) return current;
        String[] parts = path.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0 || part.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Use a relative path without empty, . or .. segments");
            Uri next = child(context, tree, DocumentsContract.getDocumentId(current), part);
            if (next == null && create && i == parts.length - 1)
                next = DocumentsContract.createDocument(context.getContentResolver(), current, "text/plain", part);
            if (next == null) throw new IllegalArgumentException("Path missing; parent folders must already exist");
            current = next;
        }
        return current;
    }
    static synchronized JSONObject execute(Context context, String name, JSONObject args) throws Exception {
        String selected = selected(context);
        if (selected == null) throw new IllegalStateException("Choose a read/write workspace folder in the app first");
        Uri tree = Uri.parse(selected);
        String path = args.optString("path", "");
        if (!path.isEmpty()) {
            for (String segment : path.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                        || segment.indexOf('\\') >= 0 || segment.indexOf('\0') >= 0)
                    throw new IllegalArgumentException("Use a relative path without empty, . or .. segments");
            }
        }
        if (name.equals("mobile_create_directory")) {
            if (path.isEmpty()) throw new IllegalArgumentException("A directory path is required");
            int slash = path.lastIndexOf('/');
            String leaf = path.substring(slash + 1);
            if (leaf.isEmpty() || leaf.equals(".") || leaf.equals("..") || leaf.indexOf('\\') >= 0 || leaf.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid directory name");
            Uri parent = resolve(context, tree, slash < 0 ? "" : path.substring(0, slash), false);
            if (child(context, tree, DocumentsContract.getDocumentId(parent), leaf) != null)
                throw new IllegalArgumentException("Path already exists");
            Uri created = DocumentsContract.createDocument(context.getContentResolver(), parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, leaf);
            if (created == null) throw new IllegalStateException("Folder provider rejected directory creation");
            return new JSONObject().put("path", path).put("created", true);
        }
        if (name.equals("mobile_delete_file")) {
            if (path.isEmpty() || !args.optBoolean("confirm", false))
                throw new IllegalArgumentException("A file path and confirm=true are required");
            Uri file = resolve(context, tree, path, false);
            try (Cursor cursor = context.getContentResolver().query(file,
                    new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IllegalArgumentException("File unavailable");
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0)))
                    throw new IllegalArgumentException("Directory deletion is not supported");
            }
            if (!DocumentsContract.deleteDocument(context.getContentResolver(), file))
                throw new IllegalStateException("Folder provider rejected deletion");
            return new JSONObject().put("path", path).put("deleted", true);
        }
        // Resolve without creation first; only the write operation may create a file.
        if (name.equals("mobile_write_file")) {
            if (path.isEmpty()) throw new IllegalArgumentException("A file path is required");
            byte[] bytes = args.getString("text").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > LIMIT) throw new IllegalArgumentException("File limit is 256 KiB UTF-8");
            Uri existing = null;
            try { existing = resolve(context, tree, path, false); }
            catch (IllegalArgumentException missing) { /* create after checking path below */ }
            if (existing != null && !args.optBoolean("overwrite", false))
                throw new IllegalArgumentException("File exists; set overwrite=true to replace its contents");
            Uri file = existing == null ? resolve(context, tree, path, true) : existing;
            requireFile(context, file);
            try (OutputStream out = context.getContentResolver().openOutputStream(file, "wt")) {
                if (out == null) throw new IllegalStateException("Folder provider denied write access");
                out.write(bytes);
            }
            return new JSONObject().put("path", path).put("bytesWritten", bytes.length);
        }
        Uri target = resolve(context, tree, path, false);
        if (name.equals("mobile_list_files")) {
            JSONArray files = new JSONArray();
            try (Cursor cursor = context.getContentResolver().query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(target)),
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null)) {
                if (cursor == null) throw new IllegalStateException("Cannot list workspace folder");
                while (cursor.moveToNext() && files.length() < 500)
                    files.put(new JSONObject().put("name", cursor.getString(0)).put("directory",
                            DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1))));
                return new JSONObject().put("files", files).put("truncated", !cursor.isAfterLast());
            }
        }
        if (path.isEmpty()) throw new IllegalArgumentException("A file path is required");
        requireFile(context, target);
        try (InputStream in = context.getContentResolver().openInputStream(target)) {
            if (in == null) throw new IllegalStateException("Folder provider denied read access");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > LIMIT) throw new IllegalArgumentException("File limit is 256 KiB");
                out.write(buffer, 0, count);
            }
            return new JSONObject().put("path", path).put("text", out.toString("UTF-8"));
        }
    }
    private static void requireFile(Context context, Uri file) throws Exception {
        try (Cursor cursor = context.getContentResolver().query(file,
                new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalArgumentException("File unavailable");
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0)))
                throw new IllegalArgumentException("Path is a directory; use mobile_list_files");
        }
    }
}
