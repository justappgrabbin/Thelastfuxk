package app.lastfuck;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Imports a web ZIP into a new directory, preserving the original archive. */
final class ZipRunner {
    static final long MAX_BYTES = 200L * 1024 * 1024;
    static final int MAX_ENTRIES = 5000;
    static final class Project {
        final File root;
        final List<String> entries;
        Project(File root, List<String> entries) { this.root=root; this.entries=entries; }
    }
    static Project unpack(File archive, File projects) throws IOException {
        if (!archive.isFile() || archive.length()>MAX_BYTES) throw new IOException("Choose a ZIP under 200 MB");
        Files.createDirectories(projects.toPath());
        File dest=Files.createTempDirectory(projects.toPath(), "zip-").toFile();
        boolean complete=false;
        try {
            long total=0;
            int count=0;
            Set<String> paths=new HashSet<>();
            List<String> html=new ArrayList<>();
            try (ZipFile zip=new ZipFile(archive)) {
                Enumeration<? extends ZipEntry> entries=zip.entries();
                byte[] buffer=new byte[32768];
                while(entries.hasMoreElements()) {
                    ZipEntry entry=entries.nextElement();
                    if(++count>MAX_ENTRIES) throw new IOException("ZIP exceeds 5,000 entries");
                    String name=entry.getName();
                    if(name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf('\0')>=0)
                        throw new IOException("Unsafe ZIP path");
                    for(String part:name.split("/")) if(part.equals("..") || part.equals(".")) throw new IOException("Unsafe ZIP path");
                    File target=new File(dest,name).getCanonicalFile();
                    if(!target.toPath().startsWith(dest.getCanonicalFile().toPath()) || target.equals(dest)) throw new IOException("ZIP path escapes project");
                    if(!paths.add(target.getPath())) throw new IOException("Duplicate ZIP path: "+name);
                    if(entry.isDirectory()) { Files.createDirectories(target.toPath()); continue; }
                    Files.createDirectories(target.getParentFile().toPath());
                    try(InputStream in=zip.getInputStream(entry); OutputStream out=Files.newOutputStream(target.toPath(),StandardOpenOption.CREATE_NEW)) {
                        int n;
                        while((n=in.read(buffer))!=-1) {
                            total+=n;
                            if(total>MAX_BYTES) throw new IOException("ZIP expands beyond 200 MB");
                            out.write(buffer,0,n);
                        }
                    }
                    if(name.toLowerCase(Locale.ROOT).matches(".*\\.html?$") && !name.startsWith("__MACOSX/")) html.add(name);
                }
            }
            if(html.isEmpty()) throw new IOException("No HTML entry found. This player needs an exported web app; source projects need a build first.");
            html.sort(Comparator.comparingInt((String p)->p.toLowerCase(Locale.ROOT).endsWith("index.html")?0:1)
                .thenComparingInt(p->p.split("/").length).thenComparing(p->p));
            complete=true;
            return new Project(dest,html);
        } finally {
            if(!complete) remove(dest);
        }
    }
    private static void remove(File file) {
        File[] children=file.listFiles();
        if(children!=null) for(File child:children) remove(child);
        file.delete();
    }
}
