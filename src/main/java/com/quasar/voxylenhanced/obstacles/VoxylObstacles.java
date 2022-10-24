package com.quasar.voxylenhanced.obstacles;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mb3364.http.AsyncHttpClient;
import com.mb3364.http.StringHttpResponseHandler;
import com.quasar.voxylenhanced.VoxylEnhanced;
import com.quasar.voxylenhanced.VoxylFeature;
import com.quasar.voxylenhanced.VoxylUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class VoxylObstacles extends VoxylFeature {

    // settings
    public static boolean toggled = true;
    public static boolean leftAligned = false;

    // opponent win stats
    public static AsyncHttpClient client = new AsyncHttpClient();
    public static String opponentName = null;
    public static boolean opponentWinsHasLoaded = false;
    public static String opponentWins = "???";

    // time related
    public static Long time = null;
    public static Long newTime = null;
    public static boolean stopGrowingTime = false;

    // quick reset
    public static boolean qrOpeningFirst = false;
    public static int qrOpenFirst = 0;
    public static boolean qrOpeningSecond = false;
    public static int qrOpenSecond = 0;
    public static boolean autoRQToggled = false;

    // other
    public static boolean isInObstacles = false;
    public static int inObstaclesTickDelay = -1;
    private static final DecimalFormat df = new DecimalFormat("0.00");
    public static Integer startingX = null;
    public static int deathCount = 0;
    public static HashMap<String, Integer> winsMap = new HashMap<>();

    @Override
    public void configurate(Configuration config, boolean loadFromFile) {
        Property toggledProp = config.get(Configuration.CATEGORY_CLIENT, "obstacles-toggled", true, "Is obstacles UI on or not");
        Property autoRQProp = config.get(Configuration.CATEGORY_CLIENT, "obstacles-autoRQ", false, "Automatically Requeue Singleplayer matches on death");
        Property leftAlignedProp = config.get(Configuration.CATEGORY_CLIENT, "obstacles-leftAligned", false, "Is UI left aligned instead of right aligned");

        if (loadFromFile) {
            autoRQToggled = autoRQProp.getBoolean();
            toggled = toggledProp.getBoolean();
            leftAligned = leftAlignedProp.getBoolean();
        } else {
            leftAlignedProp.set(leftAligned);
            autoRQProp.set(autoRQToggled);
            toggledProp.set(toggled);
        }
    }

    public static void handleCommand(String[] args) {
        if (args.length == 1) {
            VoxylUtils.informPlayer("/ve obstacles <toggle|alignment|autorequeue>");
            return;
        }
        if (args[1].equals("toggle")) {
            toggled = !toggled;
            VoxylUtils.informPlayer("Toggled obstacles functionality " + (toggled ? "on" : "off"));
            return;
        }
        if (args[1].equals("alignment")) {
            leftAligned = !leftAligned;
            VoxylUtils.informPlayer("Set alignment to " + (leftAligned ? "left" : "right"));
            return;
        }
        if (args[1].equals("autorequeue")) {
            autoRQToggled = !autoRQToggled;
            VoxylUtils.informPlayer(
                    "Singleplayer auto requeue functionality " + (autoRQToggled ? "on" : "off"));
            return;
        }
        VoxylUtils.informPlayer("/ve obstacles <toggle|alignment|autorequeue>");
    }

    public static String getStringUsername() {
        // this will ALWAYS return a string
        try {
            if (Minecraft.getMinecraft().thePlayer.getName() != null) {
                return Minecraft.getMinecraft().thePlayer.getName();
            } else {
                return "";
            }
        } catch (NullPointerException e) {
            return "";
        }
    }

    @SubscribeEvent
    public void chatEvent(ClientChatReceivedEvent event) {

        // death count
        Pattern pattern = Pattern.compile("%username% fell into the void\\.$".replace("%username%", getStringUsername()));
        if (pattern.matcher(event.message.getUnformattedText()).find()) {
            deathCount += 1;
            if (autoRQToggled) {
                restartPrivateGame();
            }
        }

        // check to see if player in obstacles
        if (!isInObstacles) {
            if (event.message.getUnformattedText().contains("Traverse 10 procedurally generated\n")) {
                isInObstacles = true;
                inObstaclesTickDelay = 40;
            }
        }

        // get the opponent username
        if (inObstaclesTickDelay >= 0 && opponentName == null) {
            Pattern gameNamesMessage = Pattern.compile("^Players in this game:");
            if (gameNamesMessage.matcher(event.message.getUnformattedText()).find()) {
                opponentName = event.message.getUnformattedText()
                        .replaceAll("Players in this game: ", "")
                        .replaceAll(getStringUsername(), "")
                        .replaceAll("\\s", "");
            }
        }

        // start the timer
        if (event.message.getUnformattedText().contains("Game starting in 5 seconds!")) {
            time = System.currentTimeMillis()+5000L;
        }

        // stop the timer
        Pattern stopGrowPattern = Pattern.compile("^Total time was.*:.*$");
        if (stopGrowPattern.matcher(event.message.getUnformattedText()).find()) {
            stopGrowingTime = true;
        }
    }
    public void attemptMakeApiRequest() {
        // opponent must have name
        if (opponentName == null) {
            return;
        }
        // retrieve opponent win stats from cache
        if (winsMap.containsKey(opponentName)) {
            opponentWins = winsMap.get(opponentName).toString();
            return;
        }

        // if cache doesn't have opponent win stats, make api request
        System.setProperty("http.agent", "Chrome");
        try {

            // if api key is not there, ask player to set it
            if (VoxylEnhanced.apiKey.equals("missing")) {
                VoxylUtils.informPlayer("You need to set your api key for the Voxyl Enhanced mod to work properly!");
                opponentWins = "API Key missing!";
                return;
            }

            try {
                // get player in the world
                UUID id = Minecraft.getMinecraft().theWorld.getPlayerEntityByName(opponentName).getUniqueID();

                // make async api request
                String url = "http://api.voxyl.net/player/stats/game/" + id.toString() + "/?api=" + VoxylEnhanced.apiKey;
                client.setUserAgent("Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1)");
                client.setConnectionTimeout(3000);
                client.get(url, new StringHttpResponseHandler() {
                    @Override
                    public void onSuccess(int i, Map<String, List<String>> map, String content) {
                        JsonParser parser = new JsonParser();
                        JsonObject obj = (JsonObject) parser.parse(content);
                        JsonObject stats = (JsonObject) obj.get("stats");
                        if (stats == null) {
                            opponentWins = "ERROR!";
                            return;
                        }
                        JsonObject obstacles = (JsonObject) stats.get("obstacleSingle");
                        if (obstacles == null) {
                            opponentWins = "0";
                            return;
                        }
                        opponentWins = obstacles.get("wins").getAsInt() + "";
                        winsMap.put(opponentName, obstacles.get("wins").getAsInt());
                    }
                    @Override
                    public void onFailure(int i, Map<String, List<String>> map, String s) {
                        opponentWins = "ERROR: " + i;
                    }
                    @Override
                    public void onFailure(Throwable throwable) {
                        opponentWins = "ERROR!";
                    }
                });
            } catch (NullPointerException e) {
                opponentWins = "No opponent";
            }
        } catch (Exception err) { err.printStackTrace(); }
    }

    public static boolean isInSingleplayer() {
        return opponentWins.equals("No opponent");
    }

    public static void restartPrivateGame() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (!isInSingleplayer()) {
            return;
        }
        mc.thePlayer.sendChatMessage("/pg");
        qrOpeningFirst = true;
    }

    @SubscribeEvent
    public void tickEvent(TickEvent.PlayerTickEvent event) {
        inObstaclesTickDelay -= 1;
        if (inObstaclesTickDelay >= 0) {
            isInObstacles = true;
        }
        if (inObstaclesTickDelay == 0) {
            attemptMakeApiRequest();
            startingX = Math.toIntExact(Math.round(Minecraft.getMinecraft().thePlayer.posX));
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.openContainer == null) {
            qrOpeningSecond = false;
            return;
        }

        if (qrOpenFirst != 0) {
            qrOpenFirst += 1;
            if (qrOpenFirst > 4) {
                mc.playerController.windowClick(
                        mc.thePlayer.openContainer.windowId, 24,
                        0,
                        0,
                        mc.thePlayer
                );
                qrOpenFirst = 0;
                qrOpeningSecond = true;
            }
        }
        if (qrOpeningSecond) {
            if (mc.thePlayer.openContainer.getSlot(25) != null) {
                if (mc.thePlayer.openContainer.getSlot(25).getHasStack()) {
                    qrOpenSecond = 1;
                    qrOpeningSecond = false;
                }
            }
        }
        if (qrOpenSecond != 0) {
            qrOpenSecond += 1;
            if (qrOpenSecond > 2) {
                mc.playerController.windowClick(
                        mc.thePlayer.openContainer.windowId, 25,
                        0,
                        0,
                        mc.thePlayer
                );
                qrOpenSecond = 0;
            }
        }
    }

    @SubscribeEvent
    public void guiOpenEvent(GuiOpenEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (mc.thePlayer == null) {
            return;
        }
        if (mc.thePlayer.openContainer == null) {
            return;
            // null safety is a pain
        }
        if (qrOpeningFirst) {
            System.out.println("First init");
            qrOpenFirst = 1;
            qrOpeningFirst = false;
        }
    }

    @SubscribeEvent
    public void render(RenderGameOverlayEvent.Text event) {
        if (!stopGrowingTime) {
            newTime = System.currentTimeMillis();
        }
        if (isInObstacles && toggled) {
            VoxylUtils.textY = 5;

            // death count
            VoxylUtils.drawText("Death count: " + deathCount, leftAligned);

            // opponent wins
            VoxylUtils.drawText("Opponent win stats: " + opponentWins, leftAligned);

            if (time != null && newTime != null) {
                double timeElapsed = (newTime - time) / 1000.0;
                VoxylUtils.drawText("Time elapsed: " + df.format(timeElapsed), leftAligned);

                if (startingX != null) {
                    double diff = Math.abs(startingX - Minecraft.getMinecraft().thePlayer.posX);
                    double pDone = VoxylUtils.clamp(diff / 175 * 100, 0, 100);
                    VoxylUtils.drawText("Percentage done: " + df.format(pDone), leftAligned);

                    double distanceTraveled = Math.abs(startingX - Minecraft.getMinecraft().thePlayer.posX);
                    double estimatedArrival = timeElapsed/(distanceTraveled/175);
                    if (!(0 >= estimatedArrival || Double.isInfinite(estimatedArrival)) && (!stopGrowingTime)) {
                        VoxylUtils.drawText("Estimated arrival: " + df.format(estimatedArrival), leftAligned);
                    }
                }
            }
        }
    }

    public void reset() {
        deathCount = 0;
        opponentName = null;
        opponentWins = "???";
        time = null;
        newTime = null;
        isInObstacles = false;
        stopGrowingTime = false;
        opponentWinsHasLoaded = false;
        startingX = null;
    }
}
