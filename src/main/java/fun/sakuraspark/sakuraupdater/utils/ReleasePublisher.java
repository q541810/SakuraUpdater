package fun.sakuraspark.sakuraupdater.utils;

import fun.sakuraspark.sakuraupdater.config.DataConfig.FileData;
import fun.sakuraspark.sakuraupdater.config.DataConfig.PathData;
import fun.sakuraspark.sakuraupdater.config.IGetReleaseDir;
import fun.sakuraspark.sakuraupdater.config.IGetSyncDirs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 发布目录（"快照"）的唯一入口。
 *
 * <p>
 * 只有 {@code commit} 与 {@code data repair} 会写入发布目录：它们把 {@code SYNC_DIR} 里那些
 * 手工编辑的源目录镜像成一份发布副本，名单再按这份副本生成；文件服务器也只从发布目录发文件。
 * 这样一来，"改了源目录却没 commit" 再也不会让客户端拿到一个发不出去的文件（404）或
 * 与名单对不上的内容（md5 不匹配），从根上消除了无限更新。
 *
 * <p>
 * <b>名单格式与旧版完全一致</b>：{@code sourcePath} 仍然记源目录里的原始路径（例如
 * {@code clientconfig/foo.toml}），{@code targetPath} 仍然记客户端路径（例如 {@code config/foo.toml}）；
 * 只有 md5/size 改为从发布副本读取，保证"名单里的 md5 一定是服务端真能发出去的字节"。
 * 客户端下载时照旧回传 {@code sourcePath}，服务端在名单里查到这条记录后，用它的 {@code targetPath}
 * 去发布目录取文件——因此升级不改变任何下线格式，用了 {@code config:push:clientconfig}
 * 这类"源目录和目标目录不同名"配置的服务器也无感。
 */
