package fun.sakuraspark.sakuraupdater.utils;

import fun.sakuraspark.sakuraupdater.config.DataConfig;
import fun.sakuraspark.sakuraupdater.config.IGetSyncDirs;
import fun.sakuraspark.sakuraupdater.config.DataConfig.FileData;
import fun.sakuraspark.sakuraupdater.config.DataConfig.PathData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 封装服务端命令的业务逻辑，与命令框架解耦。
 * context 参数获取和消息发送不在此处处理。
 */
public class ServerCommandsHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerCommandsHelper.class);
    /**
     * 命令执行结果
     */
    public static class CommandResult {
        public final boolean success;
        public final String message;

        private CommandResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static CommandResult success(String message) {
            return new CommandResult(true, message);
        }

        public static CommandResult failure(String message) {
            return new CommandResult(false, message);
        }
    }

    // ---- data edit ----
    public static CommandResult editData(String version, String description) {
        String fileContent = null; // 如果 description 是文件路径，则读取文件内容
        if (new File(description).exists()) {
            try {
                fileContent = Files.readString(Path.of(description));
            } catch (IOException e) {
                return CommandResult.failure(
                        "Description file is exist but failed to read description file: " + e.getMessage());
            }
        }
        if (!DataConfig.editData(version, fileContent != null ? fileContent : description.replace("\\n", "\n"))) {
            return CommandResult.failure("Failed to edit data: version not found.");
        }
        return CommandResult.success("SakuraUpdater updated the description of version " + version
                + " (file list and time are left untouched).");
    }

    // ---- data repair ----
    /**
     * 重建某个版本的文件清单；描述与 time 保持不变（不会打乱更新日志顺序）。
     * 用法场景：被旧版 data edit 清空过清单的版本，或该版本当时没能正确 commit。
     * <p>
     * 修最新版本：先重新发布一次，再按发布目录重建清单（清单整体重写，与发布目录一致）。
     * 修旧版本：<b>不发布</b>，只按发布目录当前的内容重建。客户端只认最新版本的清单，旧版本清单不影响下载；
     * 如果这时也发布，同步目录里没 commit 的改动会被放出去，最新版本的清单就对不上了（无限更新）。
     * 注意：重建的是"当前"发布快照的清单，不是该版本当时的快照。
     */
    public static CommandResult repairData(String version) {
        return repairData(version, PublishProgress.NONE);
    }

    /** 同 {@link #repairData(String)}，重新发布最新版本时汇报复制 / 计算校验和的进度。 */
    public static CommandResult repairData(String version, PublishProgress.Listener progress) {
        if (!ReleasePublisher.LOCK.tryLock()) {
            LOGGER.warn("Refusing 'data repair {}': another publish / commit / repair is already running.", version);
            return CommandResult.failure("Another publish, commit or repair is already running, try again later.");
        }
        try {
            return repairDataLocked(version, progress);
        } finally {
            ReleasePublisher.LOCK.unlock();
        }
    }

    private static CommandResult repairDataLocked(String version, PublishProgress.Listener progress) {
        DataConfig.Data data = DataConfig.getDataByVersion(version);
        if (data == null) {
            return CommandResult.failure("Data with version " + version + " not found.");
        }
        DataConfig.Data latest = DataConfig.getLastData();
        boolean isLatest = latest != null && Objects.equals(latest.version, data.version);

        List<PathData> pathData;
        String source;
        try {
            if (isLatest) {
                ReleasePublisher.PublishOutcome outcome = ReleasePublisher.publishAndScan(progress);
                if (!outcome.success) {
                    return CommandResult.failure(outcome.error);
                }
                pathData = outcome.roster;
                source = "from the published snapshot (" + outcome.publish.copied + " copied, "
                        + outcome.publish.updated + " updated, " + outcome.publish.deleted + " deleted)";
            } else {
                ReleasePublisher.refreshPublishedState();
                if (!ReleasePublisher.isPublished()) {
                    return CommandResult.failure("Version " + version + " is not the latest version and there is no "
                            + "published snapshot yet. Run 'commit' or 'data repair "
                            + (latest == null ? "<latest version>" : latest.version) + "' first.");
                }
                LOGGER.warn("'data repair {}' targets an older version (latest is {}): nothing is published, the "
                        + "file list is rebuilt from the current contents of {}. Uncommitted changes in the sync "
                        + "directories stay unpublished.", version, latest == null ? "?" : latest.version,
                        ReleasePublisher.releaseRoot());
                pathData = getPublishedFileList();
                source = "from the current published snapshot without publishing (not the latest version)";
            }
        } catch (Throwable t) {
            // 这里连 Error 一起接（例如独立/模组环境混用时缺类的 NoClassDefFoundError），
            // 与 FileServer 读取线程池配置时的处理保持一致：给一条明确失败而不是把命令打崩
            return CommandResult.failure("Failed to rebuild the file list: " + t);
        }
        int fileCount = countFiles(pathData);
        if (fileCount == 0) {
            return CommandResult.failure("Nothing to repair: the published snapshot is empty, please check SYNC_DIR.");
        }
        if (!DataConfig.updateFiles(version, pathData)) {
            if (isLatest) {
                LOGGER.error("The release snapshot was updated but the file list of version {} could not be written "
                        + "back, so clients may see mismatched files. Run 'data repair {}' again.", version, version);
            }
            return CommandResult.failure("Failed to write the rebuilt file list back to version " + version + ".");
        }
        return CommandResult.success("SakuraUpdater repaired version " + version + ": rebuilt " + pathData.size()
                + " path(s) / " + fileCount + " file(s), " + FileUtils.formatSize(totalBytes(pathData))
                + " in total, " + source + ". Description and time were left untouched.");
    }

    // ---- data list ----
    public static CommandResult buildDataListString() {
        StringBuilder dataList = new StringBuilder("SakuraUpdater server data:\n");
        DataConfig.getAllVersions().forEach(version -> {
            DataConfig.Data data = DataConfig.getDataByVersion(version);
            if (data == null) {
                dataList.append("Version: ").append(version).append(", <failed to read this record>\n");
                return;
            }
            boolean noFileList = data.paths == null || data.paths.isEmpty();
            dataList.append("Version: ").append(version)
                    .append(", Time: ").append(data.time)
                    .append(", Description: ").append(data.description)
                    .append(noFileList ? ", <empty file list, run 'data repair " + version + "'>" : "")
                    .append("\n");
        });
        return CommandResult.success(dataList.toString());
    }

    // ---- data show ----
    public static CommandResult showData(String version) {
        DataConfig.Data data = DataConfig.getDataByVersion(version);
        if (data == null) {
            return CommandResult.failure("Data with version " + version + " not found.");
        }
        if (data.paths == null || data.paths.isEmpty()) {
            return CommandResult.failure("Version " + version + " has an empty file list in the database "
                    + "(it was probably wiped by the old 'data edit' bug, or this version was never committed "
                    + "with a valid SYNC_DIR). Run 'data repair " + version + "' on the server to rebuild it.");
        }
        StringBuilder dataList = new StringBuilder("SakuraUpdater server data:\n");
        for (PathData path : data.paths) {
            dataList.append(path.targetPath)
                    .append(" - ")
                    .append(path.model)
                    .append(":[\n");
            for (FileData file : path.files) {
                dataList.append("    (")
                        .append(file.targetPath)
                        .append(", ")
                        .append(file.sourcePath)
                        .append(", ")
                        .append(file.md5)
                        .append(", ")
                        .append(file.size > 0 ? FileUtils.formatSize(file.size) : "size unknown")
                        .append(")\n");
            }
            dataList.append("]\n");
        }
        return CommandResult.success(dataList.toString());
    }

    // ---- data delete ----
    public static CommandResult deleteData(String version) {
        if (DataConfig.removeData(version)) {
            return CommandResult.success("SakuraUpdater server data deleted!");
        } else {
            return CommandResult.failure("Failed to delete data: Version not found.");
        }
    }

    // ---- data clear ----
    public static CommandResult clearData() {
        if (!DataConfig.clearData()) {
            return CommandResult.failure("Failed to clear data.");
        }
        return CommandResult.success("SakuraUpdater server data cleared!");
    }

    // ---- publish ----
    /** 扫描发布目录本身的实际内容（发布目录内相对路径），供 data diff 与数据库里的名单对比。 */
    public static List<PathData> getPublishedFileList() {
        return ReleasePublisher.scanRelease(IGetSyncDirs.getSyncDirs(), ReleasePublisher.releaseRoot());
    }

    // ---- 升级自动迁移 ----
    /**
     * 升级迁移：发布目录还不存在（或为空）而数据库里已经有版本时，自动完成第一次发布，
     * 省得管理员升级后忘了 commit，一直停在旧行为里。
     * <p>
     * 只在"当前源目录的内容"和"最新版本名单记录的内容"完全一致时才动手：不一致说明有没 commit
     * 的改动，自动发布会把它们悄悄放出去，名单和文件又会重新对不上。不一致时保持旧行为并打日志，
     * 让管理员自己决定什么时候 commit。
     */
    public static void autoMigrateSnapshot() {
        autoMigrateSnapshot(IGetSyncDirs.getSyncDirs(), ReleasePublisher.releaseRoot(), DataConfig.getLastData());
    }

    /** 供启动迁移与测试共用；latest 为 null（还没发布过任何版本）时什么都不做。 */
    public static void autoMigrateSnapshot(List<String> syncDirs, Path releaseRoot, @Nullable DataConfig.Data latest) {
        if (latest == null || latest.paths == null || latest.paths.isEmpty()) {
            return; // 数据库里还没有任何版本：第一次 commit 自然会建发布目录
        }
        ReleasePublisher.Plan plan;
        try {
            plan = ReleasePublisher.plan(syncDirs, releaseRoot);
        } catch (Throwable t) {
            LOGGER.warn("Snapshot auto-migration skipped: cannot scan the sync directories ({}). "
                    + "Check SYNC_DIR, then run 'commit' (or 'data repair {}') manually.",
                    t.toString(), latest.version);
            return;
        }
        if (plan.fileCount() == 0) {
            LOGGER.warn("Snapshot auto-migration skipped: the sync directories are empty. Check SYNC_DIR, then run "
                    + "'commit' (or 'data repair {}') manually.", latest.version);
            return;
        }
        Map<String, String> planned = new LinkedHashMap<>();
        try {
            for (ReleasePublisher.EntryPlan entryPlan : plan.entryPlans) {
                for (Map.Entry<String, ReleasePublisher.PlannedFile> entry : entryPlan.files.entrySet()) {
                    planned.put(entry.getKey(), MD5.calculateMD5(entry.getValue().source.toFile()));
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Snapshot auto-migration skipped: failed to read the sync directories ({}). "
                    + "Run 'commit' (or 'data repair {}') manually.", t.toString(), latest.version);
            return;
        }
        if (!sameFileSet(planned, flatten(latest.paths))) {
            LOGGER.warn("Snapshot auto-migration skipped: the sync directories have uncommitted changes that version "
                            + "{} does not record. Publishing them automatically would break clients, so the server "
                            + "keeps serving the sync directories directly. Run 'commit' (or 'data repair {}') to "
                            + "switch to snapshot publishing.", latest.version, latest.version);
            return;
        }
        ReleasePublisher.PublishResult result = ReleasePublisher.apply(plan);
        if (result.success) {
            LOGGER.info("Snapshot auto-migration done: published {} file(s) to {}, identical to version {}. "
                            + "Deleting or overwriting files in the sync directories without committing no longer "
                            + "affects clients.", result.plannedFiles, releaseRoot, latest.version);
        } else {
            LOGGER.warn("Snapshot auto-migration failed: {}. Run 'commit' (or 'data repair {}') manually.",
                    result.error, latest.version);
        }
    }

    /** 两边按客户端路径对 md5：完全一致才允许自动迁移（size 不比，旧名单里没有这个字段）。 */
    private static boolean sameFileSet(Map<String, String> planned, Map<String, FileData> recorded) {
        if (planned.size() != recorded.size()) {
            return false;
        }
        for (Map.Entry<String, String> entry : planned.entrySet()) {
            FileData file = recorded.get(entry.getKey());
            if (file == null || file.md5 == null || !file.md5.equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    // ---- data diff ----
    /**
     * 对比"发布目录里现在的实际文件"和某个版本的名单（{@code version} 为 null/空时用最新版本）。
     * <p>
     * 只读：不发布、不改名单、不动任何文件。重点是把"名单里有、但发布目录里没有"的文件找出来——
     * 那正是会让客户端无限更新（下载 404 / 内容对不上）的状态。
     */
    public static CommandResult diffData(@Nullable String version) {
        DataConfig.Data baseline;
        if (version == null || version.isBlank()) {
            baseline = DataConfig.getLastData();
            if (baseline == null) {
                return CommandResult.failure("No version has been committed yet, nothing to diff.");
            }
        } else {
            baseline = DataConfig.getDataByVersion(version.trim());
            if (baseline == null) {
                return CommandResult.failure("Data with version " + version + " not found.");
            }
        }
        if (baseline.paths == null || baseline.paths.isEmpty()) {
            return CommandResult.failure("Version " + baseline.version + " has an empty file list in the database. "
                    + "Run 'data repair " + baseline.version + "' on the server to rebuild it before diffing.");
        }
        Path releaseRoot = ReleasePublisher.releaseRoot();
        if (!ReleasePublisher.isPublished()) {
            return CommandResult.failure("No published snapshot found at " + releaseRoot
                    + ". Run 'commit' (or 'data repair <version>') once before diffing.");
        }

        Map<String, FileData> served = flatten(getPublishedFileList());
        Map<String, FileData> listed = flatten(baseline.paths);

        List<String> servedOnly = new ArrayList<>();
        List<String> listedOnly = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, FileData> entry : served.entrySet()) {
            FileData other = listed.get(entry.getKey());
            if (other == null) {
                servedOnly.add(entry.getKey());
            } else if (!Objects.equals(entry.getValue().md5, other.md5)) {
                changed.add(entry.getKey());
            }
        }
        for (String key : listed.keySet()) {
            if (!served.containsKey(key)) {
                listedOnly.add(key);
            }
        }
        Collections.sort(servedOnly);
        Collections.sort(listedOnly);
        Collections.sort(changed);

        StringBuilder report = new StringBuilder("SakuraUpdater data diff (published snapshot vs version ")
                .append(baseline.version).append("):\n");
        appendDiffSection(report, "Served but not listed", servedOnly,
                "clients can download these, but no version records them (check SYNC_DIR)");
        appendDiffSection(report, "Listed but NOT served", listedOnly,
                "listed files the server cannot serve - this is the state that makes clients loop forever");
        appendDiffSection(report, "Content differs", changed,
                "listed md5 does not match the published file");
        if (servedOnly.isEmpty() && listedOnly.isEmpty() && changed.isEmpty()) {
            report.append("  No differences: the published snapshot matches this version exactly.\n");
        } else {
            report.append("  Summary: ").append(servedOnly.size()).append(" served-but-not-listed, ")
                    .append(listedOnly.size()).append(" listed-but-not-served, ")
                    .append(changed.size()).append(" changed.\n");
        }
        return CommandResult.success(report.toString());
    }

    private static void appendDiffSection(StringBuilder report, String title, List<String> files, String note) {
        if (files.isEmpty()) {
            return;
        }
        report.append("  ").append(title).append(" (").append(files.size()).append(") - ").append(note)
                .append(":\n");
        files.forEach(file -> report.append("    ").append(file).append("\n"));
    }

    private static Map<String, FileData> flatten(List<PathData> paths) {
        Map<String, FileData> files = new LinkedHashMap<>();
        if (paths == null) {
            return files;
        }
        for (PathData path : paths) {
            if (path.files == null) {
                continue;
            }
            for (FileData file : path.files) {
                files.put(file.targetPath, file);
            }
        }
        return files;
    }

    private static int countFiles(List<PathData> paths) {
        int count = 0;
        for (PathData path : paths) {
            if (path.files != null) {
                count += path.files.size();
            }
        }
        return count;
    }

    private static long totalBytes(List<PathData> paths) {
        long total = 0;
        for (PathData path : paths) {
            if (path.files == null) {
                continue;
            }
            for (FileData file : path.files) {
                total += file.size;
            }
        }
        return total;
    }

    // ---- commit ----
    public static CommandResult commitData(String version, String description) {
        return commitData(version, description, PublishProgress.NONE);
    }

    /** 同 {@link #commitData(String, String)}，发布过程（复制、计算校验和）按字节汇报进度。 */
    public static CommandResult commitData(String version, String description, PublishProgress.Listener progress) {
        if (!ReleasePublisher.LOCK.tryLock()) {
            LOGGER.warn("Refusing 'commit {}': another publish / commit / repair is already running.", version);
            return CommandResult.failure("Another publish, commit or repair is already running, try again later.");
        }
        try {
            return commitDataLocked(version, description, progress);
        } finally {
            ReleasePublisher.LOCK.unlock();
        }
    }

    private static CommandResult commitDataLocked(String version, String description,
            PublishProgress.Listener progress) {
        // 能在发布之前判断的失败（版本号重复、描述文件读不了）必须先判断：发布目录一旦变了，
        // 而最新版本的名单没跟着变，客户端就会 md5 对不上、无限更新
        if (DataConfig.getDataByVersion(version) != null) {
            return CommandResult.failure("Failed to add commit: Version already exists or invalid data.");
        }
        String fileContent = null;
        if (new File(description).exists()) {
            try {
                fileContent = Files.readString(Path.of(description));
            } catch (IOException e) {
                return CommandResult.failure(
                        "Description file is exist but failed to read description file: " + e.getMessage());
            }
        }

        // 先发布（把源目录镜像进发布目录），名单再按发布副本生成——名单与客户端实际能下到的文件由构造保证一致，
        // 且名单格式（sourcePath = 源目录原始路径）与旧版完全一致，升级对客户端无感
        List<PathData> pathData;
        try {
            ReleasePublisher.PublishOutcome outcome = ReleasePublisher.publishAndScan(progress);
            if (!outcome.success) {
                return CommandResult.failure(outcome.error);
            }
            pathData = outcome.roster;
        } catch (Throwable t) {
            return CommandResult.failure("Failed to publish the release snapshot: " + t);
        }

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss"));
        if (!DataConfig.addData(version, timestamp,
                fileContent != null ? fileContent : description.replace("\\n", "\n"),
                pathData)) {
            // 发布与写库不是原子的，这里发布目录已经更新、版本记录却没写进去
            String hint = "The release snapshot was already updated but version " + version + " was not recorded, so "
                    + "clients may see mismatched files until this is fixed. Run 'commit' again, or run "
                    + "'data repair' on the latest version.";
            LOGGER.error(hint);
            return CommandResult.failure("Failed to add commit: could not write the version to the database. " + hint);
        }
        return CommandResult.success("SakuraUpdater server commit added!");
    }
}
