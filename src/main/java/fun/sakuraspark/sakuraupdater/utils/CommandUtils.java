package fun.sakuraspark.sakuraupdater.utils;

import java.util.function.Function;

import fun.sakuraspark.sakuraupdater.utils.ServerCommandsHelper.CommandResult;
import net.minecraft.commands.CommandSourceStack;

public class CommandUtils {

    public static void sendSuccessMessage(CommandSourceStack source, String message) {
        source.sendSuccess(
                () -> net.minecraft.network.chat.Component.literal(message),
                true);
    }

    public static void sendFailureMessage(CommandSourceStack source, String message) {
        source.sendFailure(net.minecraft.network.chat.Component.literal(message));
    }

    /** 进度条只回显给执行命令的人，不广播给其他管理员，也不写聊天记录之外的地方。 */
    public static void sendProgressMessage(CommandSourceStack source, String message) {
        source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(message), false);
    }

    /**
     * 在后台线程执行耗时的发布任务（复制 + 计算校验和），避免卡住服务器主线程；
     * 进度和结果都切回主线程再发给执行者。命令本身立即返回。
     */
    public static void runPublishTask(CommandSourceStack source, String name,
            Function<PublishProgress.Listener, CommandResult> task) {
        var server = source.getServer();
        sendProgressMessage(source, name + " started, the server keeps running while files are copied...");
        Thread thread = new Thread(() -> {
            CommandResult result;
            try {
                result = task.apply(PublishProgress.throttled(
                        line -> server.execute(() -> sendProgressMessage(source, line)), 1000));
            } catch (Throwable t) {
                result = CommandResult.failure(name + " failed: " + t);
            }
            final CommandResult finalResult = result;
            server.execute(() -> {
                if (finalResult.success) {
                    sendSuccessMessage(source, finalResult.message);
                } else {
                    sendFailureMessage(source, finalResult.message);
                }
            });
        }, "SakuraUpdater-" + name);
        thread.setDaemon(true);
        thread.start();
    }
}
