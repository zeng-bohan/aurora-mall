package com.zengbohan.aurora.common.idempotent;

// 基于 (biz_type, biz_key) 唯一索引的去重表守卫。
public interface DedupStore {

    // 插入成功（首次出现）返回 true。
    boolean tryInsert(String bizType, String bizKey);

    /** 移除因被守卫调用失败而留下的守卫，让重投递能重新处理。
     *  在调用方自有事务内，回滚已经移除它；这里覆盖无事务的场景
     *  （裸 @Idempotent(DB_DEDUP)）。 */
    void remove(String bizType, String bizKey);

    /** 持久化存在性探测，用于识别跨事件的先前补偿
     *  （例如已提交的 "stock-release" 必须中和迟到的 stock-reserved）。 */
    boolean exists(String bizType, String bizKey);
}
