package com.kishku7.chunksmith.lod.net;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A UUID, a name and an IP are each necessary and none of them authenticates. UUIDs and names are
 * PUBLIC -- anyone can look one up and send it, so they identify but do not prove. An IP false-accepts
 * two players behind one NAT and false-rejects a player roaming onto mobile data. Hence a token.
 */
public final class CsLodTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Map<String, Entry> byToken = new ConcurrentHashMap<>();
    private final Map<UUID, String> byPlayer = new ConcurrentHashMap<>();

    public String issue(UUID player, String ip) {
        revoke(player);
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        final String token = ENCODER.encodeToString(bytes);
        byToken.put(token, new Entry(player, ip, System.currentTimeMillis() + CsLodProtocol.TOKEN_TTL_MILLIS));
        byPlayer.put(player, token);
        return token;
    }

    public UUID validate(String token, String ip, OnlineCheck online) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        final Entry entry = byToken.get(token);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAt) {
            byToken.remove(token);
            byPlayer.remove(entry.player, token);
            return null;
        }
        if (!entry.ip.equals(ip)) {
            return null;
        }
        if (!online.isOnline(entry.player)) {
            return null;
        }
        return entry.player;
    }

    public void revoke(UUID player) {
        final String existing = byPlayer.remove(player);
        if (existing != null) {
            byToken.remove(existing);
        }
    }

    public void clear() {
        byToken.clear();
        byPlayer.clear();
    }

    public int size() {
        return byToken.size();
    }

    @FunctionalInterface
    public interface OnlineCheck {
        boolean isOnline(UUID player);
    }

    private static final class Entry {
        private final UUID player;
        private final String ip;
        private final long expiresAt;

        private Entry(UUID player, String ip, long expiresAt) {
            this.player = player;
            this.ip = ip;
            this.expiresAt = expiresAt;
        }
    }
}
