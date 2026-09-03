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

import com.kishku7.chunksmith.util.Input;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Quoted command arguments.
 *
 * <p>Every loader builds its arguments by splitting the RAW command line on spaces instead of
 * reading what Brigadier parsed, so quotes survive into the value. That closed BOTH routes to
 * setting an IPv6 backchannel address: the bare form dies in the parser at the first colon, and the
 * quoted form used to reach the validator as {@code "2001:db8::1"} -- quotes included -- and be
 * refused. Found on a live server, not by reading the code.
 */
public class CommandArgumentsQuotingTest {

    @Test
    public void aWhollyQuotedTokenLosesItsQuotes() {
        assertEquals("2001:db8::1", CommandArguments.unquote("\"2001:db8::1\""));
        assertEquals("2001:db8::1", CommandArguments.unquote("'2001:db8::1'"));
        assertEquals("::", CommandArguments.unquote("\"::\""));
    }

    @Test
    public void anUnquotedTokenIsUntouched() {
        assertEquals("lod.example.net", CommandArguments.unquote("lod.example.net"));
        assertEquals("192.168.1.10", CommandArguments.unquote("192.168.1.10"));
        assertEquals("none", CommandArguments.unquote("none"));
    }

    @Test
    public void onlyMATCHINGSurroundingQuotesCount() {
        // A stray quote is data, not a wrapper -- stripping one side would corrupt the value.
        assertEquals("\"half", CommandArguments.unquote("\"half"));
        assertEquals("half\"", CommandArguments.unquote("half\""));
        assertEquals("\"mixed'", CommandArguments.unquote("\"mixed'"));
    }

    @Test
    public void onlyONELayerIsStripped() {
        assertEquals("\"inner\"", CommandArguments.unquote("\"\"inner\"\""));
    }

    @Test
    public void degenerateInputsDoNotThrow() {
        assertEquals("", CommandArguments.unquote(""));
        assertEquals("\"", CommandArguments.unquote("\""));
        assertEquals(null, CommandArguments.unquote(null));
    }

    @Test
    public void theQueueItselfHandsBackUnquotedValues() {
        CommandArguments args = CommandArguments.of("lodBackchannelHost", "\"2001:db8::1\"");
        assertEquals("lodBackchannelHost", args.next().orElseThrow(AssertionError::new));
        assertEquals("2001:db8::1", args.next().orElseThrow(AssertionError::new));
    }

    @Test
    public void andThatIsExactlyWhatTheVALIDATORNeededAllAlong() {
        // The end-to-end point: unquoted, these are values checkHost has always accepted. The bug
        // was never in the validation -- it was that the value never arrived intact.
        CommandArguments args = CommandArguments.of("\"2001:db8::1\"", "\"::\"", "\"[::1]\"");
        assertEquals("2001:db8::1", Input.checkHost(args.next().orElseThrow(AssertionError::new)));
        assertEquals("::", Input.checkHost(args.next().orElseThrow(AssertionError::new)));
        assertEquals("::1", Input.checkHost(args.next().orElseThrow(AssertionError::new)));
    }
}
