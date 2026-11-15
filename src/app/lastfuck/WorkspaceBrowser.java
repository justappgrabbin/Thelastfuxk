package app.lastfuck;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** A read-only view into the same persistent directory bound at /workspace. */
final class WorkspaceBrowser {
    private final File root;
    WorkspaceBrowser(File root) { this.root=root; }
    File resolve(String path) throws IOException {
        File base=root.getCanonicalFile();
        if (path.startsWith("/") || path.indexOf('\0')>=0) throw new IOException("Use a workspace-relative path");
        File file=new File(base,path).getCanonicalFile();
        if (!file.toPath().startsWith(base.toPath())) throw new IOException("Path outside workspace");
        return file;
    }
    JSONObject list(String path) throws Exception {
        File folder=resolve(path);
        File[] entries=folder.listFiles();
        if(entries==null) throw new IOException("Folder unavailable");
        Arrays.sort(entries, Comparator.comparing((File f)->!f.isDirectory()).thenComparing(f->f.getName().toLowerCase(Locale.ROOT)));
        JSONArray items=new JSONArray();
        for(File entry:entries) {
            try { resolve(relative(entry)); } catch(IOException escape) { continue; }
            items.put(new JSONObject().put("name",entry.getName()).put("path",relative(entry))
                .put("directory",entry.isDirectory()).put("bytes",entry.length()).put("modified",entry.lastModified()));
            if(items.length()>=1000) break;
        }
        return new JSONObject().put("path",relative(folder)).put("items",items).put("truncated",entries.length>1000);
    }
    String relative(File file) throws IOException { return root.getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath()).toString(); }
    JSONObject preview(String path) throws Exception {
        File file=resolve(path);
        if(!file.isFile()) throw new IOException("Choose a file to preview");
        String name=file.getName().toLowerCase(Locale.ROOT);
        JSONObject value=new JSONObject().put("path",relative(file)).put("name",file.getName()).put("bytes",file.length());
        String mime=name.endsWith(".png")?"image/png":name.endsWith(".jpg")||name.endsWith(".jpeg")?"image/jpeg":name.endsWith(".webp")?"image/webp":name.endsWith(".gif")?"image/gif":"";
        if(!mime.isEmpty()) {
            if(file.length()>4*1024*1024) throw new IOException("Image exceeds 4 MB preview limit. Export to open the full file.");
            return value.put("kind","image").put("data","data:"+mime+";base64,"+Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath())));
        }
        boolean text=name.matches(".*\\.(html?|css|js|json|md|txt|py|sh|xml|svg|java|csv|log|yaml|yml|toml|ini|sql|c|cpp|h|ts|tsx|jsx)$")||!name.contains(".");
        if(!text) return value.put("kind","download").put("text","This file is saved in the workspace. Use Export to open it in another app.");
        String content=RuntimeFiles.read(new FileInputStream(file),192*1024);
        return value.put("kind", name.endsWith(".html")||name.endsWith(".htm")?"html":"text")
            .put("text",content).put("truncated",file.length()>192*1024);
    }
}
