package com.zengbohan.aurora.seckill.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 活动库存：DB 侧最终事实。Redis 是预扣快照（S2 起由 Lua 维护），预热时以此为准。
 */
@TableName("seckill_stock")
public class SeckillStock {

    @TableId("activity_id")
    private Long activityId;
    private Integer total;
    private Integer available;
    private LocalDateTime updatedAt;

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Integer getAvailable() {
        return available;
    }

    public void setAvailable(Integer available) {
        this.available = available;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
