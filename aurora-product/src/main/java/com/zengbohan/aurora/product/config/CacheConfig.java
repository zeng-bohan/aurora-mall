package com.zengbohan.aurora.product.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zengbohan.aurora.product.cache.StringBloomFilter;
import com.zengbohan.aurora.product.cache.VolatileBloomFilterHolder;
import com.zengbohan.aurora.product.entity.Sku;
import com.zengbohan.aurora.product.mapper.SkuMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * 启动时用全部 sku id 播种；管理端新建的商品会继续加入。
     * 容量按 10 万 id、1% 假阳性设计。当 aurora.cache.bloom.seed-on-startup=false
     * 时跳过播种查询（测试缝：上下文加载测试
     * 跑在没有数据库的 CI runner 上）。
     */
    @Bean
    public VolatileBloomFilterHolder skuBloomFilterHolder(SkuMapper skuMapper,
                                            @org.springframework.beans.factory.annotation.Value("${aurora.cache.bloom.seed-on-startup:true}") boolean seedOnStartup) {
        StringBloomFilter filter = new StringBloomFilter(100_000, 0.01);
        if (seedOnStartup) {
            seedFrom(skuMapper, filter);
        }
        return new VolatileBloomFilterHolder(filter);
    }

    /**
     * 定期重播种：多实例/漏种收敛。整体构建新过滤器后原子换入，
     * 读路径永远拿完整实例；shared-nothing 本地位图的多实例局限见 StringBloomFilter。
     * seed-on-startup=false（无库测试缝）时整个 job 不装配。
     */
    @Bean
    @ConditionalOnProperty(name = "aurora.cache.bloom.seed-on-startup", havingValue = "true", matchIfMissing = true)
    public Object bloomReseedJob(SkuMapper skuMapper, VolatileBloomFilterHolder holder) {
        return new Object() {
            private final Logger log = LoggerFactory.getLogger("bloom-reseed");

            @Scheduled(fixedDelayString = "${aurora.cache.bloom.reseed-interval-ms:300000}",
                    initialDelay = 120_000)
            public void reseed() {
                StringBloomFilter fresh = new StringBloomFilter(100_000, 0.01);
                seedFrom(skuMapper, fresh);
                holder.replace(fresh);
                log.info("bloom filter reseeded from db");
            }
        };
    }

    private static void seedFrom(SkuMapper skuMapper, StringBloomFilter filter) {
        for (Sku sku : skuMapper.selectList(new LambdaQueryWrapper<Sku>().select(Sku::getId))) {
            filter.put(String.valueOf(sku.getId()));
        }
    }
}
