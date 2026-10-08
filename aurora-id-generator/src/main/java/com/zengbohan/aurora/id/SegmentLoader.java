package com.zengbohan.aurora.id;

// 新分配的号段，覆盖区间 (maxId - step, maxId]。
@FunctionalInterface
public interface SegmentLoader {

    Segment next(String bizTag);

    record Segment(long maxId, int step) {

        public Segment {
            if (step <= 0) {
                throw new IllegalArgumentException("segment step must be positive");
            }
        }
    }
}
