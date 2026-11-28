package com.buildcloud.material.service.stock.redis;

/**
 * 库存缓存 Key 的集中定义（spec v1.2 §6.2 缓存体系表）
 *
 * ========== 为什么单独一个类（不把 key 拼在 Service 里） ==========
 *
 * 1. **口径唯一**：`pm:stock:{projectId}:{materialId}` 这个格式同时出现在 spec §6.2、
 *    Lua 脚本注释、对账 Job（W5 Phase 4）、压测脚本、运维排查手册里。任何一处写错一个冒号，
 *    结果都是「预扣写进的 key 与对账读的 key 不是同一个」——表现为库存永远对得上（因为都没读到），
 *    是最难发现的一类缺陷。故收敛到一个类，其它地方**只允许引用方法**，禁止字面量拼接；
 * 2. **Lua 脚本与 Java 必须用同一份 key**：脚本的 KEYS[1]/KEYS[2] 由 Java 侧传入，
 *    两边共享本类的构造结果，脚本里不再自行拼 key（脚本内拼 key 就无法享受这里的一致性）。
 *
 * ========== Key 命名与 Redis Cluster 的关系（面试点） ==========
 *
 * 本键设计**不带** `{...}` hash tag：`pm:stock:1:2` 与 `pm:prededuct:1:2` 在 cluster 下
 * 可能落在不同 slot，届时多 key 的 Lua 脚本会被拒绝（CROSSSLOT）。
 * 本项目当前是单实例 Redis（docker `bc-redis`，spec §6.2 未规划 cluster），故按 spec
 * 原文口径执行。**若将来上 cluster**：给 projectId+materialId 整体加 hash tag
 * （`pm:stock:{1:2}` / `pm:prededuct:{1:2}`），这两个 key 即同 slot；改的是本类一处，
 * 而不是散落各处的字符串。这条约束写在这里，是为了避免下一个人在别的类里再拼一份 key。
 *
 * @author buildcloud
 */
public final class StockCacheKeys {

    /**
     * 可用库存计数（string，值 = 可用量）
     */
    private static final String STOCK_PREFIX = "pm:stock:";

    /**
     * 预扣明细（hash，field = reqNo，value = 预扣数量）
     *
     * 与 `pm:stock:` 共用 `{projectId}:{materialId}` 段，便于运维按同一维度排查
     * （`SCAN pm:*:1:2` 一次拿全某项目某物料的两个 key）。
     */
    private static final String PREDEDUCT_PREFIX = "pm:prededuct:";

    /**
     * 两段之间的分隔符（与 spec §6.2 原文一致：冒号分隔）
     */
    private static final String SEPARATOR = ":";

    private StockCacheKeys() {
    }

    /**
     * 可用库存 key：`pm:stock:{projectId}:{materialId}`
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return Redis key
     */
    public static String availableStock(Long projectId, Long materialId) {
        return STOCK_PREFIX + projectId + SEPARATOR + materialId;
    }

    /**
     * 预扣明细 hash key：`pm:prededuct:{projectId}:{materialId}`
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return Redis key
     */
    public static String prededuct(Long projectId, Long materialId) {
        return PREDEDUCT_PREFIX + projectId + SEPARATOR + materialId;
    }

}
