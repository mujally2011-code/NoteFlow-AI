package org.gradle.wrapper;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Minimal deterministic bootstrap launcher. Regenerate official wrapper with `gradle wrapper` once installed. */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        File project = new File(System.getProperty("user.dir"));
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(new File(project,"gradle/wrapper/gradle-wrapper.properties"))) {properties.load(in);}
        String url = Objects.requireNonNull(properties.getProperty("distributionUrl"));
        String expected = Objects.requireNonNull(properties.getProperty("distributionSha256Sum"));
        if(!url.startsWith("https://services.gradle.org/distributions/gradle-8.11.1-bin.zip")) throw new SecurityException("Unexpected Gradle distribution URL");
        File cache = new File(System.getProperty("user.home"),".gradle/noteflow-bootstrap");
        cache.mkdirs();
        File executable = new File(cache,"gradle-8.11.1/bin/"+(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"gradle.bat":"gradle"));
        if(!executable.isFile()){
            File archive = new File(cache,"gradle-8.11.1-bin.zip");
            if(!archive.isFile()){
                File temp = File.createTempFile("gradle-", ".zip",cache);
                try(InputStream in=URI.create(url).toURL().openStream();OutputStream out=new FileOutputStream(temp)){in.transferTo(out);}
                Files.move(temp.toPath(),archive.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(archive)){
                byte[] bytes=new byte[65536];int n;
                while((n=in.read(bytes))!=-1)hash.update(bytes,0,n);
            }
            StringBuilder got=new StringBuilder();for(byte b:hash.digest())got.append(String.format("%02x",b));
            if(!got.toString().equals(expected)) {Files.deleteIfExists(archive.toPath());throw new SecurityException("Gradle download SHA-256 mismatch");}
            try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))){
                ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                    File target=new File(cache,entry.getName());
                    if(!target.getCanonicalPath().startsWith(cache.getCanonicalPath()+File.separator))throw new SecurityException("Zip path traversal");
                    if(entry.isDirectory())target.mkdirs();
                    else{target.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(target)){zip.transferTo(out);}}
                }
            }
            executable.setExecutable(true);
        }
        ArrayList<String> command=new ArrayList<>();command.add(executable.getAbsolutePath());Collections.addAll(command,args);
        Process proc=new ProcessBuilder(command).directory(project).inheritIO().start();
        System.exit(proc.waitFor());
    }
}
