/*
 * Chunksmith -- a chunk pre-generator for Minecraft.
 * Copyright (C) pop4959 and contributors.
 *
 * This file is taken from Chunky (https://github.com/pop4959/Chunky)
 * and is unchanged apart from the project rename.
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

package com.kishku7.chunksmith.api.event.task;

import com.kishku7.chunksmith.event.Event;

/**
 * Event which is fired when a generation task calculates progress.
 *
 * @param world    The world identifier
 * @param chunks   The number of chunks generated
 * @param complete If the generation task completed
 * @param progress The percent progress
 * @param hours    The number of hours elapsed for this task
 * @param minutes  The number of minutes elapsed for this task
 * @param seconds  The number of seconds elapsed for this task
 * @param rate     The current average generation rate
 * @param x        The current chunk's x coordinate
 * @param z        The current chunk's z coordinate
 */
public record GenerationProgressEvent(String world, long chunks, boolean complete, float progress, long hours,
                                      long minutes, long seconds, double rate, long x, long z) implements Event {
}
