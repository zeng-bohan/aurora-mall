package com.zengbohan.aurora.id;

/** A freshly allocated segment covering (maxId - step, maxId]. */
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
