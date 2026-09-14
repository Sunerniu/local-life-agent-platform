package com.hmdp.cache;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Arrays;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/** 店铺详情二级缓存；版本校验防止并发更新后旧查询重新填充 Redis。 */
@Component
public class ShopCache {
    static final String VERSION_PREFIX = "cache:shop:version:";
    static final DefaultRedisScript<Long> FILL = new DefaultRedisScript<>(
            "if (redis.call('GET', KEYS[2]) or '0') ~= ARGV[1] then return 0 end " +
            "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]); return 1", Long.class);
    static final DefaultRedisScript<Long> EVICT = new DefaultRedisScript<>(
            "redis.call('INCR', KEYS[2]); redis.call('DEL', KEYS[1]); " +
            "return redis.call('PUBLISH', ARGV[1], ARGV[2])", Long.class);

    private final StringRedisTemplate redis;
    private final String channel;
    // 存 JSON 快照，避免调用方修改可变 Shop 对象污染其他请求。
    private final Cache<Long, String> local;
    private final Object[] locks = new Object[256];

    @Autowired
    public ShopCache(StringRedisTemplate redis,
                     @Value("${hmdp.shop-cache.maximum-size:10000}") long maximumSize,
                     @Value("${hmdp.shop-cache.ttl:30s}") String ttl,
                     @Value("${hmdp.shop-cache.channel:hmdp:shop:invalidate}") String channel) {
        this(redis, maximumSize, DurationStyle.detectAndParse(ttl), channel);
    }

    ShopCache(StringRedisTemplate redis, long maximumSize, Duration ttl, String channel) {
        if (maximumSize <= 0 || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("店铺本地缓存容量和 TTL 必须大于 0");
        }
        this.redis = redis;
        this.channel = channel;
        this.local = Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(ttl).build();
        Arrays.setAll(locks, i -> new Object());
    }

    public Shop get(Long id, Function<Long, Shop> database) {
        // 同一分片的加载和失效串行，防止收到通知后在途查询回填本地旧值。
        synchronized (lock(id)) {
            String json = local.getIfPresent(id);
            if (json != null) return decode(json);
            String version = redis.opsForValue().get(VERSION_PREFIX + id);
            json = redis.opsForValue().get(CACHE_SHOP_KEY + id);
            if (json == null) {
                Shop shop = database.apply(id);
                json = shop == null ? "" : JSONUtil.toJsonStr(shop);
                Long filled = redis.execute(FILL, Arrays.asList(CACHE_SHOP_KEY + id, VERSION_PREFIX + id),
                        version == null ? "0" : version, json,
                        String.valueOf((shop == null ? CACHE_NULL_TTL : CACHE_SHOP_TTL) * 60));
                // 并发更新时仍可返回本次查询快照，但不得缓存该快照。
                if (!Long.valueOf(1).equals(filled)) return decode(json);
            }
            local.put(id, json);
            return decode(json);
        }
    }

    public void invalidateLocal(Long id) {
        synchronized (lock(id)) {
            local.invalidate(id);
        }
    }

    public void evictAfterCommit(Long id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict(id); }
            });
        } else {
            evict(id);
        }
    }

    private void evict(Long id) {
        try {
            redis.execute(EVICT, Arrays.asList(CACHE_SHOP_KEY + id, VERSION_PREFIX + id), channel, id.toString());
        } finally {
            invalidateLocal(id);
        }
    }

    private Object lock(Long id) {
        return locks[Math.floorMod(id.hashCode(), locks.length)];
    }

    private Shop decode(String json) {
        return json.isEmpty() ? null : JSONUtil.toBean(json, Shop.class);
    }
}
