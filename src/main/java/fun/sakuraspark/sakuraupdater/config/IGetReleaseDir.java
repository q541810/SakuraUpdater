package fun.sakuraspark.sakuraupdater.config;

/**
 * 读取发布目录配置。与 {@link IGetSyncDirs} 同样是按运行模式分发的门面：
 * 独立模式读 {@link StandaloneServerConfig}，模组模式读 {@link ServerConfig}。
 */
public interface IGetReleaseDir {
    static String getReleaseDir() {
        if (StandaloneServerConfig.isStandalone()) {
            return StandaloneServerConfig.getReleaseDir();
        } else {
            return ServerConfig.getReleaseDir();
        }
    }
}
