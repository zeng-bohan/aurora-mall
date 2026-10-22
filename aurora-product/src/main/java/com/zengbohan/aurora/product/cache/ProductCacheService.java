package com.zengbohan.aurora.product.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 商品详情的读写模式：cache aside + 空值缓存 + 布隆过滤器 + 逻辑过期 +
 * 互斥重建 + 随机化物理 TTL；管理端写入走"先删缓存、再延迟双删"。
 * 泛型化后同时服务 SPU 详情与任何同构视图。
 */
@Service
@RefreshScope
public class ProductCacheService {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheService.class);
    private static final String KEY_PREFIX = "aurora:product:spu:";
    private static final String MUTEX_PREFIX = "aurora:product:mutex:";
    private static final Duration NULL_TTL = Duration.ofSeconds(30);
    private static final Duration LOGICAL_TTL = Duration.ofMinutes(10);
    private static final Duration DOUBLE_DELETE_DELAY = Duration.ofMillis(500);

    private final CacheStore store;
    private final VolatileBloomFilterHolder bloomFilterHolder;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Executor rebuildExecutor;
    private final ScheduledExecutorService doubleDeleteScheduler;
    private final long physicalTtlSeconds;

    public ProductCacheService(CacheStore store,
                               VolatileBloomFilterHolder bloomFilterHolder,
                               ObjectMapper mapper,
                               Clock clock,
                               @Qualifier("cacheRebuildExecutor") Executor rebuildExecutor,
                               @Qualifier("doubleDeleteScheduler") ScheduledExecutorService doubleDeleteScheduler,
                               @Value("${aurora.cache.physical-ttl-seconds:86400}") long physicalTtlSeconds) {
        this.store = store;
        this.bloomFilterHolder = bloomFilterHolder;
        this.mapper = mapper;
        this.clock = clock;
        this.rebuildExecutor = rebuildExecutor;
        this.doubleDeleteScheduler = doubleDeleteScheduler;
        this.physicalTtlSeconds = physicalTtlSeconds;
        log.info("product cache ready: physical ttl {}s", physicalTtlSeconds);
    }

    public <T> T get(long id, Class<T> type, Supplier<T> dbLoader) {
        if (!bloomFilterHolder.get().mightContain(String.valueOf(id))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String key = KEY_PREFIX + id;
        CacheWrapper<T> wrapper = readWrapper(key, type);
        if (wrapper == null) {
            return coldLoad(key, id, type, dbLoader);
        }
        if (wrapper.getData() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (wrapper.getExpireAt().isAfter(clock.instant())) {
            return wrapper.getData();
        }
        return refreshIfAllowed(key, id, type, wrapper, dbLoader);
    }

    private <T> CacheWrapper<T> readWrapper(String key, Class<T> type) {
        String json = safeGet(key);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructParametricType(CacheWrapper.class, type));
        } catch (Exception e) {
            log.warn("unreadable cache entry {}, treating as cold", key, e);
            return null;
        }
    }

    private <T> T coldLoad(String key, long id, Class<T> type, Supplier<T> dbLoader) {
        Boolean leader = safeSetIfAbsent(MUTEX_PREFIX + id, "1", Duration.ofSeconds(10));
        if (leader == null) {
            T value = dbLoader.get();
            writeWrapper(key, value);
            return valueOrThrow(value);
        }
        if (leader) {
            try {
                T value = dbLoader.get();
                writeWrapper(key, value);
                return valueOrThrow(value);
            } finally {
                safeDelete(MUTEX_PREFIX + id);
            }
        }
        for (int i = 0; i < 4; i++) {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            CacheWrapper<T> wrapper = readWrapper(key, type);
            if (wrapper != null) {
                if (wrapper.getData() == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND);
                }
                return wrapper.getData();
            }
        }
        T value = dbLoader.get();
        writeWrapper(key, value);
        return valueOrThrow(value);
    }

    private <T> T refreshIfAllowed(String key, long id, Class<T> type,
                                   CacheWrapper<T> wrapper, Supplier<T> dbLoader) {
        Boolean leader = safeSetIfAbsent(MUTEX_PREFIX + id, "1", Duration.ofSeconds(10));
        if (Boolean.TRUE.equals(leader)) {
            try {
                rebuildExecutor.execute(() -> {
                    try {
                        writeWrapper(key, dbLoader.get());
                    } catch (Exception e) {
                        log.error("async cache rebuild failed for {}", key, e);
                    } finally {
                        safeDelete(MUTEX_PREFIX + id);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("cache rebuild rejected for {}: {}", key, e.toString());
                safeDelete(MUTEX_PREFIX + id);
            }
        }
        return wrapper.getData();
    }

    private void writeWrapper(String key, Object value) {
        try {
            if (value == null) {
                store.put(key, json(new CacheWrapper<>(null, clock.instant().plus(NULL_TTL))),
                        NULL_TTL.plus(NULL_TTL));
                return;
            }
            CacheWrapper<Object> wrapper = new CacheWrapper<>(value, clock.instant().plus(LOGICAL_TTL));
            Duration physical = Duration.ofSeconds(physicalTtlSeconds)
                    .plusSeconds(ThreadLocalRandom.current().nextLong(0, 3600));
            store.put(key, json(wrapper), physical);
        } catch (RuntimeException e) {
            log.warn("cache write failed for {}: {}", key, e.toString());
        }
    }

    private static <T> T valueOrThrow(T value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return value;
    }

    public void evict(long id) {
        safeDelete(KEY_PREFIX + id);
    }

    /** 事务内调用：提交后再删缓存，避免“删完缓存、事务尚未提交”期间旧数据被回填。 */
    public void evictAfterCommit(long id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictWithDelayedSecondDelete(id);
                }
            });
        } else {
            evictWithDelayedSecondDelete(id);
        }
    }

    public void evictWithDelayedSecondDelete(long id) {
        evict(id);
        scheduleSecondEvict(id);
    }

    public void bloomPut(long id) {
        bloomFilterHolder.get().put(String.valueOf(id));
    }

    public void doubleDeleteAfterUpdate(long id, Runnable dbUpdate) {
        dbUpdate.run();
        evictWithDelayedSecondDelete(id);
    }

    private void scheduleSecondEvict(long id) {
        doubleDeleteScheduler.schedule(() -> {
            try {
                evict(id);
            } catch (Exception e) {
                log.error("delayed double delete failed for {}", id, e);
            }
        }, DOUBLE_DELETE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    private String safeGet(String key) {
        try {
            return store.get(key);
        } catch (RuntimeException e) {
            log.warn("cache read failed for {}: {}", key, e.toString());
            return null;
        }
    }

    private Boolean safeSetIfAbsent(String key, String value, Duration ttl) {
        try {
            return store.setIfAbsent(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("cache mutex unavailable for {}: {}", key, e.toString());
            return null;
        }
    }

    private void safeDelete(String key) {
        try {
            store.delete(key);
        } catch (RuntimeException e) {
            log.warn("cache delete failed for {}: {}", key, e.toString());
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cache serialization failed", e);
        }
    }
}
