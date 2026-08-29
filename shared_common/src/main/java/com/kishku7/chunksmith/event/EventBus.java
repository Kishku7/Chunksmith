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

package com.kishku7.chunksmith.event;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class EventBus {
    private static final MethodHandle accept;

    static {
        MethodHandle acceptMethodHandle = null;
        try {
            acceptMethodHandle = MethodHandles.publicLookup().findVirtual(Consumer.class, "accept", MethodType.methodType(void.class, Object.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            e.printStackTrace();
        }
        accept = acceptMethodHandle;
    }

    private final Map<Class<?>, Set<Consumer<?>>> subscribers = new HashMap<>();

    public <T> void subscribe(Class<T> eventClass, Consumer<T> subscriber) {
        subscribers.computeIfAbsent(eventClass, x -> new HashSet<>());
        subscribers.get(eventClass).add(subscriber);
    }

    public <T> void unsubscribe(Class<T> eventClass, Consumer<T> subscriber) {
        subscribers.computeIfAbsent(eventClass, x -> new HashSet<>());
        subscribers.get(eventClass).remove(subscriber);
    }

    public void unsubscribeAll() {
        subscribers.clear();
    }

    public void unsubscribeAll(Class<?> eventClass) {
        subscribers.remove(eventClass);
    }

    public void call(Object event) {
        Class<?> eventClass = event.getClass();
        if (accept == null || !subscribers.containsKey(eventClass)) {
            return;
        }
        subscribers.get(eventClass).forEach(subscriber -> {
            try {
                accept.invoke(subscriber, event);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });
    }
}
