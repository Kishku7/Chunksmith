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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class StructureFaultReporter {
    private static final StructureFaultReporter INSTANCE = new StructureFaultReporter();

    public static StructureFaultReporter get() {
        return INSTANCE;
    }

    public static final String BLOCK_ATTACHED_MARKER = "Block-attached entity at invalid position";

    private static final String TYPE_MISSING = "block-attached entity: missing anchor (no block_pos / legacy TileX format)";
    private static final String TYPE_FAR = "block-attached entity: anchor more than 16 blocks from the entity";

    private final Logger log = LoggerFactory.getLogger("Chunksmith");

    private volatile boolean enabled = true;
    private volatile long writeIntervalMillis = 10L * 60L * 1000L;
    private volatile int maxSampleChunks = 10;

    private volatile Path reportFile;
    private volatile long lastWriteAt = 0L;
    private volatile long faultsAtLastWrite = -1L;
    private volatile boolean noticeLogged = false;

    private final Map<String, Culprit> culprits = new ConcurrentHashMap<>();
    private final AtomicLong totalFaults = new AtomicLong();

    private final ThreadLocal<ArrayDeque<Ctx>> contextStack = ThreadLocal.withInitial(ArrayDeque::new);

    private StructureFaultReporter() {
    }

    public void setReportFile(Path file) {
        this.reportFile = file;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void configure(long writeIntervalMillis, int maxSampleChunks) {
        this.writeIntervalMillis = Math.max(5_000L, writeIntervalMillis);
        this.maxSampleChunks = Math.max(1, maxSampleChunks);
    }

    public void pushContext(String structureId, int chunkX, int chunkZ) {
        if (!enabled) {
            return;
        }
        contextStack.get().push(new Ctx(structureId, chunkX, chunkZ));
    }

    public void popContext() {
        if (!enabled) {
            return;
        }
        ArrayDeque<Ctx> stack = contextStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    public void recordBlockAttached(boolean missingAnchor) {
        if (!enabled) {
            return;
        }
        Ctx ctx = contextStack.get().peek();
        String structure = ctx == null ? null : ctx.structure;
        record(namespaceOf(structure), missingAnchor ? TYPE_MISSING : TYPE_FAR, structure, ctx);
    }

    public boolean recordBlockAttachedBestEffort(String message) {
        if (message == null || !message.contains(BLOCK_ATTACHED_MARKER)) {
            return false;
        }
        if (enabled) {
            boolean missing = message.contains("null");
            record("<unknown> (no mixin on this platform)", missing ? TYPE_MISSING : TYPE_FAR, null, null);
        }
        return true;
    }

    private void record(String namespace, String type, String structure, Ctx ctx) {
        culprits.computeIfAbsent(namespace, k -> new Culprit()).add(type, structure, ctx, maxSampleChunks);
        totalFaults.incrementAndGet();
    }

    public void tick(boolean taskRunning) {
        if (!enabled || reportFile == null) {
            return;
        }
        long total = totalFaults.get();
        if (total == 0L) {
            return;
        }
        if (!noticeLogged) {
            noticeLogged = true;
            log.info("[Chunksmith] worldgen fault diagnostic active - suppressing vanilla fault spam; writing a report to {}", reportFile);
        }
        long now = System.currentTimeMillis();
        if (now - lastWriteAt >= writeIntervalMillis && total != faultsAtLastWrite) {
            writeReport(taskRunning);
            lastWriteAt = now;
            faultsAtLastWrite = total;
        }
    }

    public void flush(boolean taskRunning) {
        if (enabled && reportFile != null && totalFaults.get() > 0L) {
            writeReport(taskRunning);
        }
    }

    private void writeReport(boolean taskRunning) {
        try {
            StringBuilder sb = new StringBuilder(512);
            sb.append("Chunksmith - Worldgen Fault Report\n");
            sb.append("Generated: ").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append('\n');
            sb.append("Chunksmith generation task active: ").append(taskRunning ? "yes" : "no").append('\n');
            sb.append("Total faults this session: ").append(totalFaults.get()).append('\n');
            sb.append("\nThese are vanilla worldgen errors Chunksmith caught and kept out of the server log.\n");
            sb.append("Each is attributed to the structure/datapack being generated - report it to that mod's author.\n");
            sb.append("====================================================================\n\n");

            List<Map.Entry<String, Culprit>> sorted = new ArrayList<>(culprits.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue().total, a.getValue().total));
            for (Map.Entry<String, Culprit> entry : sorted) {
                Culprit c = entry.getValue();
                sb.append("mod/datapack: ").append(entry.getKey()).append('\n');
                sb.append("  total faults: ").append(c.total).append('\n');
                sb.append("  error types:\n");
                c.typeCounts.entrySet().stream()
                        .sorted((x, y) -> Long.compare(y.getValue().get(), x.getValue().get()))
                        .forEach(t -> sb.append("    - ").append(t.getKey()).append(" x").append(t.getValue().get()).append('\n'));
                if (!c.structures.isEmpty()) {
                    sb.append("  structures involved:\n");
                    c.structures.entrySet().stream()
                            .sorted((x, y) -> Long.compare(y.getValue().get(), x.getValue().get()))
                            .limit(20)
                            .forEach(s -> sb.append("    - ").append(s.getKey()).append(" x").append(s.getValue().get()).append('\n'));
                }
                if (!c.sampleChunks.isEmpty()) {
                    sb.append("  sample broken chunks (up to ").append(maxSampleChunks).append("): ")
                            .append(String.join(" ", c.sampleChunks)).append('\n');
                }
                sb.append('\n');
            }

            if (reportFile.getParent() != null) {
                Files.createDirectories(reportFile.getParent());
            }
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            Path tmp = reportFile.resolveSibling(reportFile.getFileName().toString() + ".tmp");
            try {
                Files.write(tmp, bytes);
                Files.move(tmp, reportFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                Files.write(reportFile, bytes);
            }
        } catch (Throwable ignored) {
            // A diagnostic must never break the server.
        }
    }

    private static String namespaceOf(String structureId) {
        if (structureId == null || structureId.isEmpty()) {
            return "<unknown> (no structure context)";
        }
        int colon = structureId.indexOf(':');
        return colon > 0 ? structureId.substring(0, colon) : structureId;
    }

    private static final class Ctx {
        String structure;
        int cx;
        int cz;

        Ctx(String structure, int cx, int cz) {
            this.structure = structure;
            this.cx = cx;
            this.cz = cz;
        }
    }

    private static final class Culprit {
        volatile long total;
        Map<String, AtomicLong> typeCounts = new ConcurrentHashMap<>();
        Map<String, AtomicLong> structures = new ConcurrentHashMap<>();
        Set<String> sampleChunks = ConcurrentHashMap.newKeySet();

        synchronized void add(String type, String structure, Ctx ctx, int maxSamples) {
            total++;
            typeCounts.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
            if (structure != null) {
                structures.computeIfAbsent(structure, k -> new AtomicLong()).incrementAndGet();
            }
            if (ctx != null && sampleChunks.size() < maxSamples) {
                sampleChunks.add("[" + ctx.cx + "," + ctx.cz + "]");
            }
        }
    }
}
