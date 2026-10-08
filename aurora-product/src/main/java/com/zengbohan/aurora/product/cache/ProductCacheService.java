package com.zengbohan.aurora.product.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zengbohan.aurora.common.exception.BusinessException;
import com.zengbohan.aurora.common.exception.ErrorCode;
import com.zengbohan.aurora.product.entity.Sku;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 商品详情的读写模式：cache aside + 空值缓存 + 布隆过滤器 + 逻辑过期 +
 * 互斥重建 + 随机化物理 TTL；管理端写入走"先删缓存、再延迟双删"。
 */
@Service
@RefreshScope
public class ProductCacheService {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheService.class);
    private static final String KEY_PREFIX = "aurora:product:sku:";
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
        // 每次（重新）创建都打日志：配置中心改 TTL 会重建这个 @RefreshScope Bean，
        // 在这里打印出新值
        log.info("product cache ready: physical ttl {}s", physicalTtlSeconds);
    }

    public Sku getById(long id, Function<Long, Sku> dbLoader) {
        // 布隆过滤器没有假阴性：不存在的 id 不会触达缓存或 DB
        if (!bloomFilterHolder.get().mightContain(String.valueOf(id))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String key = KEY_PREFIX + id;
        CacheWrapper<Sku> wrapper = readWrapper(key);

        if (wrapper == null) {
            return coldLoad(key, id, dbLoader);
        }
        if (wrapper.getData() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (wrapper.getExpireAt().isAfter(clock.instant())) {
            return wrapper.getData();
        }
        return refreshIfAllowed(key, id, wrapper, dbLoader);
    }

    private CacheWrapper<Sku> readWrapper(String key) {
        String json = store.get(key);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructParametricType(CacheWrapper.class, Sku.class));
        } catch (Exception e) {
            log.warn("unreadable cache entry {}, treating as cold", key, e);
            return null;
        }
    }

    private Sku coldLoad(String key, long id, Function<Long, Sku> dbLoader) {
        if (store.setIfAbsent(MUTEX_PREFIX + id, "1", Duration.ofSeconds(10))) {
            try {
                Sku sku = dbLoader.apply(id);
                writeWrapper(key, sku);
                return skuOrThrow(sku);
            } finally {
                store.delete(MUTEX_PREFIX + id);
            }
        }
        // 没抢到互斥锁：先轮询胜出者的写入，再做判断
        for (int i = 0; i < 4; i++) {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            CacheWrapper<Sku> wrapper = readWrapper(key);
            if (wrapper != null) {
                if (wrapper.getData() == null) {
                    // 胜出者缓存了一次真实的未命中
                    throw new BusinessException(ErrorCode.NOT_FOUND);
                }
                return wrapper.getData();
            }
        }
        // 胜出者太慢或它的互斥锁已过期：宁可重复查一次 DB，
        // 也不要对存在的商品返回假 404
        Sku sku = dbLoader.apply(id);
        writeWrapper(key, sku);
        return skuOrThrow(sku);
    }

    private Sku refreshIfAllowed(String key, long id, CacheWrapper<Sku> wrapper, Function<Long, Sku> dbLoader) {
        if (store.setIfAbsent(MUTEX_PREFIX + id, "1", Duration.ofSeconds(10))) {
            rebuildExecutor.execute(() -> {
                try {
                    writeWrapper(key, dbLoader.apply(id));
                } catch (Exception e) {
                    log.error("async cache rebuild failed for {}", key, e);
                } finally {
                    store.delete(MUTEX_PREFIX + id);
                }
            });
        }
        // 逻辑过期契约：单个重建者刷新期间照常返回旧值
        return wrapper.getData();
    }

    private void writeWrapper(String key, Sku sku) {
        if (sku == null) {
            store.put(key, json(new CacheWrapper<>(null, clock.instant().plus(NULL_TTL))), NULL_TTL.plus(NULL_TTL));
            return;
        }
        Duration logical = LOGICAL_TTL;
        CacheWrapper<Sku> wrapper = new CacheWrapper<>(sku, clock.instant().plus(logical));
        // 物理 TTL 长于逻辑过期加上抖动，让重建过程无缝衔接
        Duration physical = Duration.ofSeconds(physicalTtlSeconds)
                .plusSeconds(ThreadLocalRandom.current().nextLong(0, 3600));
        store.put(key, json(wrapper), physical);
    }

    private Sku skuOrThrow(Sku sku) {
        if (sku == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return sku;
    }

    public void evict(long id) {
        store.delete(KEY_PREFIX + id);
    }

    public void bloomPut(long id) {
        bloomFilterHolder.get().put(String.valueOf(id));
    }

    public void doubleDeleteAfterUpdate(long id, Runnable dbUpdate) {
        dbUpdate.run();
        evict(id);
        doubleDeleteScheduler.schedule(() -> {
            try {
                evict(id);
            } catch (Exception e) {
                log.error("delayed double delete failed for {}", id, e);
            }
        }, DOUBLE_DELETE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("cache serialization failed", e);
        }
    }
}
