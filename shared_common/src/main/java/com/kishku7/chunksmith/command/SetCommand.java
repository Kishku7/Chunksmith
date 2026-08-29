/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 *
 * Chunksmith is a fork of Chunky (https://github.com/pop4959/Chunky)
 * by pop4959 and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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

    public SetCommand(Chunksmith chunky) {
        this.chunky = chunky;
    }

    @Override
    public void execute(Sender sender, CommandArguments arguments) {
        Config config = chunky.getConfig();
        Optional<String> key = arguments.next();

        if (key.isEmpty()) {
            listAll(sender, config);
            return;
        }

        Optional<ConfigSetting> found = ConfigSettings.find(key.get());
        if (found.isEmpty()) {
            sender.sendMessagePrefixed(TranslationKey.ERROR_SET_UNKNOWN, key.get());
            sender.sendMessage(TranslationKey.FORMAT_SET_KEYS, String.join(", ", ConfigSettings.names()));
            return;
        }
        ConfigSetting setting = found.get();

        Optional<String> value = arguments.next();
        if (value.isEmpty()) {
            sendOne(sender, config, setting);
            return;
        }

        if (!setting.isSupported(config)) {
            sender.sendMessagePrefixed(TranslationKey.ERROR_SET_UNSUPPORTED, setting.name());
            return;
        }

        if (!setting.write(config, value.get())) {
            String why = setting.explainRefusal(config, value.get());
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

    private void listAll(Sender sender, Config config) {
        StringBuilder list = new StringBuilder();
        for (ConfigSetting setting : ConfigSettings.all()) {
            list.append('\n').append(setting.name()).append(": ").append(setting.read(config));
            if (!setting.isSupported(config)) {
                list.append(" (not used on this platform)");
            }
        }
        sender.sendMessage(TranslationKey.FORMAT_SET_LIST, list.toString());
    }

    private void sendOne(Sender sender, Config config, ConfigSetting setting) {
        if (!setting.isSupported(config)) {
            sender.sendMessagePrefixed(TranslationKey.ERROR_SET_UNSUPPORTED, setting.name());
            return;
        }
        sender.sendMessagePrefixed(TranslationKey.FORMAT_SET_SHOW, setting.name(), setting.read(config));
    }

    private String expected(ConfigSetting setting) {
        List<String> completions = setting.kind().completions();
        if (!completions.isEmpty()) {
            return String.join("/", completions);
        }
        return setting.kind().name().toLowerCase(Locale.ROOT);
    }

    @Override
    public List<String> suggestions(CommandArguments arguments) {
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
