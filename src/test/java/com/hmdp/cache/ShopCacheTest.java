package com.hmdp.cache;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopCacheTest {
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    Function<Long, Shop> database;
    ShopCache cache;

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        database = mock(Function.class);
        when(redis.opsForValue()).thenReturn(values);
        cache = new ShopCache(redis, 100, Duration.ofSeconds(30), "test:invalidate");
    }

    Shop shop(String name) {
        Shop shop = new Shop(); shop.setId(1L); shop.setName(name); return shop;
    }

    @Test void localHitSkipsRedisAndReturnsIndependentSnapshot() {
        when(values.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(shop("original")));
        cache.get(1L, database).setName("mutated");
        clearInvocations(redis, values);
        assertEquals("original", cache.get(1L, database).getName());
        verifyNoInteractions(redis, values, database);
    }

    @Test void databaseMissFillsBothLevels() {
        when(database.apply(1L)).thenReturn(shop("database"));
        when(redis.execute(eq(ShopCache.FILL), anyList(), any(), any(), any())).thenReturn(1L);
        assertEquals("database", cache.get(1L, database).getName());
        assertEquals("database", cache.get(1L, database).getName());
        verify(database, times(1)).apply(1L);
        verify(redis).execute(eq(ShopCache.FILL), anyList(), eq("0"), any(), eq("1800"));
    }

    @Test void missingShopUsesEmptyStringAndNegativeCache() {
        when(redis.execute(eq(ShopCache.FILL), anyList(), any(), any(), any())).thenReturn(1L);
        assertNull(cache.get(1L, database));
        assertNull(cache.get(1L, database));
        verify(database, times(1)).apply(1L);
        verify(redis).execute(eq(ShopCache.FILL), anyList(), eq("0"), eq(""), eq("120"));
    }

    @Test void redisNegativeHitSkipsDatabase() {
        when(values.get("cache:shop:1")).thenReturn("");
        assertNull(cache.get(1L, database));
        verifyNoInteractions(database);
    }

    @Test void rejectedStaleFillIsNotLocallyCached() {
        when(database.apply(1L)).thenReturn(shop("old"), shop("new"));
        when(redis.execute(eq(ShopCache.FILL), anyList(), any(), any(), any())).thenReturn(0L, 1L);
        assertEquals("old", cache.get(1L, database).getName());
        assertEquals("new", cache.get(1L, database).getName());
    }

    @Test void notificationInvalidatesIndependentInstances() {
        ShopCache other = new ShopCache(redis, 100, Duration.ofSeconds(30), "test:invalidate");
        when(values.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(shop("old")));
        cache.get(1L, database); other.get(1L, database);
        cache.invalidateLocal(1L); other.invalidateLocal(1L);
        when(values.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(shop("new")));
        assertEquals("new", cache.get(1L, database).getName());
        assertEquals("new", other.get(1L, database).getName());
    }

    @Test void evictionRunsOnlyAfterCommit() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evictAfterCommit(1L);
            verifyNoInteractions(redis);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(redis).execute(eq(ShopCache.EVICT), anyList(), eq("test:invalidate"), eq("1"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void rollbackDoesNotEvict() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evictAfterCommit(1L);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(redis);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void notificationCannotBeOvertakenByInFlightLoad() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1);
        when(values.get("cache:shop:1")).thenAnswer(invocation -> {
            loading.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return JSONUtil.toJsonStr(shop("old"));
        });
        try {
            Future<Shop> read = executor.submit(() -> cache.get(1L, database));
            assertTrue(loading.await(3, TimeUnit.SECONDS));
            Future<?> invalidation = executor.submit(() -> cache.invalidateLocal(1L));
            release.countDown(); read.get(3, TimeUnit.SECONDS); invalidation.get(3, TimeUnit.SECONDS);
            when(values.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(shop("new")));
            assertEquals("new", cache.get(1L, database).getName());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
