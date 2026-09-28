package com.zengbohan.aurora.product.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.zengbohan.aurora.product.cache.StringBloomFilter;
import com.zengbohan.aurora.product.entity.Sku;
import com.zengbohan.aurora.product.mapper.SkuMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    /**
     * Seeded with every sku id at startup; admin creates extend it.
     * Sized for 100k ids at 1% false positives. The seed query is skipped when
     * aurora.cache.bloom.seed-on-startup=false (test seam: context-load tests
     * run on CI runners with no database).
     */
    @Bean
    public StringBloomFilter skuBloomFilter(SkuMapper skuMapper,
                                            @org.springframework.beans.factory.annotation.Value("${aurora.cache.bloom.seed-on-startup:true}") boolean seedOnStartup) {
        StringBloomFilter filter = new StringBloomFilter(100_000, 0.01);
        if (seedOnStartup) {
            for (Sku sku : skuMapper.selectList(new LambdaQueryWrapper<Sku>().select(Sku::getId))) {
                filter.put(String.valueOf(sku.getId()));
            }
        }
        return filter;
    }
}
