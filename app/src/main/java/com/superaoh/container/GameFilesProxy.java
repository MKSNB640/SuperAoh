package com.superaoh.container;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * SuperAoh 资源接管层。
 *
 * 让游戏 DEX 在 SuperAoh 进程内运行时：
 *   Gdx.files.internal("...") -> 读用户 APK 的 assets/（而非 SuperAoh 自己的）
 *   Gdx.files.local("...")    -> 隔离到当前工作区，避免不同游戏版本互相覆盖存档
 *
 * 读取优先级（高 -> 低）：
 *   1. 覆盖层   &lt;workspace&gt;/overlay/assets/...   AI 生成的资源补丁
 *   2. 热缓存   &lt;cache&gt;/hot/&lt;fingerprint&gt;/...     从 APK 解压过的热点文件
 *   3. APK 内层 assets/...                        虚拟文件系统，流式读取
 *   4. 宿主兜底 original.internal(...)
 */
public final class GameFilesProxy implements Files {

    private final Files original;
    private final ZipFile zip;
    private final ZipAssetIndex index;
    private final File extractionRoot;
    private final File localDir;
    private final File overlayAssetsDir;
    private final File hotDir;

    public GameFilesProxy(Files original,
                          ZipFile zip,
                          File extractionRoot,
                          File localDir,
                          File overlayAssetsDir,
                          File hotDir) {
        this.original = original;
        this.zip = zip;
        this.index = new ZipAssetIndex(zip);
        this.extractionRoot = extractionRoot;
        this.localDir = localDir;
        this.overlayAssetsDir = overlayAssetsDir;
        this.hotDir = hotDir;
    }

    /** 安装到 Gdx.files，返回被替换掉的原对象。 */
    public Files install() {
        Files previous = Gdx.files;
        Gdx.files = this;
        return previous;
    }

    public int getAssetFileCount() {
        return index.fileCount;
    }

    public int getAssetDirCount() {
        return index.dirCount;
    }

    public List<String> topLevelAssets() {
        return index.children("");
    }

    public ZipAssetIndex getIndex() {
        return index;
    }

    public ZipFile getZip() {
        return zip;
    }

    // ---------------- Files 接口 ----------------

    @Override
    public FileHandle internal(String path) {
        String normalized = ZipAssetIndex.normalize(path);

        // 1. 覆盖层
        if (overlayAssetsDir != null) {
            File overlay = new File(overlayAssetsDir, normalized);
            if (overlay.isFile()) {
                return original.absolute(overlay.getAbsolutePath());
            }
        }

        // 2. 热缓存
        if (hotDir != null) {
            File hot = new File(hotDir, normalized);
            if (hot.isFile()) {
                return original.absolute(hot.getAbsolutePath());
            }
        }

        // 3. APK 内 assets/
        if (index.exists(normalized)) {
            if (index.isDirectory(normalized)) {
                return new ZipFileHandle(index, normalized, extractionRoot, zip);
            }
            if (needsRealFile(normalized)) {
                File extracted = new ZipFileHandle(index, normalized, extractionRoot, zip).file();
                return original.absolute(extracted.getAbsolutePath());
            }
            return new ZipFileHandle(index, normalized, extractionRoot, zip);
        }

        // 4. 兜底
        return original.internal(path);
    }

    /** 读取全部字节（Java 8 兼容，替代 InputStream.readAllBytes）。 */
    static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static boolean needsRealFile(String path) {
        return path.startsWith("music/")
                || path.startsWith("sounds/")
                || path.endsWith(".mp3")
                || path.endsWith(".ogg")
                || path.endsWith(".wav")
                || path.endsWith(".ttf");
    }

    @Override
    public FileHandle getFileHandle(String path, FileType type) {
        switch (type) {
            case Internal:
                return internal(path);
            case Local:
                return local(path);
            default:
                return original.getFileHandle(path, type);
        }
    }

    @Override
    public FileHandle local(String path) {
        if (localDir == null) {
            return original.local(path);
        }
        return original.absolute(new File(localDir, ZipAssetIndex.normalize(path)).getAbsolutePath());
    }

    @Override
    public FileHandle external(String path) {
        return original.external(path);
    }

    @Override
    public FileHandle absolute(String path) {
        return original.absolute(path);
    }

    @Override
    public FileHandle classpath(String path) {
        return original.classpath(path);
    }

    @Override
    public String getLocalStoragePath() {
        return localDir != null ? localDir.getAbsolutePath() : original.getLocalStoragePath();
    }

    @Override
    public String getExternalStoragePath() {
        return original.getExternalStoragePath();
    }

    @Override
    public boolean isExternalStorageAvailable() {
        return original.isExternalStorageAvailable();
    }

    @Override
    public boolean isLocalStorageAvailable() {
        return original.isLocalStorageAvailable();
    }

