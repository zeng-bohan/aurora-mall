package com.zengbohan.aurora.sharding.pilot;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.Properties;

/**
 * user_id 取模分库：ds0 / ds1。
 *
 * <p>用自定义算法而不是内置 INLINE：INLINE 的表达式求值依赖 Groovy，试点不想为一行取模多带一个依赖；
 * 顺带把算法 SPI 的接入姿势跑通了。生产落地时换成 INLINE / HASH_MOD 都行，配置形状一致。
 */
public class UserIdModShardingAlgorithm implements StandardShardingAlgorithm<Long> {

    private static final int SHARD_COUNT = 2;

    @Override
    public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<Long> shardingValue) {
        // floorMod：负数也能落到合法分片（取模分片最容易踩的负数坑：Java 的 % 会给出 -1）
        String target = "ds" + Math.floorMod(shardingValue.getValue(), SHARD_COUNT);
        if (!availableTargetNames.contains(target)) {
            throw new IllegalStateException(
                    "no shard for user_id=" + shardingValue.getValue() + ", available=" + availableTargetNames);
        }
        return target;
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         RangeShardingValue<Long> shardingValue) {
        // 不做区间裁剪：范围条件一律广播到所有分片（这条代价在报告里单独记一笔）
        return availableTargetNames;
    }

    @Override
    public String getType() {
        return "USER_ID_MOD";
    }

    @Override
    public void init(Properties props) {
        // 无参数
    }
}
