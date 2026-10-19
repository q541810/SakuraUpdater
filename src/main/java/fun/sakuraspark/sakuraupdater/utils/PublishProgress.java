package fun.sakuraspark.sakuraupdater.utils;

import java.util.function.Consumer;

/**
 * 发布进度的回调与文字进度条。不依赖 Minecraft，命令层只需要提供一个"把一行文字发出去"的出口。
 */
public final class PublishProgress {

    /** 阶段名：复制文件、计算校验和。 */
    public static final String COPYING = "Copying";
    public static final String HASHING = "Hashing";

    /** 进度回调；会被多个工作线程并发调用，实现必须线程安全。 */
    @FunctionalInterface
    public interface Listener {
        /**
         * @param phase 当前阶段
         * @param done  已完成字节数
         * @param total 该阶段总字节数
         */
        void onProgress(String phase, long done, long total);
    }

    public static final Listener NONE = (phase, done, total) -> {
    };

    private PublishProgress() {
    }

    /** 例如 {@code Copying [##########----------]  50%  12.0 MB / 24.0 MB}。 */
    public static String bar(String phase, long done, long total) {
        int width = 20;
        long clamped = total <= 0 ? 0 : Math.max(0, Math.min(done, total));
        int percent = total <= 0 ? 100 : (int) (clamped * 100 / total);
        int filled = percent * width / 100;
        StringBuilder sb = new StringBuilder(phase).append(" [");
        for (int i = 0; i < width; i++) {
            sb.append(i < filled ? '#' : '-');
        }
        sb.append(String.format("] %3d%%  %s / %s", percent, size(clamped), size(Math.max(total, 0))));
        return sb.toString();
    }

    /** 人类可读的体积。 */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = { "KB", "MB", "GB", "TB" };
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    /**
     * 把进度限流后交给 sink：每个阶段第一次、间隔够久、或刚好完成时才输出一行，避免刷屏。
     * 每个阶段完成只输出一次。
     */
    public static Listener throttled(Consumer<String> sink, long minIntervalMillis) {
        return new Listener() {
            private String phase;
            private long lastEmit;
            private boolean finished;

            @Override
            public synchronized void onProgress(String newPhase, long done, long total) {
                long now = System.currentTimeMillis();
                boolean newPhaseStarted = !newPhase.equals(phase);
                if (newPhaseStarted) {
                    phase = newPhase;
                    finished = false;
                    lastEmit = 0;
                }
                boolean complete = total > 0 && done >= total;
                if (finished) {
                    return;
                }
                if (complete || now - lastEmit >= minIntervalMillis) {
                    lastEmit = now;
                    finished = complete;
                    sink.accept(bar(newPhase, done, total));
                }
            }
        };
    }
}
