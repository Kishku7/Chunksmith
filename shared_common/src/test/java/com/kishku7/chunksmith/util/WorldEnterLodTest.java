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

package com.kishku7.chunksmith.util;

import com.kishku7.chunksmith.platform.LodMode;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** worldEnterLod: auto follows the renderer; on and off are the user's word (mod_support #37). */
public class WorldEnterLodTest {

    @Test
    public void autoBuildsOnlyWhenARendererIsInstalled() {
        assertTrue(WorldEnterLod.resolve(LodMode.AUTO, true));
        assertFalse(WorldEnterLod.resolve(LodMode.AUTO, false));
    }

    @Test
    public void onAndOffAreFixedWhateverIsInstalled() {
        assertTrue(WorldEnterLod.resolve(LodMode.ON, false));
        assertTrue(WorldEnterLod.resolve(LodMode.ON, true));
        assertFalse(WorldEnterLod.resolve(LodMode.OFF, true));
        assertFalse(WorldEnterLod.resolve(LodMode.OFF, false));
    }

    @Test
    public void anUnparseableValueIsTreatedAsAuto() {
        // GsonConfig maps an unknown value to AUTO before it gets here; null must not flip to ON.
        assertTrue(WorldEnterLod.resolve(null, true));
        assertFalse(WorldEnterLod.resolve(null, false));
    }
}
