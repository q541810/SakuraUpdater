package fun.sakuraspark.sakuraupdater;

import fun.sakuraspark.sakuraupdater.config.DataConfig;
import fun.sakuraspark.sakuraupdater.config.DataConfig.FileData;
import fun.sakuraspark.sakuraupdater.config.DataConfig.PathData;
import fun.sakuraspark.sakuraupdater.network.FileServer;
import fun.sakuraspark.sakuraupdater.utils.MD5;
import fun.sakuraspark.sakuraupdater.utils.PublishProgress;
import fun.sakuraspark.sakuraupdater.utils.ReleasePublisher;
import fun.sakuraspark.sakuraupdater.utils.ServerCommandsHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通过 releasePublisherTest 运行，只用 JDK + slf4j。
 *
 * <p>
 * 重点回归："改了源目录却没 commit" 不能再让客户端拿到发不出去的文件（404）或与名单对不上的内容
 * （md5 不匹配）——因为名单和客户端下载都走发布目录，而发布目录只在提交时才变。
 */
public final class ReleasePublisherTest {

    private static final Path WORK = Paths.get("build", "sakuraupdater-release-test");
    private static final Path LIVE = WORK.resolve("live");
    private static final Path RELEASE = WORK.resolve("release");

    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        resetWorkDir();
        Files.createDirectories(LIVE.resolve("mods/sub"));
        Files.createDirectories(LIVE.resolve("cfg"));
        write(LIVE.resolve("mods/foo.jar"), "foo-v1");
        write(LIVE.resolve("mods/sub/bar.txt"), "bar-v1");
        write(LIVE.resolve("mods/skip-me.jar"), "ignored");
        write(LIVE.resolve("cfg/client.toml"), "cfg-v1");

        List<String> syncDirs = new ArrayList<>(List.of(
                // 不用绝对路径：Windows 的盘符冒号会被配置格式里的 ':' 切开
                "mods:mirror:" + rel(LIVE.resolve("mods")),
                "config:push:" + rel(LIVE.resolve("cfg")),
                "mods:ignore:.*skip-me\\.jar$"));
        String releaseDir = rel(RELEASE);

        // ---- 首次发布 ----
        ReleasePublisher.Plan plan = ReleasePublisher.plan(syncDirs, Paths.get(releaseDir));
        require(plan.fileCount() == 3, "被 ignore 的文件不进计划");
        ReleasePublisher.PublishResult first = ReleasePublisher.apply(plan);
        require(first.success, "发布成功: " + first.error);
        require(first.copied == 3 && first.updated == 0 && first.deleted == 0,
                "首次发布复制 3 个文件，实际 copy=" + first.copied + " update=" + first.updated + " delete=" + first.deleted);
        require(Files.isRegularFile(RELEASE.resolve("mods/foo.jar")), "mods/foo.jar 已发布");
        require(Files.isRegularFile(RELEASE.resolve("config/client.toml")), "cfg 的内容映射到 target 路径 config/");
        require(!Files.exists(RELEASE.resolve("mods/skip-me.jar")), "被 ignore 的文件不发布");

        // ---- 名单：格式与旧版一致（sourcePath = 源目录原始路径），md5 取自发布副本 ----
        List<PathData> roster = ReleasePublisher.buildRoster(plan);
        FileData foo = find(roster, "mods/foo.jar");
        require(foo != null, "名单里有 mods/foo.jar");
        require(foo.sourcePath.equals(rel(LIVE.resolve("mods/foo.jar"))),
                "sourcePath 与旧版一致：源目录里的原始路径，实际 " + foo.sourcePath);
        require(foo.targetPath.equals("mods/foo.jar"), "targetPath 是客户端路径");
        require(foo.md5.equals(MD5.calculateMD5(RELEASE.resolve("mods/foo.jar").toFile())),
                "名单 md5 与发布目录里的字节一致");
        require(foo.size == Files.size(RELEASE.resolve("mods/foo.jar")), "名单 size 与发布目录一致");

