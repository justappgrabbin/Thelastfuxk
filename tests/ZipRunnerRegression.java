package app.lastfuck;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public final class ZipRunnerRegression {
    static File archive(File dir,String name,String... paths) throws Exception {
        File file=new File(dir,name);
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file))) {
            for(String path:paths) { out.putNextEntry(new ZipEntry(path)); out.write("test".getBytes("UTF-8")); out.closeEntry(); }
        }
        return file;
    }
    static void require(boolean ok) { if(!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        File dir=Files.createTempDirectory("zip-regression").toFile(), projects=new File(dir,"projects");
        File zip=archive(dir,"web.zip","My App/about.htm","My App/index.html","My App/js/app.js","My App/game.wasm");
        ZipRunner.Project first=ZipRunner.unpack(zip,projects), second=ZipRunner.unpack(zip,projects);
        require(first.entries.get(0).equals("My App/index.html"));
        require(new File(first.root,"My App/js/app.js").isFile());
        require(!first.root.equals(second.root) && zip.isFile());
        for(String path:new String[]{"../escape.html","/escape.html","dir/../../escape.html","dir\\escape.html","plain.txt"}) {
            int before=projects.list().length;
            try { ZipRunner.unpack(archive(dir,"bad.zip",path),projects); throw new AssertionError("Accepted "+path); }
            catch(IOException expected) { require(projects.list().length==before); }
        }
        String[] many=new String[ZipRunner.MAX_ENTRIES+1];
        for(int i=0;i<many.length;i++) many[i]="file"+i+".html";
        try { ZipRunner.unpack(archive(dir,"many.zip",many),projects); throw new AssertionError("Accepted too many entries"); }
        catch(IOException expected) { }
        require(!new File(dir,"escape.html").exists());
        System.out.println("ZIP runner: nested assets, entry selection, preserved imports, traversal rejection, entry limits, cleanup passed");
    }
}
