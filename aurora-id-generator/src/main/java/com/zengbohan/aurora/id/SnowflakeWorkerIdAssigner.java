package com.zengbohan.aurora.id;

import java.net.InetAddress;
import java.util.Objects;
import java.lang.System.Logger.Level;

/**
 * 雪花 workerId 分配。
 * <p>
 * workerId 只需在**同时存活**的实例间唯一——两个同 workerId 的活实例会在同一毫秒
 * 发出重复 id。分配顺序：
 * <ol>
 *   <li>**显式指定**（多实例/生产部署必须）：系统属性 {@code aurora.snowflake.worker-id}，
 *       其次环境变量 {@code AURORA_SNOWFLAKE_WORKER_ID}；越界/非数字启动即失败</li>
 *   <li>**缺省推导**：{@code hash(hostname/pid) % 1024}——同机多实例靠 pid 区分、
 *       跨机靠 hostname 区分。哈希**不探测冲突**，两个并发实例可能撞出同一 id，
 *       因此推导模式启动时打 WARN；生产建议显式指定，或改用中心化租约（DB/ZK，
 *       为保持本模块"无 DB 依赖"不内置）</li>
 * </ol>
 */
public final class SnowflakeWorkerIdAssigner {

    public static final String PROPERTY_KEY = "aurora.snowflake.worker-id";
    public static final String ENV_KEY = "AURORA_SNOWFLAKE_WORKER_ID";

    private static final System.Logger log = System.getLogger(SnowflakeWorkerIdAssigner.class.getName());

    private SnowflakeWorkerIdAssigner() {
    }

    /** 解析 workerId（[0,1023]）：显式配置优先，缺省按 hostname/pid 推导。 */
    public static int assign() {
        return assign(System.getProperty(PROPERTY_KEY), System.getenv(ENV_KEY),
                hostName(), ProcessHandle.current().pid());
    }

    // 参数化版本：把输入交给调用方/测试，保证可确定性。
    static int assign(String explicitValue, String envValue, String hostname, long pid) {
        String configured = explicitValue != null ? explicitValue : envValue;
        if (configured != null && !configured.isBlank()) {
            int id = parse(configured.trim());
            log.log(Level.INFO, "snowflake workerId={0} (explicitly configured)", id);
            return id;
        }
        String seed = (hostname == null || hostname.isBlank() ? "unknown" : hostname.trim()) + "/" + pid;
        int derived = Math.floorMod(Objects.hashCode(seed), (int) SnowflakeIdGenerator.MAX_WORKER_ID + 1);
        log.log(Level.WARNING, "snowflake workerId={0} derived from \"{1}\" — concurrent instances must "
                + "set " + PROPERTY_KEY + " explicitly, two live instances sharing a workerId emit duplicate ids",
                derived, seed);
        return derived;
    }

    // 显式值要么是合法的 [0,1023]，要么让启动失败——静默取默认会把配置错误藏起来。
    private static int parse(String value) {
        int id;
        try {
            id = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "snowflake " + PROPERTY_KEY + " must be an integer: " + value, e);
        }
        if (id < 0 || id > SnowflakeIdGenerator.MAX_WORKER_ID) {
            throw new IllegalStateException("snowflake " + PROPERTY_KEY
                    + " must be within [0, " + SnowflakeIdGenerator.MAX_WORKER_ID + "]: " + id);
        }
        return id;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown"; // 离线/受限环境：退化为仅按 pid 区分
        }
    }
}
