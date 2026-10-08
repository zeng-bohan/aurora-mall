package com.zengbohan.aurora.id;

public interface IdGenerator {

    // 下一个全局唯一、大致递增的 id。
    long nextId();
}
