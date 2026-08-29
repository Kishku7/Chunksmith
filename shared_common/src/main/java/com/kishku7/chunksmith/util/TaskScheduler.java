/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) 2025-2026 Kishku7
 * Copyright (C) pop4959 and contributors.
 *
 * This file is derived from Chunky (https://github.com/pop4959/Chunky).
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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskScheduler {
    /** slf4j: JUL does not reach the game log on any loader, so this used to vanish. */
    private static final Logger LOGGER = LoggerFactory.getLogger("Chunksmith");

    private final ExecutorService executor;
    private final Set<Future<?>> futures = ConcurrentHashMap.newKeySet();

    public TaskScheduler() {
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(3, Integer.MAX_VALUE, 5, TimeUnit.MINUTES, new SynchronousQueue<>());
        threadPoolExecutor.setThreadFactory(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
        threadPoolExecutor.prestartAllCoreThreads();
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        this.executor = threadPoolExecutor;
    }

    public void runTask(Runnable runnable) {
        futures.add(executor.submit(() -> { try { runnable.run(); } catch (Throwable t) { LOGGER.error("Generation task threw an uncaught exception", t); throw t; } }));
        futures.removeIf(Future::isDone);
    }

    public void cancelTasks() {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
        futures.clear();
    }
}
