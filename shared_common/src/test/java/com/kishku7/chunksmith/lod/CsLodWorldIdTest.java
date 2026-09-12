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

package com.kishku7.chunksmith.lod;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The id decides WHERE a client writes gigabytes, and a wrong or unstable one either orphans a store
 * or serves terrain from a world that no longer exists. Every property below is load-bearing for one
 * of those two.
 */
public class CsLodWorldIdTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path store() throws IOException {
        Path root = tmp.newFolder().toPath().resolve("chunksmith").resolve("lod");
        CsLodWorldId.forget(root);
        return root;
    }

    @Test
    public void mintingCreatesTheFileAndTheIdIsWellFormed() throws IOException {
        Path root = store();
        String id = CsLodWorldId.forStore(root);

        assertTrue("a mintable store must produce an id", CsLodWorldId.isValid(id));
        assertTrue(Files.isRegularFile(root.resolve(CsLodWorldId.FILE_NAME)));
        assertEquals(id, Files.readString(root.resolve(CsLodWorldId.FILE_NAME), StandardCharsets.UTF_8).trim());
    }

    @Test
    public void theSameStoreKeepsTheSameIdAcrossTheCache() throws IOException {
        Path root = store();
        String first = CsLodWorldId.forStore(root);
        CsLodWorldId.forget(root);
        String afterForget = CsLodWorldId.forStore(root);

        // Stability is the whole contract. A re-read that minted again would move every client's
        // store on the next restart.
        assertEquals(first, afterForget);
        assertEquals(first, CsLodWorldId.forStore(root));
    }

    @Test
    public void twoStoresGetDifferentIds() throws IOException {
        assertNotEquals(CsLodWorldId.forStore(store()), CsLodWorldId.forStore(store()));
    }

    @Test
    public void aCorruptIdFileIsMintedOverRatherThanRefused() throws IOException {
        Path root = store();
        Files.createDirectories(root);
        Files.writeString(root.resolve(CsLodWorldId.FILE_NAME), "not-an-id", StandardCharsets.UTF_8);

        String id = CsLodWorldId.forStore(root);

        // Minting over is the recoverable choice: a bad id orphans every client's store ONCE, a
        // refusal orphans it forever.
        assertTrue(CsLodWorldId.isValid(id));
        assertEquals(id, Files.readString(root.resolve(CsLodWorldId.FILE_NAME), StandardCharsets.UTF_8).trim());
    }

    @Test
    public void surroundingWhitespaceInTheFileIsTolerated() throws IOException {
        Path root = store();
        Files.createDirectories(root);
        String written = "0123456789abcdef0123456789abcdef";
        Files.writeString(root.resolve(CsLodWorldId.FILE_NAME), "  " + written + "\n\n",
                StandardCharsets.UTF_8);

        assertEquals(written, CsLodWorldId.forStore(root));
    }

    @Test
    public void anUnwritableStoreYieldsEmptyRatherThanThrowing() throws IOException {
        // A file where the directory needs to be. createDirectories then fails, which is the closest
        // portable stand-in for the real case (a read-only or full disk) -- and the point is only that
        // the hello still goes out.
        Path root = tmp.newFolder().toPath().resolve("blocked");
        Files.writeString(root, "in the way", StandardCharsets.UTF_8);
        CsLodWorldId.forget(root);

        assertEquals("", CsLodWorldId.forStore(root));
    }

    @Test
    public void aNullStoreIsEmptyNotAnException() {
        assertEquals("", CsLodWorldId.forStore(null));
    }

    @Test
    public void onlyThirtyTwoLowercaseHexCharactersValidate() {
        assertTrue(CsLodWorldId.isValid("0123456789abcdef0123456789abcdef"));
        assertFalse("empty is absent, not valid", CsLodWorldId.isValid(""));
        assertFalse("uppercase is not what we mint", CsLodWorldId.isValid("0123456789ABCDEF0123456789ABCDEF"));
        assertFalse(CsLodWorldId.isValid("0123456789abcdef"));
        assertFalse(CsLodWorldId.isValid("0123456789abcdef0123456789abcdefff"));
        assertFalse("a path separator here would escape the store", CsLodWorldId.isValid("../etc/passwd"));
        assertFalse(CsLodWorldId.isValid(null));
    }

    @Test
    public void aMintedIdIsAlwaysAcceptedByItsOwnValidator() throws IOException {
        // The two halves have to agree or a server mints an id its own clients reject and every store
        // silently falls back to address keying, which is the bug this class exists to remove.
        for (int i = 0; i < 50; i++) {
            assertTrue(CsLodWorldId.isValid(CsLodWorldId.forStore(store())));
        }
    }
}
