package com.zengbohan.aurora.id;

import com.zengbohan.aurora.id.SegmentLoader.Segment;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段（leaf）分配器，双缓冲：当前号段消耗到 60% 时就预先取好下一段，
 * 因此耗尽时的切换是 O(1)。内部已加同步——调用方可自由共享同一实例。
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
        // 先切换/预取再计算，保证 offset 落在 [0, step) 内
        if (current == null || offset.get() >= current.step()) {
            switchTo(takePrefetchedOrLoad());
        } else if (prefetched == null && !prefetchFailed
                && offset.get() >= current.step() * PREFETCH_THRESHOLD) {
            // 尽力而为：耗尽切换时会重试加载并抛出真正的失败；
            // 这里先吞掉，让当前号段继续发号
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
