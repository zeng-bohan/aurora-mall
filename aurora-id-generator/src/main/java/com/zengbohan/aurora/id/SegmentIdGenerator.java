package com.zengbohan.aurora.id;

import com.zengbohan.aurora.id.SegmentLoader.Segment;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Segment (leaf) allocator with double buffering: when the current segment is
 * 60% consumed the next one is already fetched, so the switch at exhaustion
 * is O(1). Synchronized internally — callers share one instance freely.
 */
public class SegmentIdGenerator implements IdGenerator {

    private static final double PREFETCH_THRESHOLD = 0.6;

    private final SegmentLoader loader;
    private final String bizTag;
    private Segment current;
    private Segment prefetched;
    private boolean prefetchFailed;
    private final AtomicLong offset = new AtomicLong();

    public SegmentIdGenerator(String bizTag, SegmentLoader loader) {
        this.bizTag = bizTag;
        this.loader = loader;
    }

    @Override
    public synchronized long nextId() {
        // switch/prefetch BEFORE computing, so offset stays within [0, step)
        if (current == null || offset.get() >= current.step()) {
            switchTo(takePrefetchedOrLoad());
        } else if (prefetched == null && !prefetchFailed
                && offset.get() >= current.step() * PREFETCH_THRESHOLD) {
            // best effort: the exhaustion switch retries the load and surfaces
            // the real failure; swallow here so current ids keep flowing
            try {
                prefetched = loader.next(bizTag);
            } catch (RuntimeException e) {
                prefetchFailed = true;
            }
        }
        return current.maxId() - current.step() + 1 + offset.getAndIncrement();
    }

    private Segment takePrefetchedOrLoad() {
        Segment next = prefetched;
        prefetched = null;
        prefetchFailed = false;
        return next != null ? next : loader.next(bizTag);
    }

    private void switchTo(Segment segment) {
        current = segment;
        offset.set(0);
    }
}