        // 改名录条目（config:push:<live>/cfg）：sourcePath 仍是源路径、targetPath 仍是客户端路径——升级对它也无感
        FileData cfg = find(roster, "config/client.toml");
        require(cfg != null, "改名录条目也在名单里");
        require(cfg.sourcePath.equals(rel(LIVE.resolve("cfg/client.toml"))),
                "改名录条目的 sourcePath 保留源路径，实际 " + cfg.sourcePath);
        require(cfg.targetPath.equals("config/client.toml"), "改名录条目的 targetPath 是客户端路径");

        require(find(ReleasePublisher.scanRelease(syncDirs, Paths.get(releaseDir)), "mods/skip-me.jar") == null,
                "被 ignore 的文件不进名单（扫描发布目录的口径）");
        require(find(ReleasePublisher.scanRelease(syncDirs, Paths.get(releaseDir)), "mods/foo.jar") != null,
                "扫描发布目录也看得到已发布的文件");

        // ---- 没有改动时重复发布应当是空操作 ----
        ReleasePublisher.PublishResult noop = ReleasePublisher.apply(ReleasePublisher.plan(syncDirs, Paths.get(releaseDir)));
        require(noop.success && noop.copied == 0 && noop.updated == 0 && noop.deleted == 0,
                "无改动重复发布不做事，实际 copy=" + noop.copied + " update=" + noop.updated + " delete=" + noop.deleted);

        // ---- 回归：删掉源目录里的文件但不 commit ----
        Files.delete(LIVE.resolve("mods/foo.jar"));
        require(find(ReleasePublisher.scanRelease(syncDirs, Paths.get(releaseDir)), "mods/foo.jar") != null,
                "删了但没 commit：名单里仍然有它");
        require(Files.isRegularFile(RELEASE.resolve("mods/foo.jar")),
                "删了但没 commit：发布目录里仍然发得出来（客户端不会 404）");

        // ---- 回归：覆盖源目录里的文件但不 commit ----
        String publishedBarMd5 = MD5.calculateMD5(RELEASE.resolve("mods/sub/bar.txt").toFile());
        write(LIVE.resolve("mods/sub/bar.txt"), "bar-v2-completely-different");
        FileData bar = find(ReleasePublisher.scanRelease(syncDirs, Paths.get(releaseDir)), "mods/sub/bar.txt");
        require(bar.md5.equals(publishedBarMd5), "改了但没 commit：名单里仍是发布出去的 md5");
        require(read(RELEASE.resolve("mods/sub/bar.txt")).equals("bar-v1"),
                "改了但没 commit：发布目录里仍是旧字节（客户端不会 md5 不匹配）");

        // ---- commit 之后差异才生效 ----
        ReleasePublisher.PublishResult second = ReleasePublisher.apply(ReleasePublisher.plan(syncDirs, Paths.get(releaseDir)));
        require(second.success, "第二次发布成功: " + second.error);
        require(second.deleted >= 1, "被删的文件这次从发布目录里删掉了");
        require(second.updated >= 1, "被改的文件这次覆盖了");
        require(!Files.exists(RELEASE.resolve("mods/foo.jar")), "commit 后 mods/foo.jar 不在发布目录里了");
        List<PathData> roster2 = ReleasePublisher.scanRelease(syncDirs, Paths.get(releaseDir));
        require(find(roster2, "mods/foo.jar") == null, "commit 后名单里没有 mods/foo.jar 了");
        require(!find(roster2, "mods/sub/bar.txt").md5.equals(publishedBarMd5), "commit 后名单里是新 md5");

        // ---- 源目录不存在：整体失败，发布目录不能被清空 ----
        Files.move(LIVE.resolve("mods"), WORK.resolve("mods-moved"));
        boolean planFailed = false;
        try {
            ReleasePublisher.plan(syncDirs, Paths.get(releaseDir));
        } catch (IOException e) {
            planFailed = true;
            require(e.getMessage() != null && e.getMessage().contains("does not exist"),
                    "错误信息点出缺失的源目录: " + e.getMessage());
        }
        require(planFailed, "源目录缺失时 plan 必须失败");
        require(find(ReleasePublisher.scanRelease(syncDirs, Paths.get(releaseDir)), "mods/sub/bar.txt") != null,
                "plan 失败不影响发布目录（不会被当成全部删除而清空）");
        Files.move(WORK.resolve("mods-moved"), LIVE.resolve("mods"));

