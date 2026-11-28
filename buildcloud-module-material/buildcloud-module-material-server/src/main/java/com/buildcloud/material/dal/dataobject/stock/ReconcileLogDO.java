package com.buildcloud.material.dal.dataobject.stock;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 库存对账结果快照 DO
 *
 * 表 bc_reconcile_log，对应 spec v1.2 §6.1 步骤⑦「对账兜底：XXL-Job 定时比对 Redis 预扣 vs DB 流水 →
 * 差异告警（站内信）+ 修复」，建表见 {@code sql/buildcloud/10-reconcile-log.sql}。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）：表含 tenant_id，基座多租户插件只对该基类子类追加条件。
 *
 * ========== 粒度：每轮对账 × 每种差异类型 一行（不是每轮一行） ==========
 *
 * 一轮对账最多写 3 行（D1/D2/D3），因为三类的 {@link #diffCount} / {@link #fixedCount} /
 * {@link #status} 各自独立——「D1 已修完、D3 仍失败」是最常见的中途状态，
 * 合成一行就必须在 detail_json 里嵌套维度，而「今天有没有哪轮差异超阈值」这类告警查询
 * 正好要用这两个数值字段做条件。粒度选择的完整理由见建表脚本文件头。
 *
 * ========== 字段口径 ==========
 *
 * 1. {@link #batchNo}：一轮对账一个批次号（`RCL-yyyyMMddHHmmss-SSS-分片号`）。分片号后缀的作用是
 *    多执行器并行时能分辨"这行是哪个分片写的"——没有它，两个执行器在同一毫秒开跑会产生相同的号，
 *    事后无法归因；
 * 2. {@link #diffCount} / {@link #fixedCount}：**分开存**。只存 diff_count 无法区分
 *    「发现了但修复失败」与「发现了正在修」，而前者才是需要人介入的状态；
 * 3. {@link #status}：{@link com.buildcloud.material.enums.stock.ReconcileStatusEnum}。
 *    它的取值是 diff/fixed 的**结论快照**（便于告警查询一条 SQL 搞定），
 *    不参与修复逻辑判断——修复判断一律看 Redis/DB 的真实状态（幂等性来自"看现状"而非"看日志"）。
 * 4. {@link #detailJson}：差异明细 + 修复前后值。存 JSON 不另开明细表的理由见建表脚本；
 *    超长时截断并在末尾标注（字段是 varchar(4000)，MySQL STRICT 模式下超长是**报错**不是截断，
 *    若不截断，一次大规模差异会把整轮对账的落库打断——日志表写失败不能影响对账本身）。
 *
 * @author buildcloud
 */
@TableName("bc_reconcile_log")
@KeySequence("bc_reconcile_log_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconcileLogDO extends TenantBaseDO {

    /**
     * 对账快照编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 对账批次号（一轮对账一个；同轮的多行共用同一个批次号）
     */
    private String batchNo;

    /**
     * 对账执行时间（批次开始时刻；同轮所有行相同）
     */
    private LocalDateTime checkTime;

    /**
     * 差异类型：1-D1 2-D2 3-D3
     *
     * 枚举 {@link com.buildcloud.material.enums.stock.ReconcileDiffTypeEnum}
     */
    private Integer checkType;

    /**
     * 本轮该类型检出的差异条数（**修复前**的检出数，不是修复后的残留数）
     */
    private Integer diffCount;

    /**
     * 本轮该类型修复成功的条数
     */
    private Integer fixedCount;

    /**
     * 对账结论：0-无差异 1-有差异未修复 2-已全部修复
     *
     * 枚举 {@link com.buildcloud.material.enums.stock.ReconcileStatusEnum}
     */
    private Integer status;

    /**
     * 差异明细 JSON（含每条差异的修复前后值，便于审计与复盘）
     */
    private String detailJson;

}
