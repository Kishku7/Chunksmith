package com.kishku7.chunksmith.platform;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class BukkitSender implements Sender {
    private static final Pattern RGB_PATTERN = Pattern.compile("&#[0-9a-fA-F]{6}");
    private static final boolean RGB_COLORS_SUPPORTED = detectRgbSupport();

    // net.md_5.bungee.api.ChatColor is deprecated on Paper/Folia (Adventure is preferred), but the
    // legacy path is retained intentionally for cross-version '&#RRGGBB' hex-colour support, guarded
    // by this runtime probe. There is no cross-version-safe non-deprecated replacement on the older
    // servers these jars target.
    @SuppressWarnings("deprecation")
    private static boolean detectRgbSupport() {
        try {
            Class.forName("net.md_5.bungee.api.ChatColor");
            ChatColor.class.getMethod("of", String.class);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }

    private final CommandSender sender;

    public BukkitSender(final CommandSender sender) {
        this.sender = sender;
    }

    @Override
    public boolean isPlayer() {
        return sender instanceof Player;
    }

    @Override
    public String getName() {
        return sender.getName();
    }

    @Override
    public World getWorld() {
        return new BukkitWorld(Bukkit.getWorlds().get(0));
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), 0, 0, 0, 0, 0);
    }

    @Override
    public boolean hasPermission(final String permission) {
        return sender.hasPermission(permission);
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        sender.sendMessage(formatColored(translateKey(key, prefixed, args)));
    }

    // Legacy ChatColor usage (net.md_5.bungee hex + org.bukkit.ChatColor code translation) is
    // deprecated on Paper/Folia but intentionally retained for cross-version colour output; no
    // non-deprecated replacement exists across every targeted server version.
    @SuppressWarnings("deprecation")
    protected String formatColored(final String message) {
        String coloredMessage = message;
        if (RGB_COLORS_SUPPORTED) {
            Matcher rgbMatcher = RGB_PATTERN.matcher(message);
            while (rgbMatcher.find()) {
                final ChatColor rgbColor = ChatColor.of(rgbMatcher.group().substring(1));
                final String messageStart = coloredMessage.substring(0, rgbMatcher.start());
                final String messageEnd = coloredMessage.substring(rgbMatcher.end());
                coloredMessage = messageStart + rgbColor + messageEnd;
                rgbMatcher = RGB_PATTERN.matcher(coloredMessage);
            }
        }
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', coloredMessage);
    }
}