        // ---- 发布目录不能落在源目录内部 ----
        try {
            ReleasePublisher.plan(List.of("mods:mirror:" + rel(LIVE)), LIVE.resolve("release-inside"));
            throw new AssertionError("RELEASE_DIR 落在源目录内部却被接受了");
        } catch (IOException expected) {
            // 预期
        }

        // ---- 源目录存在但为空：计划为空，publish() 会拒绝执行（否则会把已发布的文件全删光）----
        Path emptyDir = LIVE.resolve("empty");
        Files.createDirectories(emptyDir);
        ReleasePublisher.Plan emptyPlan = ReleasePublisher.plan(List.of("empty:mirror:" + rel(emptyDir)),
                Paths.get(releaseDir));
        require(emptyPlan.fileCount() == 0, "空源目录的计划为空，publish() 据此拒绝发布空快照");

        // ---- 下载路径必须留在发布目录内（路径穿越）----
        Path releaseAbs = Paths.get(releaseDir).toAbsolutePath().normalize();
        require(ReleasePublisher.resolveInRelease(Paths.get(releaseDir), "mods/sub/bar.txt").startsWith(releaseAbs),
                "正常路径能解析");

        // 必须直接拒绝的写法：../、绝对路径、UNC、规范化后为空（"." 与 "mods/.."）
        List<String> mustReject = List.of(
                "../outside", "mods/../../outside", "mods/sub/../../../outside",
                "/etc/passwd", "\\\\server\\share", "..", ".", "mods/..",
                "mods\\..\\..\\outside");
        for (String bad : mustReject) {
            try {
                ReleasePublisher.resolveInRelease(Paths.get(releaseDir), bad);
                throw new AssertionError("逃逸路径被接受了: " + bad);
            } catch (IOException expected) {
                // 预期
            }
        }

        // 不会被解码的写法：当普通文件名处理，无论平台怎么解析都绝不能落在发布目录外
        for (String weird : List.of("%2e%2e/outside", "....//outside", "C:boot.ini")) {
            try {
                require(ReleasePublisher.resolveInRelease(Paths.get(releaseDir), weird).startsWith(releaseAbs),
                        "奇异路径被解析到了发布目录外: " + weird);
            } catch (IOException alsoAcceptable) {
                // 平台直接拒绝也可以
            }
        }

        // 发布目录里的符号链接不能把下载带出去（Windows 没有符号链接权限时跳过这条）
        Path linkRoot = WORK.resolve("release-link");
        Files.createDirectories(linkRoot.resolve("mods"));
        write(WORK.resolve("outside-secret.txt"), "secret");
        Path link = linkRoot.resolve("mods/evil.jar");
        boolean linkCreated = true;
        try {
            Files.createSymbolicLink(link, WORK.resolve("outside-secret.txt").toAbsolutePath());
        } catch (UnsupportedOperationException | IOException e) {
            linkCreated = false; // 本机不允许创建符号链接
        }
        if (linkCreated) {
            try {
                ReleasePublisher.resolveInRelease(linkRoot, "mods/evil.jar");
                throw new AssertionError("符号链接逃逸被接受了");
            } catch (IOException expected) {
                // 预期
            }
        }

        // ---- 清理只发生在声明的 targetPath 子目录里，发布目录根部的东西不碰 ----
        write(RELEASE.resolve("not-a-target.txt"), "keep");
        write(RELEASE.resolve("mods/stale.txt"), "stale");
        write(RELEASE.resolve(".sakura-tmp-42"), "tmp");
        ReleasePublisher.PublishResult scoped =
                ReleasePublisher.apply(ReleasePublisher.plan(syncDirs, Paths.get(releaseDir)));
        require(scoped.success, "清理轮发布成功: " + scoped.error);
        require(Files.exists(RELEASE.resolve("not-a-target.txt")), "发布目录根部的无关文件不被清理");
        require(!Files.exists(RELEASE.resolve("mods/stale.txt")), "targetPath 下多余的文件会被清掉");
        require(!Files.exists(RELEASE.resolve(".sakura-tmp-42")), "残留的临时文件会被清掉");

