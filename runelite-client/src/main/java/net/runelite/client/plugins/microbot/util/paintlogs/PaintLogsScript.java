package net.runelite.client.plugins.microbot.util.paintlogs;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Script;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;

@Slf4j
public class PaintLogsScript extends Script {

    public static String status = "";
    public static ArrayList<String> debugMessages = new ArrayList<>();

    public static void debug(String msg) {
        writeToFile(msg);
        log.info(msg);
        while (debugMessages.size() >= 5) debugMessages.remove(0);
        debugMessages.add(msg);
    }

    public static void writeToFile(String msg) {
        String logFilePath = System.getenv().getOrDefault("XDG_DATA_HOME",
            System.getProperty("user.home") + "/.local/share") + "/microbot/logs/debug.log";
        try {
            Files.createDirectories(Paths.get(logFilePath).getParent());
            Files.writeString(Paths.get(logFilePath), msg + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}