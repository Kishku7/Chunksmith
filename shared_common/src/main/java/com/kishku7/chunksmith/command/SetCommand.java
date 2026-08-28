package com.kishku7.chunksmith.command;

import com.kishku7.chunksmith.Chunksmith;
import com.kishku7.chunksmith.platform.Config;
import com.kishku7.chunksmith.platform.Sender;
import com.kishku7.chunksmith.util.TranslationKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Locale;

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
            final String why = setting.explainRefusal(config, value.get());
            if (why != null) {
                sender.sendMessagePrefixed(TranslationKey.ERROR_SET_REFUSED, setting.name(), why);
            } else {
                sender.sendMessagePrefixed(TranslationKey.ERROR_SET_VALUE, value.get(), setting.name(),
                        expected(setting));
            }
            return;
        }

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
        return setting.kind().name().toLowerCase(Locale.ROOT);
    }

    @Override
    public List<String> suggestions(final CommandArguments arguments) {
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