    // ---------------- 虚拟 ZIP 索引 ----------------

    public static final class ZipAssetIndex {
        private final ConcurrentHashMap<String, ZipEntry> files = new ConcurrentHashMap<>();
        private final Set<String> directories = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<String, Set<String>> children = new ConcurrentHashMap<>();
        final int fileCount;
        final int dirCount;

        ZipAssetIndex(ZipFile zip) {
            directories.add("");
            children.putIfAbsent("", ConcurrentHashMap.newKeySet());
            java.util.Enumeration<? extends java.util.zip.ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                String name = entry.getName();
                if (!name.startsWith("assets/")) {
                    continue;
                }
                String relative = normalize(name.substring("assets/".length()));
                if (relative.isEmpty()) {
                    continue;
                }
                addPath(relative, entry);
            }
            fileCount = files.size();
            dirCount = directories.size();
        }

        private void addPath(String path, ZipEntry entry) {
            String[] parts = path.split("/");
            String parent = "";
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                String current = parent.isEmpty() ? part : parent + "/" + part;
                children.computeIfAbsent(parent, k -> ConcurrentHashMap.newKeySet()).add(part);
                if (i < parts.length - 1 || entry.isDirectory()) {
                    directories.add(current);
                    children.putIfAbsent(current, ConcurrentHashMap.newKeySet());
                } else {
                    files.put(current, entry);
                }
                parent = current;
            }
        }

        public boolean exists(String path) {
            return files.containsKey(path) || directories.contains(path);
        }

        public boolean isDirectory(String path) {
            return directories.contains(path);
        }

        public ZipEntry entry(String path) {
            return files.get(path);
        }

        public List<String> children(String path) {
            Set<String> set = children.get(path);
            if (set == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(new TreeSet<>(set));
        }

        /** 安全归一化：拒绝 .. 越界，丢弃空段与 . */
        public static String normalize(String raw) {
            if (raw == null) {
                return "";
            }
            String[] parts = raw.replace('\\', '/').split("/");
            List<String> clean = new ArrayList<>(parts.length);
            for (String part : parts) {
                if (part.isEmpty() || ".".equals(part)) {
                    continue;
                }
                if ("..".equals(part)) {
                    return "";
                }
                clean.add(part);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < clean.size(); i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(clean.get(i));
            }
            return sb.toString();
        }
    }

    // ---------------- ZIP 虚拟 FileHandle ----------------

    static final class ZipFileHandle extends FileHandle {
        private final ZipAssetIndex index;
        private final String virtualPath;
        private final File extractionRoot;
        private final ZipFile zip;

        ZipFileHandle(ZipAssetIndex index, String virtualPath, File extractionRoot, ZipFile zip) {
            this.index = index;
            this.virtualPath = virtualPath;
            this.extractionRoot = extractionRoot;
            this.zip = zip;
        }

        @Override
        public FileType type() {
            return FileType.Internal;
        }

        @Override
        public String path() {
            return virtualPath;
        }

        @Override
        public String name() {
            if (virtualPath.isEmpty()) {
                return "";
            }
            return virtualPath.substring(virtualPath.lastIndexOf('/') + 1);
        }

        @Override
        public String extension() {
            String n = name();
            int i = n.lastIndexOf('.');
            return i <= 0 ? "" : n.substring(i + 1);
        }

        @Override
        public String nameWithoutExtension() {
            String n = name();
            int i = n.lastIndexOf('.');
            return i <= 0 ? n : n.substring(0, i);
        }

        @Override
        public String pathWithoutExtension() {
            int i = virtualPath.lastIndexOf('.');
            return i <= 0 ? virtualPath : virtualPath.substring(0, i);
        }

        @Override
        public boolean exists() {
            return index.exists(virtualPath);
        }

        @Override
        public boolean isDirectory() {
            return index.isDirectory(virtualPath);
        }

        @Override
        public long length() {
            ZipEntry e = index.entry(virtualPath);
            return e == null ? 0L : e.getSize();
        }

        @Override
        public long lastModified() {
            ZipEntry e = index.entry(virtualPath);
            return e == null ? 0L : e.getTime();
        }

        @Override
        public InputStream read() {
            if (isDirectory()) {
                throw new GdxRuntimeException("Cannot read a virtual directory: " + virtualPath);
            }
            ZipEntry entry = index.entry(virtualPath);
            if (entry == null) {
                throw new GdxRuntimeException("Asset not found: " + virtualPath);
            }
            try {
                return zip.getInputStream(entry);
            } catch (Exception e) {
                throw new GdxRuntimeException("Error reading asset: " + virtualPath, e);
            }
        }

        @Override
        public byte[] readBytes() {
            try (InputStream in = read()) {
                return readAll(in);
            } catch (java.io.IOException e) {
                throw new GdxRuntimeException("Error reading asset bytes: " + virtualPath, e);
            }
        }

        /** 只有下游强制要求 java.io.File 时才真正落盘。 */
        @Override
        public File file() {
            String normalized = ZipAssetIndex.normalize(virtualPath);
            File target = new File(extractionRoot, normalized);
            try {
                String rootPath = extractionRoot.getCanonicalFile().getPath();
                String targetPath = target.getCanonicalFile().getPath();
                if (!targetPath.equals(rootPath)
                        && !targetPath.startsWith(rootPath + File.separator)) {
                    throw new GdxRuntimeException("Unsafe asset path: " + virtualPath);
                }
            } catch (java.io.IOException e) {
                throw new GdxRuntimeException("Path check failed: " + virtualPath, e);
            }

            if (isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                target.mkdirs();
                return target;
            }

            long expected = length();
            synchronized (index) {
                if (!target.exists() || target.length() != expected) {
                    File parent = target.getParentFile();
                    if (parent != null) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    File temp = new File(parent, target.getName() + ".tmp");
                    try (InputStream in = read();
                         FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] buf = new byte[256 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                        out.flush();
                    } catch (java.io.IOException e) {
                        throw new GdxRuntimeException("Extract failed: " + virtualPath, e);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    if (!temp.renameTo(target)) {
                        try {
                            java.nio.file.Files.copy(temp.toPath(), target.toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (java.io.IOException e) {
                            throw new GdxRuntimeException("Rename failed: " + virtualPath, e);
                        }
                        //noinspection ResultOfMethodCallIgnored
                        temp.delete();
                    }
                }
            }
            return target;
        }

        @Override
        public ByteBuffer map() {
            return map(FileChannel.MapMode.READ_ONLY);
        }

        @Override
        public ByteBuffer map(FileChannel.MapMode mode) {
            try (RandomAccessFile raf = new RandomAccessFile(file(),
                    mode == FileChannel.MapMode.READ_ONLY ? "r" : "rw")) {
                return raf.getChannel()
                        .map(mode, 0L, raf.length())
                        .order(java.nio.ByteOrder.nativeOrder());
            } catch (java.io.IOException e) {
                throw new GdxRuntimeException("Map failed: " + virtualPath, e);
            }
        }

        @Override
        public FileHandle[] list() {
            List<String> kids = index.children(virtualPath);
            FileHandle[] out = new FileHandle[kids.size()];
            for (int i = 0; i < kids.size(); i++) {
                out[i] = child(kids.get(i));
            }
            return out;
        }

        @Override
        public FileHandle[] list(String suffix) {
            FileHandle[] all = list();
            List<FileHandle> keep = new ArrayList<>(all.length);
            for (FileHandle h : all) {
                if (h.name().endsWith(suffix)) {
                    keep.add(h);
                }
            }
            return keep.toArray(new FileHandle[0]);
        }

        @Override
        public FileHandle[] list(FileFilter filter) {
            FileHandle[] all = list();
            List<FileHandle> keep = new ArrayList<>(all.length);
            for (FileHandle h : all) {
                if (filter.accept(h.file())) {
                    keep.add(h);
                }
            }
            return keep.toArray(new FileHandle[0]);
        }

        @Override
        public FileHandle[] list(FilenameFilter filter) {
            FileHandle[] all = list();
            List<FileHandle> keep = new ArrayList<>(all.length);
            for (FileHandle h : all) {
                if (filter.accept(h.file(), h.name())) {
                    keep.add(h);
                }
            }
            return keep.toArray(new FileHandle[0]);
        }

        @Override
        public FileHandle child(String name) {
            String childPath = ZipAssetIndex.normalize(
                    virtualPath.isEmpty() ? name : virtualPath + "/" + name);
            return new ZipFileHandle(index, childPath, extractionRoot, zip);
        }

        @Override
        public FileHandle sibling(String name) {
            if (virtualPath.isEmpty()) {
                throw new GdxRuntimeException("Cannot get sibling of ZIP root");
            }
            int slash = virtualPath.lastIndexOf('/');
            String parent = slash < 0 ? "" : virtualPath.substring(0, slash);
            String childPath = ZipAssetIndex.normalize(
                    parent.isEmpty() ? name : parent + "/" + name);
            return new ZipFileHandle(index, childPath, extractionRoot, zip);
        }

        @Override
        public FileHandle parent() {
            int slash = virtualPath.lastIndexOf('/');
            String parentPath = slash < 0 ? "" : virtualPath.substring(0, slash);
            return new ZipFileHandle(index, parentPath, extractionRoot, zip);
        }

        @Override
        public String toString() {
            return "zip:" + virtualPath;
        }
    }
}