package com.yourapp.drama.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Component
public class NovelStorage {
    private final Path root;
    public NovelStorage(@Value("${novel.storage.root:./data/novels}")String root){this.root=Path.of(root).toAbsolutePath().normalize();}
    public record StoredPart(long size,String sha256){}
    public StoredPart writePart(String uploadId,int partNo,InputStream source,long maxBytes){Path target=part(uploadId,partNo);try{Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),".part-",".tmp");MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;try(OutputStream output=Files.newOutputStream(temp)){byte[] buffer=new byte[64*1024];for(int read;(read=source.read(buffer))>=0;){if(read==0)continue;size+=read;if(size>maxBytes)throw new IllegalArgumentException("上传分片超过允许大小");digest.update(buffer,0,read);output.write(buffer,0,read);}}try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}return new StoredPart(size,HexFormat.of().formatHex(digest.digest()));}catch(Exception e){if(e instanceof RuntimeException runtime)throw runtime;throw new UncheckedIOException("保存小说分片失败",e instanceof IOException io?io:new IOException(e));}}
    public StoredPart assemble(String uploadId,int totalParts,String storageKey){Path target=source(storageKey);try{Files.createDirectories(target.getParent());Path temp=Files.createTempFile(target.getParent(),".novel-",".tmp");MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;try(OutputStream output=Files.newOutputStream(temp)){byte[] buffer=new byte[64*1024];for(int part=1;part<=totalParts;part++)try(InputStream input=Files.newInputStream(part(uploadId,part))){for(int read;(read=input.read(buffer))>=0;){if(read==0)continue;size+=read;digest.update(buffer,0,read);output.write(buffer,0,read);}}}try{Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}return new StoredPart(size,HexFormat.of().formatHex(digest.digest()));}catch(Exception e){if(e instanceof RuntimeException runtime)throw runtime;throw new UncheckedIOException("合并小说分片失败",e instanceof IOException io?io:new IOException(e));}}
    public Path source(String key){if(key==null||!key.matches("[0-9a-fA-F-]{36}[.](txt|docx|epub)"))throw new IllegalArgumentException("小说存储标识无效");return safe(root.resolve("sources").resolve(key));}
    public void cancel(String uploadId){Path dir=safe(root.resolve("uploads").resolve(uuid(uploadId)));if(!Files.exists(dir))return;try(var paths=Files.walk(dir)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException e){throw new UncheckedIOException(e);}});}catch(IOException e){throw new UncheckedIOException(e);}}
    private Path part(String uploadId,int partNo){if(partNo<1)throw new IllegalArgumentException("partNo 必须从 1 开始");return safe(root.resolve("uploads").resolve(uuid(uploadId)).resolve(String.format("%08d.part",partNo)));}
    private Path safe(Path path){Path result=path.toAbsolutePath().normalize();if(!result.startsWith(root))throw new IllegalArgumentException("文件超出小说存储目录");return result;}
    private static String uuid(String value){return UUID.fromString(value).toString();}
}
