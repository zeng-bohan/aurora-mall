package com.zengbohan.aurora.product.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.zengbohan.aurora.product.cache.StringBloomFilter;
import com.zengbohan.aurora.product.cache.VolatileBloomFilterHolder;
import com.zengbohan.aurora.product.mapper.ProductSpuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class CacheConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public ThreadPoolTaskExecutor cacheRebuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadFactory(new CustomizableThreadFactory("cache-rebuild-"));
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }

    @Bean
    public ScheduledExecutorService doubleDeleteScheduler() {
        return Executors.newSingleThreadScheduledExecutor(new CustomizableThreadFactory("double-delete-"));
    }

    @Bean
    public VolatileBloomFilterHolder spuBloomFilterHolder(
            ProductSpuMapper spuMapper,
            @Value("${aurora.cache.bloom.seed-on-startup:true}") boolean seedOnStartup) {
        StringBloomFilter filter = new StringBloomFilter(100_000, 0.01);
        if (seedOnStartup) {
            seedFrom(spuMapper, filter);
        }
        return new VolatileBloomFilterHolder(filter);
    }

    @Bean
    @ConditionalOnProperty(name = "aurora.cache.bloom.seed-on-startup", havingValue = "true", matchIfMissing = true)
    public Object bloomReseedJob(ProductSpuMapper spuMapper, VolatileBloomFilterHolder holder) {
        return new Object() {
            private final Logger log = LoggerFactory.getLogger("bloom-reseed");

            @Scheduled(fixedDelayString = "${aurora.cache.bloom.reseed-interval-ms:300000}",
                    initialDelay = 120_000)
            public void reseed() {
                StringBloomFilter fresh = new StringBloomFilter(100_000, 0.01);
                seedFrom(spuMapper, fresh);
                holder.replace(fresh);
                log.info("bloom filter reseeded from db");
            }
        };
    }

    private static void seedFrom(ProductSpuMapper spuMapper, StringBloomFilter filter) {
        for (Long id : spuMapper.findAllIds()) {
            filter.put(String.valueOf(id));
        }
    }
}
