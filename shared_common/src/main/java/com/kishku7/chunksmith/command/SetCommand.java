package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code /cs set} -- read and write every setting in the config file from in-game.
 *
 * <pre>
 *   /cs set                    list every setting and the value in force
 *   /cs set &lt;key&gt;              show one setting
 *   /cs set &lt;key&gt; &lt;value&gt;      change it, and save it
 * </pre>
 *
 * <p>House rule (2026-08-11): a setting in the config file must be settable from a command. Before
 * this, only {@code silent} and {@code updateInterval} were -- the other nine keys could be changed only by
 * editing the file and restarting the server, which on a live server means not at all.
 *
 * <p>Two behaviours worth knowing, both deliberate:
 *
 * <ul>
 *   <li><b>The value is READ BACK after writing</b>, never echoed. Several settings clamp to a legal range
 *       on write, so what you typed and what is now in force are not always the same thing -- and the
 *       operator needs to see the second one. A clamp therefore looks like a successful set to a different
 *       number, which is exactly what happened.</li>
 *   <li><b>A rejected value is a rejected value.</b> Only a string that cannot be understood at all (a word
 *       where a number goes, a language we do not ship) is refused. Range violations are not refusals --
 *       the config layer clamps them.</li>
 * </ul>
 */
public class SetCommand implements ChunksmithCommand {
    private final Chunksmith chunky;

    public SetCommand(final Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(final Sender sender, final CommandArguments arguments) {
        final Config config = chunky.getConfig();
        final Optional<String> key = arguments.next();

        if (key.isEmpty()) {
            listAll(sender, config);
            return;
        }

        final Optional<ConfigSetting> found = ConfigSettings.find(key.get());
        if (found.isEmpty()) {
            sender.sendMessagePrefixed(TranslationKey.ERROR_SET_UNKNOWN, key.get());
            sender.sendMessage(TranslationKey.FORMAT_SET_KEYS, String.join(", ", ConfigSettings.names()));
            return;
        }
        final ConfigSetting setting = found.get();

        final Optional<String> value = arguments.next();
        if (value.isEmpty()) {
            sendOne(sender, config, setting);
            return;
        }

        if (!setting.isSupported(config)) {
            sender.sendMessagePrefixed(TranslationKey.ERROR_SET_UNSUPPORTED, setting.name());
            return;
        }

        if (!setting.write(config, value.get())) {
            sender.sendMessagePrefixed(TranslationKey.ERROR_SET_VALUE, value.get(), setting.name(),
                    expected(setting));
            return;
        }

        // Read back rather than echo -- see the class comment.
        sender.sendMessagePrefixed(TranslationKey.FORMAT_SET, setting.name(), setting.read(config));
    }

    private void listAll(final Sender sender, final Config config) {
        final StringBuilder list = new StringBuilder();
        for (final ConfigSetting setting : ConfigSettings.all()) {
            list.append('\n').append(setting.name()).append(": ").append(setting.read(config));
            if (!setting.isSupported(config)) {
                list.append(" (not used on this platform)");
            }
        }
        sender.sendMessage(TranslationKey.FORMAT_SET_LIST, list.toString());
    }

    private void sendOne(final Sender sender, final Config config, final ConfigSetting setting) {
        if (!setting.isSupported(config)) {
            sender.sendMessagePrefixed(TranslationKey.ERROR_SET_UNSUPPORTED, setting.name());
            return;
        }
        sender.sendMessagePrefixed(TranslationKey.FORMAT_SET_SHOW, setting.name(), setting.read(config));
    }

    private String expected(final ConfigSetting setting) {
        final List<String> completions = setting.kind().completions();
        if (!completions.isEmpty()) {
            return String.join("/", completions);
        }
        return setting.kind().name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
        // First argument: the key. Second: whatever that key accepts, when it is a fixed set.
        if (arguments.size() < 2) {
            return new ArrayList<>(ConfigSettings.names());
        }
        if (arguments.size() == 2) {
            return ConfigSettings.find(arguments.remaining().get(0))
                    .map(setting -> setting.kind().completions())
                    .orElse(List.of());
        }
        return List.of();
    }
}