public final class ReleasePublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReleasePublisher.class);

    /** 复制文件用的临时文件前缀；位于发布目录根部，不会被扫描进名单。 */
    private static final String TMP_PREFIX = ".sakura-tmp-";

    /** 临时文件名的序号：多个工作线程同时复制，不能只靠时间戳区分。 */
    private static final AtomicLong TMP_COUNTER = new AtomicLong();

    /** 复制与计算 MD5 的并行度。瓶颈是磁盘，开太多没有好处。 */
    private static final int WORKERS = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));

    /**
     * 发布互斥锁：发布、写库（commit）、repair 都在它里面执行，同一时间只允许一个。
     * 用 tryLock 而不是排队等待——并发的第二个操作直接被拒绝，管理员马上知道发生了什么。
     * 可重入：commit 持锁期间再调用 {@link #apply(Plan)} 不会死锁。
     */
    public static final ReentrantLock LOCK = new ReentrantLock();

    /** 发布目录现在是否可用（存在且非空）。由启动检查和成功发布来刷新。 */
    private static volatile boolean published = false;

    private ReleasePublisher() {
    }

    /** 一个可发布的 SYNC_DIR 条目（{@code ignore} 条目不在其中）。 */
    public static final class Entry {
        public final String targetPath;
        public final String model;
        public final List<String> sources;

        Entry(String targetPath, String model, List<String> sources) {
            this.targetPath = targetPath;
            this.model = model;
            this.sources = sources;
        }
    }

    /** 计划里的一个文件：复制用谁、名单里写什么。 */
    public static final class PlannedFile {
        /** 源目录里的实际文件（复制来源） */
        public final Path source;
        /** 名单里 sourcePath 该写的字符串，与旧版 {@code getPathDataList} 的取值完全一致 */
        public final String livePath;

        PlannedFile(Path source, String livePath) {
            this.source = source;
            this.livePath = livePath;
        }
    }

    /** 一个条目的发布计划：这个 targetPath 下应该有哪些文件。 */
    public static final class EntryPlan {
        public final Entry entry;
        /** 发布目录内相对路径（即客户端路径） -> 计划文件 */
        public final Map<String, PlannedFile> files;

        EntryPlan(Entry entry, Map<String, PlannedFile> files) {
            this.entry = entry;
            this.files = files;
        }
    }

    /** 一次发布的计划：期望发布目录里存在哪些文件。 */
    public static final class Plan {
        public final Path releaseRoot;
        public final List<EntryPlan> entryPlans;

        Plan(Path releaseRoot, List<EntryPlan> entryPlans) {
            this.releaseRoot = releaseRoot;
            this.entryPlans = entryPlans;
        }

        public int fileCount() {
            int count = 0;
            for (EntryPlan entryPlan : entryPlans) {
                count += entryPlan.files.size();
            }
            return count;
        }
    }

    /** 发布结果（文件计数部分）。 */
    public static final class PublishResult {
        public final boolean success;
        public final String error;
        public final int plannedFiles;
        public final int copied;
        public final int updated;
        public final int deleted;

        private PublishResult(boolean success, String error, int plannedFiles, int copied, int updated, int deleted) {
            this.success = success;
            this.error = error;
            this.plannedFiles = plannedFiles;
            this.copied = copied;
            this.updated = updated;
            this.deleted = deleted;
        }

        static PublishResult failure(String error) {
            return new PublishResult(false, error, 0, 0, 0, 0);
        }

        static PublishResult ok(int plannedFiles, int copied, int updated, int deleted) {
            return new PublishResult(true, null, plannedFiles, copied, updated, deleted);
        }
    }

    /** 发布 + 生成名单的一次性结果，供 commit / data repair 使用。 */
    public static final class PublishOutcome {
        public final boolean success;
        public final String error;
        public final PublishResult publish;
        public final List<PathData> roster;

        private PublishOutcome(boolean success, String error, PublishResult publish, List<PathData> roster) {
            this.success = success;
            this.error = error;
            this.publish = publish;
            this.roster = roster;
        }

        static PublishOutcome failure(String error) {
            return new PublishOutcome(false, error, null, null);
        }

        static PublishOutcome ok(PublishResult publish, List<PathData> roster) {
            return new PublishOutcome(true, null, publish, roster);
        }
    }

    // ---- 路径与状态 ----

    /** 配置里的发布目录，已转成绝对规范路径。 */
    public static Path releaseRoot() {
        return Paths.get(IGetReleaseDir.getReleaseDir()).toAbsolutePath().normalize();
    }

    public static boolean isPublished() {
        return published;
    }

    /** 重新判断发布目录是否可用并缓存结果。启动时以及每次成功发布后调用。 */
    public static void refreshPublishedState() {
        Path root = releaseRoot();
        published = Files.isDirectory(root) && !FileUtils.getAllFiles(root.toFile()).isEmpty();
    }

    /**
     * 把客户端回传的文件路径限制在发布目录内部。
     *
     * @throws IOException 路径为空、为绝对路径、或通过 {@code ..} 逃出发布目录
     */
    public static Path resolveInRelease(Path releaseRoot, String requested) throws IOException {
        if (requested == null || requested.isBlank()) {
            throw new IOException("empty file path");
        }
        String cleaned = requested.replace('\\', '/');
        // "/x" 在 Windows 上 Paths.get().isAbsolute() 是 false，所以要单独挡掉
        if (cleaned.startsWith("/")) {
            throw new IOException("absolute path is not allowed: " + requested);
        }
        Path rel;
        try {
            rel = Paths.get(cleaned).normalize();
        } catch (InvalidPathException e) {
            throw new IOException("invalid file path: " + requested);
        }
        // 规范化后变成空（"."、"mods/.." 这类）或以 ".." 开头的一律拒绝
        if (rel.isAbsolute() || rel.startsWith("..") || rel.toString().isEmpty()) {
            throw new IOException("path escapes the release directory: " + requested);
        }
        Path root = releaseRoot.toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = root.resolve(rel).normalize();
        } catch (InvalidPathException e) {
            throw new IOException("invalid file path: " + requested);
        }
        if (!resolved.startsWith(root)) {
            throw new IOException("path escapes the release directory: " + requested);
        }
        // 目标若是符号链接，跟进真实路径再查一次，防止发布目录里的链接指到外面
        try {
            if (!resolved.toRealPath().startsWith(root.toRealPath())) {
                throw new IOException("path escapes the release directory through a symlink: " + requested);
            }
        } catch (NoSuchFileException e) {
            // 文件还不存在：交给调用方的存在性检查，规范化检查已经把住了边界
        }
        return resolved;
    }

    // ---- 解析 SYNC_DIR ----

    private static List<Entry> parseEntries(List<String> syncDirs) {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String syncDir : syncDirs) {
            if (syncDir == null) {
                continue;
            }
            List<String> parts = List.of(syncDir.split(":"));
            if (parts.size() < 2) {
                continue;
            }
            String targetPath = parts.get(0).trim();
            String model = parts.get(1).trim();
            if (model.equalsIgnoreCase("ignore") || targetPath.isEmpty()) {
                continue;
            }
            if (!seen.add(targetPath)) {
                continue; // 同一个 targetPath 只认第一个条目，与旧行为一致
            }
            List<String> sources = new ArrayList<>();
            if (parts.size() > 2) {
                for (int i = 2; i < parts.size(); i++) {
                    String source = parts.get(i).trim();
                    if (!source.isEmpty()) {
                        sources.add(source);
                    }
                }
            }
            if (sources.isEmpty()) {
                sources.add(targetPath);
            }
            entries.add(new Entry(targetPath, model, List.copyOf(sources)));
        }
        return entries;
    }

    private static List<String[]> parseIgnores(List<String> syncDirs) {
        List<String[]> ignores = new ArrayList<>();
        for (String syncDir : syncDirs) {
            if (syncDir == null) {
                continue;
            }
            List<String> parts = List.of(syncDir.split(":"));
            if (parts.size() < 2 || !parts.get(1).trim().equalsIgnoreCase("ignore")) {
                continue;
            }
            String targetPath = parts.get(0).trim();
            for (int i = 2; i < parts.size(); i++) {
                if (!parts.get(i).isEmpty()) {
                    ignores.add(new String[] { targetPath, parts.get(i) });
                }
            }
        }
        return ignores;
    }

    private static boolean isIgnored(List<String[]> ignores, String targetPath, String releaseRel) {
        for (String[] ignore : ignores) {
            if (ignore[0].equals(targetPath) && releaseRel.matches(ignore[1])) {
                return true;
            }
        }
        return false;
    }

    private static String joinReleasePath(String targetPath, String rel) throws IOException {
        String joined = (targetPath + "/" + rel).replace('\\', '/');
        while (joined.contains("//")) {
            joined = joined.replace("//", "/");
        }
        if (joined.startsWith("/") || joined.equals("..") || joined.contains("../") || joined.contains("/..")) {
            throw new IOException("invalid target path: " + targetPath + " + " + rel);
        }
        return joined;
    }

    // ---- 计划 / 发布 ----

    /**
     * 扫描源目录，算出"现在 commit 会在发布目录里放哪些文件"。
     *
     * @throws IOException SYNC_DIR 没有可用条目、源目录不存在、或发布目录与源目录相互包含
     */
    public static Plan plan(List<String> syncDirs, Path releaseRoot) throws IOException {
        Path root = releaseRoot.toAbsolutePath().normalize();
        List<Entry> entries = parseEntries(syncDirs);
        List<String[]> ignores = parseIgnores(syncDirs);
        if (entries.isEmpty()) {
            throw new IOException("no usable SYNC_DIR entry found, please check SYNC_DIR");
        }
        for (Entry entry : entries) {
            for (String source : entry.sources) {
                Path sourceRoot = Paths.get(source).toAbsolutePath().normalize();
                if (root.startsWith(sourceRoot) || sourceRoot.startsWith(root)) {
                    throw new IOException("RELEASE_DIR must not be inside, or contain, the sync source directory: "
                            + source);
                }
            }
        }
        List<EntryPlan> entryPlans = new ArrayList<>();
        for (Entry entry : entries) {
            Map<String, PlannedFile> files = new LinkedHashMap<>();
            for (String source : entry.sources) {
                Path sourceRoot = Paths.get(source).toAbsolutePath().normalize();
                if (!Files.isDirectory(sourceRoot)) {
                    // 宁可整体失败，也不要因为盘没挂上 / 路径写错，把发布目录里的东西当成"已删除"清掉。
                    // 不区分发布目录里有没有这个条目的历史内容：玩家配了这个条目多半是有意的，缺目录大多是路径写错
                    // 或盘没挂上，直接报错比悄悄放过省事。
                    String message = "sync source directory does not exist: " + source + " (SYNC_DIR entry '"
                            + entry.targetPath + "'). Nothing was published and the release directory and version "
                            + "records are untouched. Create the directory, or remove/fix this entry in SYNC_DIR.";
                    LOGGER.error(message);
                    throw new IOException(message);
                }
                for (File file : FileUtils.getAllFiles(new File(source))) {
                    Path absolute = file.toPath().toAbsolutePath().normalize();
                    if (!absolute.startsWith(sourceRoot)) {
                        continue;
                    }
                    String rel = sourceRoot.relativize(absolute).toString().replace(File.separatorChar, '/');
                    String releaseRel = joinReleasePath(entry.targetPath, rel);
                    if (isIgnored(ignores, entry.targetPath, releaseRel)) {
                        continue;
                    }
                    // sourcePath 与旧版 getPathDataList 完全一致：源目录配置串 + 相对路径
                    String livePath = file.getPath().replace(File.separatorChar, '/');
                    PlannedFile previous = files.put(releaseRel, new PlannedFile(absolute, livePath));
                    if (previous != null && !previous.source.equals(absolute)) {
                        LOGGER.warn("SYNC_DIR entry '{}': {} is provided by more than one source ({} and {}); "
                                + "the later one wins.", entry.targetPath, releaseRel, previous.source, absolute);
                    }
                }
            }
            entryPlans.add(new EntryPlan(entry, files));
        }
        warnAboutOverlappingEntries(entryPlans);
        return new Plan(root, List.copyOf(entryPlans));
    }

    /** 把所有条目的计划文件合成一张表（发布目录内相对路径 -> 文件）；同一路径出现多次时后者覆盖前者。 */
    private static Map<String, PlannedFile> mergedFiles(List<EntryPlan> entryPlans) {
        Map<String, PlannedFile> merged = new LinkedHashMap<>();
        for (EntryPlan entryPlan : entryPlans) {
            merged.putAll(entryPlan.files);
        }
        return merged;
    }

    /** 不同条目往发布目录的同一个路径发布了不同的源文件（例如 targetPath 嵌套）：后一个会覆盖前一个，提示一下。 */
    private static void warnAboutOverlappingEntries(List<EntryPlan> entryPlans) {
        Map<String, Path> owners = new LinkedHashMap<>();
        for (EntryPlan entryPlan : entryPlans) {
            for (Map.Entry<String, PlannedFile> entry : entryPlan.files.entrySet()) {
                Path previous = owners.put(entry.getKey(), entry.getValue().source);
                if (previous != null && !previous.equals(entry.getValue().source)) {
                    LOGGER.warn("SYNC_DIR entry '{}' publishes {} from {}, which overrides {} from an earlier entry. "
                            + "Check whether your SYNC_DIR entries overlap by mistake.",
                            entryPlan.entry.targetPath, entry.getKey(), entry.getValue().source, previous);
                }
            }
        }
    }

    /** 按计划把发布目录镜像成期望状态：缺失的复制、变过的覆盖、多余的删除。 */
    public static PublishResult apply(Plan plan) {
        return apply(plan, PublishProgress.NONE);
    }

    /** 同 {@link #apply(Plan)}，复制过程中按字节汇报进度（回调可能来自多个工作线程）。 */
    public static PublishResult apply(Plan plan, PublishProgress.Listener progress) {
        if (!LOCK.tryLock()) {
            LOGGER.warn("Another publish / commit / repair is already running, refusing to start a second one.");
            return PublishResult.failure("Another publish, commit or repair is already running, try again later.");
        }
        try {
            return applyLocked(plan, progress);
        } finally {
            LOCK.unlock();
        }
    }

    private static PublishResult applyLocked(Plan plan, PublishProgress.Listener progress) {
        int deleted = 0;
        try {
            Files.createDirectories(plan.releaseRoot);
            deleteStaleTempFiles(plan.releaseRoot);
            // 所有条目合成一张表：复制每个路径只做一次，清理也按这张总表判断。
            // 只看单个条目自己的文件表会把 targetPath 嵌套时内层条目的文件（如 config 与 config/sub）当成多余的删掉
            Map<String, PlannedFile> wanted = mergedFiles(plan.entryPlans);
            // 先挑出需要复制的文件（只看大小和修改时间，很快），再并行复制
            List<CopyTask> tasks = new ArrayList<>();
            long totalBytes = 0;
            for (Map.Entry<String, PlannedFile> entry : wanted.entrySet()) {
                Path dest = plan.releaseRoot.resolve(entry.getKey()).normalize();
                if (!dest.startsWith(plan.releaseRoot)) {
                    continue;
                }
                boolean exists = Files.exists(dest);
                if (!exists || needsCopy(entry.getValue().source, dest)) {
                    tasks.add(new CopyTask(entry.getValue().source, dest, exists));
                    totalBytes += Files.size(entry.getValue().source);
                }
            }
            AtomicInteger copied = new AtomicInteger();
            AtomicInteger updated = new AtomicInteger();
            AtomicLong doneBytes = new AtomicLong();
            final long total = totalBytes;
            List<Callable<Void>> jobs = new ArrayList<>();
            for (CopyTask task : tasks) {
                jobs.add(() -> {
                    copyFile(task.source, task.dest, plan.releaseRoot, bytes -> progress
                            .onProgress(PublishProgress.COPYING, doneBytes.addAndGet(bytes), total));
                    (task.replacing ? updated : copied).incrementAndGet();
                    return null;
                });
            }
            runAll(jobs);
            // 只在配置里声明的 targetPath 子目录内清理，避免 RELEASE_DIR 配错时误删无关目录
            for (EntryPlan entryPlan : plan.entryPlans) {
                Path dir = plan.releaseRoot.resolve(entryPlan.entry.targetPath).normalize();
                if (!dir.startsWith(plan.releaseRoot) || !Files.isDirectory(dir)) {
                    continue;
                }
                for (File file : FileUtils.getAllFiles(dir.toFile())) {
                    Path absolute = file.toPath().toAbsolutePath().normalize();
                    if (!absolute.startsWith(plan.releaseRoot)) {
                        continue;
                    }
                    String rel = plan.releaseRoot.relativize(absolute).toString().replace(File.separatorChar, '/');
                    if (!wanted.containsKey(rel)) {
                        Files.deleteIfExists(absolute);
                        deleted++;
                    }
                }
                deleteEmptyDirectories(dir);
            }
            published = true;
            return PublishResult.ok(plan.fileCount(), copied.get(), updated.get(), deleted);
        } catch (Exception e) {
            LOGGER.error("Failed to publish the release snapshot to {}", plan.releaseRoot, e);
            return PublishResult.failure("Failed to publish the release snapshot: " + e);
        }
    }

    private static final class CopyTask {
        final Path source;
        final Path dest;
        /** true = 覆盖已存在的旧文件（计入 updated），false = 新增（计入 copied） */
        final boolean replacing;

        CopyTask(Path source, Path dest, boolean replacing) {
            this.source = source;
            this.dest = dest;
            this.replacing = replacing;
        }
    }

    /**
     * 用固定大小的线程池并行执行；任何一个失败就取消其余并把第一个错误抛出来。
     * 返回时所有工作线程都已停下，调用方可以放心清理现场。
     */
    private static void runAll(List<Callable<Void>> jobs) throws IOException {
        if (jobs.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(WORKERS, jobs.size()), runnable -> {
            Thread thread = new Thread(runnable, "SakuraUpdater-publish");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> job : jobs) {
                futures.add(pool.submit(job));
            }
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while publishing", e);
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 用当前配置执行一次发布并生成名单（commit / data repair 共用这一条路）。 */
    public static PublishOutcome publishAndScan() {
        return publishAndScan(PublishProgress.NONE);
    }

    /** 同 {@link #publishAndScan()}，复制与计算 MD5 两个阶段都会汇报进度。 */
    public static PublishOutcome publishAndScan(PublishProgress.Listener progress) {
        Plan plan;
        try {
            plan = plan(IGetSyncDirs.getSyncDirs(), releaseRoot());
        } catch (IOException e) {
            return PublishOutcome.failure(e.getMessage());
        }
        if (plan.fileCount() == 0) {
            // 发布空快照会把已发布的文件全删掉，让名单里每一项都发不出去——正好是要避免的死循环
            return PublishOutcome.failure("Refusing to publish: the sync directories are empty, or every file in "
                    + "them is excluded by an 'ignore' rule. Check SYNC_DIR.");
        }
        PublishResult publish = apply(plan, progress);
        if (!publish.success) {
            return PublishOutcome.failure(publish.error);
        }
        try {
            return PublishOutcome.ok(publish, buildRoster(plan, progress));
        } catch (RuntimeException e) {
            LOGGER.error("Failed to build the file list from the published snapshot", e);
            return PublishOutcome.failure("Failed to build the file list from the published snapshot: " + e);
        }
    }

    /**
     * 只比大小与修改时间，不比内容：复制时会把源文件的修改时间一并带过去，所以两者都相等基本就是同一份文件，
     * 不值得为此每次发布多读一遍所有文件。极端情况（内容变了但大小和修改时间都没变）下发布目录会保留旧内容。
     */
    private static boolean needsCopy(Path source, Path dest) throws IOException {
        if (Files.size(source) != Files.size(dest)) {
            return true;
        }
        return Files.getLastModifiedTime(source).toMillis() != Files.getLastModifiedTime(dest).toMillis();
    }

    private static void copyFile(Path source, Path dest, Path releaseRoot, LongConsumer progress)
            throws IOException {
        Files.createDirectories(dest.getParent());
        // 先写临时文件再改名：下载中的客户端要么看到旧文件、要么看到新文件，不会读到写了一半的内容
        Path tmp = releaseRoot.resolve(TMP_PREFIX + TMP_COUNTER.incrementAndGet());
        try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                progress.accept(read);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            moveWithRetry(tmp, dest);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            Files.setLastModifiedTime(dest, Files.getLastModifiedTime(source));
        } catch (IOException e) {
            LOGGER.debug("Could not copy the last-modified time of {} to {}", source, dest, e);
        }
    }

    private static void moveWithRetry(Path from, Path to) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e; // Windows 上目标文件正被下载占着时会短暂拒绝替换，等一会儿再试
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    private static void deleteStaleTempFiles(Path releaseRoot) throws IOException {
        try (var stream = Files.list(releaseRoot)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path) && path.getFileName().toString().startsWith(TMP_PREFIX)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void deleteEmptyDirectories(Path releaseRoot) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.walk(releaseRoot)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        }
        // 从最深的开始删，父目录才可能在子目录删完后变空
        dirs.sort(Comparator.comparingInt((Path p) -> p.getNameCount()).reversed());
        for (Path dir : dirs) {
            if (dir.equals(releaseRoot)) {
                continue;
            }
            try (var stream = Files.list(dir)) {
                if (stream.findAny().isEmpty()) {
                    Files.delete(dir);
                }
            }
        }
    }

    // ---- 名单 ----

    /**
     * 按发布计划生成名单。md5/size 从发布副本读取——名单描述的必须是服务端真能发出去的字节；
     * sourcePath 保留源目录里的原始路径，与旧版格式一致。
     */
    public static List<PathData> buildRoster(Plan plan) {
        return buildRoster(plan, PublishProgress.NONE);
    }

    /** 同 {@link #buildRoster(Plan)}，并行计算 MD5 并按字节汇报进度。名单里的顺序与计划一致。 */
    public static List<PathData> buildRoster(Plan plan, PublishProgress.Listener progress) {
        List<PathData> result = new ArrayList<>();
        List<FileData> allFiles = new ArrayList<>();
        long totalBytes = 0;
        for (EntryPlan entryPlan : plan.entryPlans) {
            PathData pathData = new PathData();
            pathData.model = entryPlan.entry.model;
            pathData.targetPath = entryPlan.entry.targetPath;
            pathData.files = new ArrayList<>();
            for (Map.Entry<String, PlannedFile> entry : entryPlan.files.entrySet()) {
                File publishedFile = plan.releaseRoot.resolve(entry.getKey()).normalize().toFile();
                FileData fileData = new FileData();
                fileData.sourcePath = entry.getValue().livePath;
                fileData.targetPath = entry.getKey();
                fileData.size = publishedFile.length();
                totalBytes += fileData.size;
                pathData.files.add(fileData);
                allFiles.add(fileData);
            }
            result.add(pathData);
        }
        AtomicLong doneBytes = new AtomicLong();
        final long total = totalBytes;
        List<Callable<Void>> jobs = new ArrayList<>();
        for (FileData fileData : allFiles) {
            File publishedFile = plan.releaseRoot.resolve(fileData.targetPath).normalize().toFile();
            jobs.add(() -> {
                fileData.md5 = MD5.calculateMD5(publishedFile, bytes -> progress
                        .onProgress(PublishProgress.HASHING, doneBytes.addAndGet(bytes), total));
                return null;
            });
        }
        try {
            runAll(jobs);
        } catch (IOException e) {
            throw new RuntimeException("Failed to build the file list: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 扫描发布目录本身的实际内容（发布目录内相对路径），供 {@code data diff}
     * 拿"实际能发出去的东西"和数据库里的名单做对比。只按 targetPath 比较，sourcePath 无意义。
     */
    public static List<PathData> scanRelease(List<String> syncDirs, Path releaseRoot) {
        Path root = releaseRoot.toAbsolutePath().normalize();
        List<PathData> result = new ArrayList<>();
        List<String[]> ignores = parseIgnores(syncDirs);
        for (Entry entry : parseEntries(syncDirs)) {
            PathData pathData = new PathData();
            pathData.model = entry.model;
            pathData.targetPath = entry.targetPath;
            pathData.files = new ArrayList<>();
            Path dir = root.resolve(entry.targetPath).normalize();
            if (dir.startsWith(root)) {
                for (File file : FileUtils.getAllFiles(dir.toFile())) {
                    Path absolute = file.toPath().toAbsolutePath().normalize();
                    if (!absolute.startsWith(root)) {
                        continue;
                    }
                    String rel = root.relativize(absolute).toString().replace(File.separatorChar, '/');
                    if (isIgnored(ignores, entry.targetPath, rel)) {
                        continue;
                    }
                    FileData fileData = new FileData();
                    fileData.sourcePath = rel;
                    fileData.targetPath = rel;
                    fileData.md5 = MD5.calculateMD5(file);
                    fileData.size = file.length();
                    pathData.files.add(fileData);
                }
            }
            result.add(pathData);
        }
        return result;
    }
}
