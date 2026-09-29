package com.zengbohan.aurora.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("tx_message")
public class TxMessage {

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_SENT = 1;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizKey;
    private String topic;
    private String tag;
    private String payload;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;

    public Long getId() {
        return id;
    }

    public String getBizKey() {
        return bizKey;
    }

    public void setBizKey(String bizKey) {
        this.bizKey = bizKey;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }
}
