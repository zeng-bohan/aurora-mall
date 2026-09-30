package com.zengbohan.aurora.product.cache;

/**
 * 布隆过滤器的可替换持有者：重播种任务整体构建新过滤器后原子换入，
 * 读路径永远拿完整的当前实例（volatile 发布保证），不存在半新半旧。
 */
public class VolatileBloomFilterHolder {

    private volatile StringBloomFilter current;

    public VolatileBloomFilterHolder(StringBloomFilter initial) {
        this.current = initial;
    }

    public StringBloomFilter get() {
        return current;
    }

    /** 重播种完成后的原子整体替换。 */
    public void replace(StringBloomFilter next) {
        this.current = next;
    }
}
