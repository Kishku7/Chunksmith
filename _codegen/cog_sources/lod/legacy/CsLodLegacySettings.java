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

package com.kishku7.chunksmith.lod.legacy;

import com.kishku7.chunksmith.lod.client.CsLodClientConfig;
import com.kishku7.chunksmith.lod.client.CsLodClientSettings;
import com.kishku7.chunksmith.lod.client.net.CsLodClientNet;
import com.kishku7.chunksmith.lod.net.CsLodMessages;
import com.kishku7.chunksmith.lod.net.CsLodProtocol;

/**
 * COMPATIBILITY ONLY. Lets a 4.x client answer a 3.x server's {@code /cslod set}.
 *
 * <p>This whole package exists so the compat code is somewhere you can see it and delete it. When 3.x
 * servers stop mattering, delete the package and the two lines that reach into it; nothing in the
 * feature code depends on any of it.
 *
 * <p><b>Why it survives when the rest of the relay does not.</b> 4.0.0 moved client settings to
 * {@code /csclient set}, which reads and writes the file directly, and deleted the SEND half of the
 * {@code SETTING_*} exchange -- a 4.x server never asks a client anything. But a 4.x client can still
 * join a 3.x server, and that server still offers its own {@code /cslod set}. If nothing here
 * answered, the payload would hit an unknown id and be dropped SILENTLY, and a 3.x server is
 * deliberately silent on success, so the player would type the command and see nothing at all. That
 * exact failure is what {@code hasLodClient} was invented to prevent on the other side of the wire;
 * reintroducing it in the other direction would be a poor trade for deleting forty lines.
 */
public final class CsLodLegacySettings {

    private CsLodLegacySettings() {
    }

    /**
     * Acts on this client's own settings, on behalf of a {@code /cslod set} typed at a 3.x server.
     * The reply prints here rather than going back for the server to print, because the file being
     * read and written is on this machine and the server cannot know the answer.
     *
     * <p>Already on the client thread: {@code ClientPlatform} hands every payload to the client
     * executor before the dispatcher is reached.
     */
    public static void handle(final CsLodMessages.ClientSetting request) {
        if (request.action() == CsLodProtocol.SETTING_LIST) {
            CsLodClientNet.chat("[chunksmith] LOD client settings (config/"
                    + CsLodClientConfig.FILE_NAME + "):");
            for (CsLodClientSettings.Setting setting : CsLodClientSettings.all()) {
                CsLodClientNet.chat("[chunksmith]   " + setting.name() + " = " + setting.read()
                        + "  -- " + setting.help());
            }
            return;
        }

        var found = CsLodClientSettings.find(request.name());
        if (found.isEmpty()) {
            CsLodClientNet.chat("[chunksmith] no LOD client setting called '" + request.name()
                    + "'. Known: " + String.join(", ", CsLodClientSettings.names()));
            return;
        }
        CsLodClientSettings.Setting setting = found.get();

        if (request.action() == CsLodProtocol.SETTING_SHOW) {
            CsLodClientNet.chat("[chunksmith] " + setting.name() + " = " + setting.read()
                    + "  -- " + setting.help());
            return;
        }

        // SETTING_SET. A refused value is a shape error: a word where a number belongs. An
        // out-of-range value is accepted and clamped, so the reply reports what was STORED rather
        // than what was typed.
        if (!setting.write(request.value())) {
            var expected = setting.kind().completions();
            CsLodClientNet.chat("[chunksmith] '" + request.value() + "' is not a valid value for "
                    + setting.name()
                    + (expected.isEmpty() ? " (expected a whole number)"
                            : " (expected one of: " + String.join(", ", expected) + ")"));
            return;
        }
        CsLodClientNet.chat("[chunksmith] " + setting.name() + " = " + setting.read()
                + ", applied now and saved to config/" + CsLodClientConfig.FILE_NAME);
        CsLodClientNet.chat("[chunksmith] (this server is on Chunksmith 3.x; on 4.x the command is"
                + " /csclient set)");
    }
}