        // ---- 源目录缺失：不论发布目录里有没有这个条目的历史内容，一律失败 ----
        Path freshRelease = WORK.resolve("release-fresh");
        try {
            ReleasePublisher.plan(List.of("shaderpacks:mirror:" + rel(LIVE.resolve("no-such-dir"))), freshRelease);
            throw new AssertionError("源目录缺失（发布目录里也没有历史内容）却被放行了");
        } catch (IOException expected) {
            require(expected.getMessage().contains("does not exist"), "错误信息点出缺失的源目录");
        }

        // ---- targetPath 嵌套（config 与 config/sub，源目录各自独立）：内层文件不能被外层的清理删掉 ----
        write(LIVE.resolve("outer/a.toml"), "a");
        write(LIVE.resolve("inner/x.toml"), "x");
        List<String> nested = List.of(
                "config:push:" + rel(LIVE.resolve("outer")),
                "config/sub:push:" + rel(LIVE.resolve("inner")));
        Path nestedRelease = WORK.resolve("release-nested");
        ReleasePublisher.Plan nestedPlan = ReleasePublisher.plan(nested, nestedRelease);
        require(ReleasePublisher.apply(nestedPlan).success, "嵌套 targetPath 发布成功");
        require(Files.isRegularFile(nestedRelease.resolve("config/a.toml")), "外层条目的文件已发布");
        require(Files.isRegularFile(nestedRelease.resolve("config/sub/x.toml")), "内层条目的文件没有被外层的清理删掉");
        List<PathData> nestedRoster = ReleasePublisher.buildRoster(nestedPlan);
        require(find(nestedRoster, "config/sub/x.toml") != null, "内层文件进了名单");
        ReleasePublisher.PublishResult nestedAgain = ReleasePublisher.apply(ReleasePublisher.plan(nested, nestedRelease));
        require(nestedAgain.success && nestedAgain.copied == 0 && nestedAgain.updated == 0 && nestedAgain.deleted == 0,
                "嵌套 targetPath 重复发布是空操作，实际 copy=" + nestedAgain.copied + " update=" + nestedAgain.updated
                        + " delete=" + nestedAgain.deleted);
        // 内层文件真的不再被任何条目需要时，才会被清理掉
        Files.delete(LIVE.resolve("inner/x.toml"));
        write(LIVE.resolve("inner/y.toml"), "y");
        require(ReleasePublisher.apply(ReleasePublisher.plan(nested, nestedRelease)).success, "嵌套发布第三次成功");
        require(!Files.exists(nestedRelease.resolve("config/sub/x.toml")), "不再被需要的内层文件会被清理");
        require(Files.isRegularFile(nestedRelease.resolve("config/sub/y.toml")), "新增的内层文件已发布");

