package com.zengbohan.aurora.id;

public interface IdGenerator {

    /** Next globally unique, roughly increasing id. */
    long nextId();
}
