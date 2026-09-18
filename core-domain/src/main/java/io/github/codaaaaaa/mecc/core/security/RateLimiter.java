package io.github.codaaaaaa.mecc.core.security;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Token-bucket rate limiter keyed by an arbitrary string (IP address, player UUID, ...). Thread-safe.
 * Memory is bounded: idle buckets are dropped, and when {@code maxKeys} is reached the oldest are evicted.
 */
public final class RateLimiter {
    private final int capacity;
    private final double refillPerNano;
    private final int maxKeys;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new HashMap<>();

    /**
     * @param capacity burst size
     * @param period   time in which a completely empty bucket refills
     */
    public RateLimiter(int capacity, Duration period, int maxKeys, Clock clock) {
        if (capacity < 1 || period.isNegative() || period.isZero() || maxKeys < 1) {
            throw new IllegalArgumentException("invalid rate limiter settings");
        }
        this.capacity = capacity;
        this.refillPerNano = capacity / (double) period.toNanos();
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    /** Consumes one permit for {@code key}. Returns {@code false} when the caller is over the limit. */
    public synchronized boolean tryAcquire(String key) {
        long now = nanos();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                evict(now);
            }
            bucket = new Bucket(capacity, now);
            buckets.put(key, bucket);
        }
        bucket.refill(now);
        if (bucket.tokens < 1) {
            return false;
        }
        bucket.tokens -= 1;
        return true;
    }

    private void evict(long now) {
        for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext(); ) {
            Bucket bucket = it.next();
            bucket.refill(now);
            if (bucket.tokens >= capacity) {
                it.remove();
            }
        }
        if (buckets.size() >= maxKeys) {
            // Still full of active keys: drop the one that was refilled longest ago.
            buckets.entrySet().stream()
                    .min((a, b) -> Long.compare(a.getValue().updatedAt, b.getValue().updatedAt))
                    .map(Map.Entry::getKey)
                    .ifPresent(buckets::remove);
        }
    }

    private long nanos() {
        Duration sinceEpoch = Duration.ofMillis(clock.millis());
        return sinceEpoch.toNanos();
    }

    private final class Bucket {
        private double tokens;
        private long updatedAt;

        private Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }

        private void refill(long now) {
            if (now > updatedAt) {
                tokens = Math.min(capacity, tokens + (now - updatedAt) * refillPerNano);
                updatedAt = now;
            }
        }
    }
}