        // ---- 发布互斥：另一个发布进行中时，第二个直接被拒绝 ----
        ReleasePublisher.Plan lockPlan = ReleasePublisher.plan(nested, nestedRelease);
        Thread holder = new Thread(() -> {
            ReleasePublisher.LOCK.lock();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                ReleasePublisher.LOCK.unlock();
            }
        });
        holder.start();
        Thread.sleep(300);
        ReleasePublisher.PublishResult busy = ReleasePublisher.apply(lockPlan);
        require(!busy.success && busy.error.contains("already running"), "有发布在进行时第二个被拒绝");
        holder.join();
        require(ReleasePublisher.apply(lockPlan).success, "锁释放后可以再次发布");

        // ---- 并行发布 + 进度，以及客户端下载时怎么找文件 ----
        progressAndParallelTests();
        downloadLookupTests();

        // ---- 升级自动迁移：源目录与最新版本完全一致时才动手 ----
        // 先把 live 恢复成干净状态（前面的回归用例删过 foo.jar、改过 bar.txt）
        write(LIVE.resolve("mods/foo.jar"), "foo-v1");
        write(LIVE.resolve("mods/sub/bar.txt"), "bar-v1");

        DataConfig.Data recorded = recordedRoster();
        Path migrateRoot = WORK.resolve("release-migrate");
        ServerCommandsHelper.autoMigrateSnapshot(syncDirs, migrateRoot, recorded);
        require(Files.isRegularFile(migrateRoot.resolve("mods/foo.jar")), "内容一致时自动迁移会建立发布目录");
        require(Files.isRegularFile(migrateRoot.resolve("config/client.toml")), "自动迁移同样覆盖改名录条目");

        // 有没 commit 的改动：绝不自动发布，否则名单和文件又会重新对不上（老 bug 回归）
        write(LIVE.resolve("mods/foo.jar"), "uncommitted-edit");
        Path mismatchRoot = WORK.resolve("release-mismatch");
        ServerCommandsHelper.autoMigrateSnapshot(syncDirs, mismatchRoot, recorded);
        require(!Files.exists(mismatchRoot.resolve("mods/foo.jar")), "有不一致时拒绝自动迁移");

        System.out.println("ReleasePublisherTest: all " + checks + " checks passed.");
    }

    // ---- helpers ----

    private static String rel(Path path) {
        return path.toString().replace('\\', '/');
    }

    /** 造一份"数据库里记录的名单"（模拟旧服务端 commit 出来的最新版本），内容与当前 live 一致。 */
    private static DataConfig.Data recordedRoster() throws IOException {
        DataConfig.PathData mods = new DataConfig.PathData();
        mods.model = "mirror";
        mods.targetPath = "mods";
        mods.files = new ArrayList<>(List.of(
                listed("mods/foo.jar", LIVE.resolve("mods/foo.jar")),
                listed("mods/sub/bar.txt", LIVE.resolve("mods/sub/bar.txt"))));
        DataConfig.PathData cfg = new DataConfig.PathData();
        cfg.model = "push";
        cfg.targetPath = "config";
        cfg.files = new ArrayList<>(List.of(listed("config/client.toml", LIVE.resolve("cfg/client.toml"))));
        DataConfig.Data data = new DataConfig.Data();
        data.version = "9.9.9";
        data.paths = new ArrayList<>(List.of(mods, cfg));
        return data;
    }

    private static DataConfig.FileData listed(String targetPath, Path liveFile) throws IOException {
        DataConfig.FileData fileData = new DataConfig.FileData();
        fileData.sourcePath = rel(liveFile);
        fileData.targetPath = targetPath;
        fileData.md5 = MD5.calculateMD5(liveFile.toFile());
        fileData.size = Files.size(liveFile);
        return fileData;
    }

    private static void resetWorkDir() throws IOException {
        deleteRecursively(WORK);
        Files.createDirectories(WORK);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ---- 并行发布与进度 ----

    private static void progressAndParallelTests() throws Exception {
        // 纯函数：进度条与体积格式
        require(PublishProgress.bar("Copying", 0, 100).contains("0%"), "进度条 0%");
        require(PublishProgress.bar("Copying", 50, 100).contains("50%")
                && PublishProgress.bar("Copying", 50, 100).contains("##########----------"), "进度条 50% 半满");
        require(PublishProgress.bar("Copying", 100, 100).contains("100%"), "进度条 100%");
        require(PublishProgress.bar("Copying", 999, 100).contains("100%"), "超出总量按 100% 封顶");
        require(PublishProgress.bar("Hashing", 0, 0).contains("100%"), "总量为 0 不除零");
        require(PublishProgress.size(512).equals("512 B"), "体积 B");
        require(PublishProgress.size(1536).equals("1.5 KB"), "体积 KB");
        require(PublishProgress.size(5L * 1024 * 1024).equals("5.0 MB"), "体积 MB");

        // 限流：同一阶段大量回调只输出少数几行，且完成时一定输出一次、之后不再输出
        List<String> lines = new ArrayList<>();
        PublishProgress.Listener throttled = PublishProgress.throttled(lines::add, 60_000);
        for (long done = 0; done <= 1000; done += 10) {
            throttled.onProgress("Copying", done, 1000);
        }
        throttled.onProgress("Copying", 1000, 1000);
        require(lines.size() == 2, "限流后只剩首行和完成行，实际 " + lines.size());
        require(lines.get(lines.size() - 1).contains("100%"), "最后一行是 100%");
        throttled.onProgress("Hashing", 5, 10);
        require(lines.size() == 3 && lines.get(2).startsWith("Hashing"), "新阶段的第一次回调会输出");

        // 并行发布：60 个小文件 + 1 个 3MB 的大文件，分布在多层目录里
        Path live = WORK.resolve("par-live");
        Path release = WORK.resolve("par-release");
        long expectedBytes = 0;
        for (int i = 0; i < 60; i++) {
            String content = "file-" + i + "-" + "x".repeat(i * 7);
            write(live.resolve("mods/d" + (i % 5) + "/f" + i + ".txt"), content);
            expectedBytes += content.getBytes(StandardCharsets.UTF_8).length;
        }
        byte[] big = new byte[3 * 1024 * 1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31 + 7);
        }
        Files.write(live.resolve("mods/big.bin"), big);
        expectedBytes += big.length;

        List<String> parSync = List.of("mods:mirror:" + rel(live.resolve("mods")));
        ReleasePublisher.Plan parPlan = ReleasePublisher.plan(parSync, Paths.get(rel(release)));
        require(parPlan.fileCount() == 61, "并行用例共 61 个文件");
        AtomicLong maxCopy = new AtomicLong();
        AtomicLong maxHash = new AtomicLong();
        AtomicLong totalCopy = new AtomicLong();
        AtomicLong totalHash = new AtomicLong();
        AtomicBoolean overshoot = new AtomicBoolean();
        PublishProgress.Listener recorder = (phase, done, total) -> {
            boolean copying = phase.equals(PublishProgress.COPYING);
            (copying ? maxCopy : maxHash).accumulateAndGet(done, Math::max);
            (copying ? totalCopy : totalHash).set(total);
            if (done > total) {
                overshoot.set(true);
            }
        };
        ReleasePublisher.PublishResult par = ReleasePublisher.apply(parPlan, recorder);
        require(par.success && par.copied == 61, "并行发布 61 个文件全部复制: " + par.error);
        require(maxCopy.get() == expectedBytes && totalCopy.get() == expectedBytes,
                "复制进度走满 " + expectedBytes + "，实际 " + maxCopy.get() + "/" + totalCopy.get());
        List<PathData> parRoster = ReleasePublisher.buildRoster(parPlan, recorder);
        require(maxHash.get() == expectedBytes && totalHash.get() == expectedBytes,
                "校验和进度走满，实际 " + maxHash.get() + "/" + totalHash.get());
        require(!overshoot.get(), "进度不会超过总量");
        require(parRoster.get(0).files.size() == 61, "名单含 61 个文件");
        for (FileData fileData : parRoster.get(0).files) {
            Path copy = release.resolve(fileData.targetPath);
            require(Files.isRegularFile(copy), "已发布: " + fileData.targetPath);
            Path src = live.resolve(fileData.targetPath);
            require(fileData.md5.equals(MD5.calculateMD5(src.toFile())),
                    "并行算出的 md5 与源文件一致: " + fileData.targetPath);
            require(fileData.size == Files.size(copy), "size 与发布副本一致: " + fileData.targetPath);
        }
        require(java.util.Arrays.equals(Files.readAllBytes(release.resolve("mods/big.bin")), big), "大文件内容完整");
        try (var stream = Files.list(release)) {
            require(stream.noneMatch(p -> p.getFileName().toString().startsWith(".sakura-tmp-")), "没有残留临时文件");
        }
        ReleasePublisher.PublishResult parAgain = ReleasePublisher.apply(
                ReleasePublisher.plan(parSync, Paths.get(rel(release))));
        require(parAgain.success && parAgain.copied == 0 && parAgain.updated == 0, "内容没变时不重复复制");

        // 名单顺序与计划一致（并行不能打乱顺序）
        List<String> planned = new ArrayList<>(parPlan.entryPlans.get(0).files.keySet());
        List<String> listed = new ArrayList<>();
        for (FileData fileData : parRoster.get(0).files) {
            listed.add(fileData.targetPath);
        }
        require(planned.equals(listed), "并行生成的名单顺序与计划一致");

        // 失败：计划之后源文件消失 -> 整个发布失败，其余线程被取消，没有残留临时文件
        Path liveFail = WORK.resolve("fail-live");
        Path releaseFail = WORK.resolve("fail-release");
        for (int i = 0; i < 20; i++) {
            write(liveFail.resolve("mods/f" + i + ".txt"), "content-" + i);
        }
        List<String> failSync = List.of("mods:mirror:" + rel(liveFail.resolve("mods")));
        ReleasePublisher.Plan failPlan = ReleasePublisher.plan(failSync, Paths.get(rel(releaseFail)));
        Files.delete(liveFail.resolve("mods/f7.txt"));
        ReleasePublisher.PublishResult failed = ReleasePublisher.apply(failPlan);
        require(!failed.success && failed.error != null, "源文件在发布途中消失时发布失败");
        try (var stream = Files.list(releaseFail)) {
            require(stream.noneMatch(p -> p.getFileName().toString().startsWith(".sakura-tmp-")),
                    "失败后没有残留临时文件");
        }
        require(ReleasePublisher.LOCK.tryLock(), "失败后锁已释放");
        ReleasePublisher.LOCK.unlock();
    }

    // ---- 客户端下载时文件怎么找 ----

    private static void downloadLookupTests() throws Exception {
        Path root = WORK.resolve("lookup-release");
        write(root.resolve("config/foo.toml"), "published-cfg");
        write(root.resolve("mods/a.jar"), "published-jar");
        Path legacy = WORK.resolve("lookup-live/clientconfig/foo.toml");
        write(legacy, "live-cfg");

        DataConfig.Data data = new DataConfig.Data();
        data.version = "1.0";
        DataConfig.PathData cfg = new DataConfig.PathData();
        cfg.targetPath = "config";
        cfg.files = new ArrayList<>();
        // sourcePath 是源目录的原始路径，targetPath 是客户端 / 发布目录里的路径，两者故意不同名
        DataConfig.FileData cfgFile = new DataConfig.FileData();
        cfgFile.sourcePath = rel(legacy);
        cfgFile.targetPath = "config/foo.toml";
        cfg.files.add(cfgFile);
        DataConfig.FileData missing = new DataConfig.FileData();
        missing.sourcePath = "live/missing.jar";
        missing.targetPath = "mods/missing.jar";
        cfg.files.add(missing);
        DataConfig.FileData evil = new DataConfig.FileData();
        evil.sourcePath = "live/evil.jar";
        evil.targetPath = "../outside.jar";
        cfg.files.add(evil);
        data.paths = new ArrayList<>(List.of(cfg));

        FileServer.Located ok = FileServer.locate(data, rel(legacy), root, true);
        require(ok.file != null && ok.status == 200, "发布模式：名单里的文件能找到");
        require(read(ok.file.toPath()).equals("published-cfg"), "发布模式发的是发布副本，而不是源目录里的实时文件");

        FileServer.Located notListed = FileServer.locate(data, "mods/a.jar", root, true);
        require(notListed.file == null && notListed.status == 403, "不在名单里的文件拒绝 403（即使发布目录里有）");

        FileServer.Located gone = FileServer.locate(data, "live/missing.jar", root, true);
        require(gone.file == null && gone.status == 404, "名单里有但发布目录没有 -> 404");

        FileServer.Located escape = FileServer.locate(data, "live/evil.jar", root, true);
        require(escape.file == null && escape.status == 403, "名单项的 targetPath 想逃出发布目录 -> 403");

        FileServer.Located legacyMode = FileServer.locate(data, rel(legacy), root, false);
        require(legacyMode.file != null && read(legacyMode.file.toPath()).equals("live-cfg"),
                "未发布时沿用旧行为：直接发源目录里的文件");

        require(FileServer.locate(null, rel(legacy), root, true).status == 403, "没有任何版本时拒绝");
        require(FileServer.locate(new DataConfig.Data(), rel(legacy), root, true).status == 403, "版本没有名单时拒绝");
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static FileData find(List<PathData> paths, String targetPath) {
        for (PathData pathData : paths) {
            if (pathData.files == null) {
                continue;
            }
            for (FileData fileData : pathData.files) {
                if (fileData.targetPath.equals(targetPath)) {
                    return fileData;
                }
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
