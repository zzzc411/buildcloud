package com.buildcloud.material.dal.dataobject.requisition;

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
 * 材料申领单 DO
 *
 * 表 bc_requisition，对应 spec v1.2 §5.2「申领单：req_no 全局唯一/项目/申请人/状态/超时时间」，
 * 建表见 sql/buildcloud/07-material.sql 第 5 节。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）：表含 tenant_id，基座多租户插件只对该基类子类追加条件。
 *
 * ========== 三个业务关键字段（改前必读） ==========
 *
 * 1. {@link #status}：见 {@link com.buildcloud.material.enums.requisition.RequisitionStatusEnum}，
 *    流转校验唯一实现是该枚举的 {@code canTransfer}，禁止在 Controller/前端散落判断。
 *
 * 2. {@link #reqNo}：业务单号，**租户内唯一**（真库 uk_req_no），同时是 W5 MQ 消费的幂等键
 *    （spec §6.1「消费者以 req_no 唯一索引 + 消费状态表防重复投递」）。
 *    按 ADR-004 它属 **A 类（人可重建的单号）**：Service 层查重时需探测「已逻辑删除行仍占单号」的情况，
 *    但本 Phase 的单号由服务端生成（格式 `REQ-yyyyMMdd-序号`，序号段带随机后缀），
 *    人工撞号概率极低，故只在 create 时查一次未删除行；不做已删行探测（见 Service 注释说明）。
 *
 * 3. {@link #lockedQty} 相关：本表**没有** locked 列——V1 的扣减语义是「available_qty 直接减少」，
 *    预扣量记在 bc_material_stock.locked_qty（W5 才写）。V1 的「已提交(1)」是事务内瞬时态，
 *    落库时已是「已扣减(2)」，因此这里只需 status 表达结果，不需要额外的预扣列。
 *
 * @author buildcloud
 */
@TableName("bc_requisition")
@KeySequence("bc_requisition_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequisitionDO extends TenantBaseDO {

    /**
     * 申领单编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 申领单号（业务编码，租户内唯一；W5 MQ 消费幂等键）
     */
    private String reqNo;

    /**
     * 所属项目（bc_project.id，数据权限维度，同时是流水的分片键来源）
     */
    private Long projectId;

    /**
     * 申请人（system_users.id）
     *
     * 取值优先级：VO 显式传入 > 登录态 {@code SecurityFrameworkUtils#getLoginUserId()}。
     * 单测无登录态时可用 VO 传入，生产走登录态，避免前端伪造他人申领。
     */
    private Long applicantUserId;

    /**
     * 申领状态：见 {@link com.buildcloud.material.enums.requisition.RequisitionStatusEnum}
     */
    private Integer status;

    /**
     * 超时关闭时间（提交时 = 当前时间 + 30 分钟，W5 延迟消息据此关单）
     *
     * V1 现在就写入该列，目的不是自己用，而是让 W5 的延迟消息可以直接接管历史数据：
     * 消费到消息时再核对一次 `status == 已提交(1) && timeout_time <= now()` 才关单，
     * 避免消息早到/重投导致误关单（DDL 注释同口径）。
     */
    private LocalDateTime timeoutTime;

    /**
     * 审批状态：-1-未开始 1-审批中 2-通过 3-不通过 4-已取消
     *
     * 数值与基座 `BpmProcessInstanceStatusEnum` 一一对齐（与 bc_project / bc_contract 同口径），
     * 本 Phase 只写 -1（未开始），P5 接入 requisition-approve 后由审批回调推进。
     */
    private Integer approvalStatus;

    /**
     * 审批流程实例编号（ACT_HI_PROCINST.PROC_INST_ID_）
     */
    private String processInstanceId;

    /**
     * 会签审批人用户编号列表（逗号分隔，如 "1,100"；W4-P5 起）
     *
     * 提交审批时暂存（基座回调事件不带流程变量，见 sql/buildcloud/08-requisition-approval.sql
     * 文件头的取舍说明），出结果后**保留**（审计要看「这张单当时由谁会签」）。
     */
    private String approvers;

    /**
     * 实际出库时间（已扣减2 → 已出库3 时写入；W4-P5 起）
     *
     * 出库只是一次性动作且**不再动库存**（V1 在提交审批时就已扣减），
     * 但报表域算「在途料时长 / 领用及时率」需要一个真实的时间基准，
     * 且它与 create_time（建单时间）语义完全不同，不能复用。
     */
    private LocalDateTime actualOutTime;

    /**
     * 最近一次退料时间（退料可多次发生，逐笔明细见 bc_stock_log 的 biz_type=3）
     */
    private LocalDateTime actualReturnTime;

    /**
     * 备注
     */
    private String remark;

}
