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

package com.kishku7.chunksmith.lod.net;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * mod_support #48: peers that stop reading must not hold the backchannel's four threads forever.
 * Four raw sockets ask for a large region and never read it, which fills every handler thread with a
 * blocked write; a fifth, ordinary request must still be answered once the watchdog closes them.
 */
public class CsLodHttpServerStallTest {

    private static final String DIM = "minecraft_overworld";
    private static final String FILE = "r.0.0.cslod";

    private Path root;
    private CsLodTokens tokens;
    private CsLodHttpServer server;
    private final List<Socket> stuck = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("cslod-stall");
        Path dim = Files.createDirectories(root.resolve(DIM));
        // Big enough that loopback send + receive buffers cannot swallow it, so the write really blocks.
        byte[] block = new byte[1024 * 1024];
        try (OutputStream out = Files.newOutputStream(dim.resolve(FILE))) {
            for (int i = 0; i < 64; i++) {
                out.write(block);
            }
        }
        tokens = new CsLodTokens();
    }

    @After
    public void tearDown() throws IOException {
        for (Socket socket : stuck) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        if (server != null) {
            server.stop();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    public void stalledPeersAreClosedAndTheNextRequestIsServed() throws Exception {
        int port = freePort();
        server = new CsLodHttpServer(root, tokens, player -> true);
        server.stallTimingForTest(1_000L, 250L);
        assertEquals(port, server.start("127.0.0.1", 25565, port, ""));
        String token = tokens.issue(UUID.randomUUID());

        for (int i = 0; i < 4; i++) {
            Socket socket = new Socket();
            socket.setReceiveBufferSize(4096);
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            String request = "GET " + CsLodProtocol.HTTP_PREFIX + DIM + "/" + FILE + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n" + CsLodProtocol.HEADER_TOKEN + ": " + token + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            stuck.add(socket); // and never read
        }

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest head = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + CsLodProtocol.HTTP_PREFIX + DIM + "/" + FILE))
                .header(CsLodProtocol.HEADER_TOKEN, token)
                .timeout(Duration.ofSeconds(20))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Void> response = http.send(head, HttpResponse.BodyHandlers.discarding());

        assertEquals(200, response.statusCode());
        assertTrue(server.describe(), server.describe().contains(" stalled, "));
        assertFalse("the stuck peers were counted as stalled: " + server.describe(),
                server.describe().contains(" 0 stalled, "));
    }

    @Test
    public void stallRule() {
        assertFalse(CsLodHttpServer.isStalled(10_000L, 10_000L + CsLodHttpServer.STALL_MILLIS - 1,
                CsLodHttpServer.STALL_MILLIS));
        assertTrue(CsLodHttpServer.isStalled(10_000L, 10_000L + CsLodHttpServer.STALL_MILLIS,
                CsLodHttpServer.STALL_MILLIS));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
