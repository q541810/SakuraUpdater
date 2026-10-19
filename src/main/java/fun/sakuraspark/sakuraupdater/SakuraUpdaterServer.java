package fun.sakuraspark.sakuraupdater;

import org.slf4j.Logger;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static fun.sakuraspark.sakuraupdater.utils.CommandUtils.*;

import com.mojang.logging.LogUtils;

import fun.sakuraspark.sakuraupdater.config.DataConfig;
import fun.sakuraspark.sakuraupdater.config.ServerConfig;
import fun.sakuraspark.sakuraupdater.network.FileServer;
import fun.sakuraspark.sakuraupdater.utils.ServerCommandsHelper;
import fun.sakuraspark.sakuraupdater.utils.ServerCommandsHelper.CommandResult;
import net.minecraft.commands.CommandSourceStack;
import static net.minecraft.commands.Commands.*;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

//@Mod.EventBusSubscriber(value = Dist.DEDICATED_SERVER, modid = SakuraUpdater.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SakuraUpdaterServer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FileServer file_server;

    public static SakuraUpdaterServer INSTANCE;

    SakuraUpdaterServer() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ServerConfig.SPEC);
        if (!DataConfig.connectToDatabase("config/sakuraupdater-database.db")) {
            throw new IllegalStateException("Failed to initialize SakuraUpdater database; see dependency/database errors above.");
        }
        INSTANCE = this;
        // fileServer = new FileServer(ConfigServer.SERVER_PORT.get(),
        // ConfigServer.SYNC_DIR.get());
    }

    public static SakuraUpdaterServer getInstance() {
        return INSTANCE;
    }

    public void runServer() {
        if (file_server != null) {
            if (file_server.isRunning()) {
                LOGGER.warn("SakuraUpdater Server is already running!");
                return;
            }
            return;
        }
        file_server = new FileServer(ServerConfig.port);
        file_server.start();
        LOGGER.info("SakuraUpdater Server is running!");
    }

    // Register commands for the server
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(sakuraupdaterCommand());
    }

    // ----Create command----
    private LiteralArgumentBuilder<CommandSourceStack> sakuraupdaterCommand() {
        return literal("sakuraupdater")
                .then(createDataCommand())
                .then(createCommitCommand());
    }

    // 动态版本建议提供器
    private static final SuggestionProvider<CommandSourceStack> VERSION_SUGGESTIONS = (context, builder) -> {
        // 根据DataConfig.datalist动态生成版本建议
        for (String version : DataConfig.getAllVersions()) {
            builder.suggest(version);
        }
        return builder.buildFuture();
    };

    // ----Create the /ssync data command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataCommand() {
        return literal("data")
                .requires(source -> source.hasPermission(2))
                .then(createDataEditCommand())
                .then(createDataRepairCommand())
                .then(createDataDiffCommand())
                .then(createDataListCommand())
                .then(createDataShowCommand())
                .then(createDataDeleteCommand())
                .then(createDataClearCommand());
    }

    // ----Create the /ssync data diff command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataDiffCommand() {
        return literal("diff")
                .executes(context -> {
                    CommandResult result = ServerCommandsHelper.diffData(null);
                    if (!result.success) {
                        sendFailureMessage(context.getSource(), result.message);
                        return 0;
                    }
                    sendSuccessMessage(context.getSource(), result.message);
                    return 1;
                })
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .executes(context -> {
                            String version = getString(context, "version");
                            CommandResult result = ServerCommandsHelper.diffData(version);
                            if (!result.success) {
                                sendFailureMessage(context.getSource(), result.message);
                                return 0;
                            }
                            sendSuccessMessage(context.getSource(), result.message);
                            return 1;
                        }));
    }

    // ----Create the /ssync data repair command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataRepairCommand() {
        return literal("repair")
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .executes(context -> {
                            String version = getString(context, "version");
                            runPublishTask(context.getSource(), "data repair",
                                    progress -> ServerCommandsHelper.repairData(version, progress));
                            return 1;
                        }));
    }

    // ----Create the /ssync data subcommands----
    private LiteralArgumentBuilder<CommandSourceStack> createDataEditCommand() {
        return literal("edit")
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .then(argument("description", string())
                                .executes(context -> {
                                    String version = getString(context, "version");
                                    String description = getString(context, "description");
                                    CommandResult result = ServerCommandsHelper.editData(version, description);
                                    if (!result.success) {
                                        sendFailureMessage(context.getSource(), result.message);
                                        return 0;
                                    }
                                    sendSuccessMessage(context.getSource(), result.message);
                                    return 1;
                                })));
    }

    // ----Create the /ssync data list command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataListCommand() {
        return literal("list")
                .executes(context -> {
                    CommandResult dataList = ServerCommandsHelper.buildDataListString();
                    if (!dataList.success) {
                        sendFailureMessage(context.getSource(), dataList.message);
                        return 0;
                    }
                    sendSuccessMessage(context.getSource(), dataList.message);
                    return 1;
                });
    }

    // ----Create the /ssync data show command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataShowCommand() {
        return literal("show")
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .executes(context -> {
                            String version = getString(context, "version");
                            CommandResult result = ServerCommandsHelper.showData(version);
                            if (!result.success) {
                                sendFailureMessage(context.getSource(), result.message);
                                return 0;
                            }
                            sendSuccessMessage(context.getSource(), result.message);
                            return 1;
                        }));
    }

    private LiteralArgumentBuilder<CommandSourceStack> createDataDeleteCommand() {
        return literal("delete")
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .executes(context -> {
                            String version = getString(context, "version");
                            CommandResult result = ServerCommandsHelper.deleteData(version);
                            if (!result.success) {
                                sendFailureMessage(context.getSource(), result.message);
                            } else {
                                sendSuccessMessage(context.getSource(), result.message);
                            }
                            return 1;
                        }));
    }

    // ----Create the /ssync data clear command----
    private LiteralArgumentBuilder<CommandSourceStack> createDataClearCommand() {
        return literal("clear")
                .executes(context -> {
                    CommandResult result = ServerCommandsHelper.clearData();
                    if (!result.success) {
                        sendFailureMessage(context.getSource(), result.message);
                    } else {
                        sendSuccessMessage(context.getSource(), result.message);
                    }
                    return 1;
                });
    }

    // ----Create the /ssync commit command----
    private LiteralArgumentBuilder<CommandSourceStack> createCommitCommand() {
        return literal("commit")
                .requires(source -> source.hasPermission(2))
                .then(argument("version", string())
                        .suggests(VERSION_SUGGESTIONS)
                        .then(argument("description", greedyString())
                                .executes(context -> {
                                    //LOGGER.debug("why!");
                                    String version = getString(context, "version");
                                    String description = getString(context, "description");
                                    // 走带发布的入口：commit 会先把源目录镜像进发布目录，名单再从发布目录扫描
                                    // 复制和算校验和很耗时，放到后台线程做，进度回显给执行者
                                    runPublishTask(context.getSource(), "commit",
                                            progress -> ServerCommandsHelper.commitData(version, description, progress));
                                    return 1;
                                })));
    }

}
