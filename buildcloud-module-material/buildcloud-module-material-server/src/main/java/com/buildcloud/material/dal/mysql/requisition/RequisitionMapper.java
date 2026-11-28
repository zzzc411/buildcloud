package com.buildcloud.material.dal.mysql.requisition;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionPageReqVO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 材料申领单 Mapper
 *
 * 继承基座 {@link BaseMapperX}（AGENTS §4）。
 *
 * 两类 SQL，刻意分开：
 * 1. **查询**（selectPage / selectByReqNo）走基座 LambdaQueryWrapperX，条件拼接口径统一；
 * 2. **状态推进**（{@link #updateStatusCas}）走原生 @Update —— 需要「WHERE status = 期望值」
 *    这条 CAS 语义，wrapper 写法反而更长更易读错，且必须自己补 `deleted = 0`。
 *
 * 注意：V1 申领链路的**热点锁不在本表**，而在 bc_material_stock
 * （`MaterialStockMapper#selectByProjectIdAndMaterialIdForUpdate`）。
 * 本表的 CAS 只解决「同一单据被并发提交两次」，不承担并发扣减的正确性。
 *
 * @author buildcloud
 */
@Mapper
public interface RequisitionMapper extends BaseMapperX<RequisitionDO> {

    /**
     * 申领单分页查询
     *
     * 检索维度对齐 DDL 索引（07-material.sql 第 5 节）：
     * - projectId → idx_project_id（项目详情/数据权限过滤后的主列表）；
     * - status → idx_status（待办、超时扫描）；
     * - reqNo → uk_req_no（精确查单号）。
     * 排序用 id 降序：新建的排在前面，符合「单据列表按录入时间倒序」的使用习惯；
     * 不用 create_time 排序是因为同毫秒批量创建时顺序不稳（同 StockLogMapper 的处理思路）。
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<RequisitionDO> selectPage(RequisitionPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<RequisitionDO>()
                .likeIfPresent(RequisitionDO::getReqNo, reqVO.getReqNo())
                .eqIfPresent(RequisitionDO::getProjectId, reqVO.getProjectId())
                .eqIfPresent(RequisitionDO::getApplicantUserId, reqVO.getApplicantUserId())
                .eqIfPresent(RequisitionDO::getStatus, reqVO.getStatus())
                .eqIfPresent(RequisitionDO::getApprovalStatus, reqVO.getApprovalStatus())
                .betweenIfPresent(RequisitionDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(RequisitionDO::getId));
    }

    /**
     * 按申领单号查询（uk_req_no 的业务侧入口）
     *
     * @param reqNo 申领单号
     * @return 申领单；不存在返回 null
     */
    default RequisitionDO selectByReqNo(String reqNo) {
        return selectOne(RequisitionDO::getReqNo, reqNo);
    }

    /**
     * **状态条件更新**：把单据从 {@code fromStatus} 推进到 {@code toStatus}（乐观并发控制）
     *
     * <pre>
     *   UPDATE bc_requisition
     *      SET status = #{toStatus}, approval_status = #{approvalStatus},
     *          timeout_time = #{timeoutTime}, update_time = NOW()
     *    WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0
     * </pre>
     *
     * ========== 为什么用「WHERE status = 期望值」而不是先 select 再 updateById ==========
     *
     * 申领单的 submit/cancel 都是低频但**必须防重复**的动作：
     * 用户双击提交、网关重试、压测脚本重投，都可能让同一个单据被处理两次。
     * - 先 select 再 `updateById` 存在窗口：两个线程都读到 status=0，都认为可以提交，
     *   于是库存被扣两次（V1 的库存行锁只保证「每次扣减原子」，拦不住「同一单据扣两次」）；
     * - `WHERE status = 期望值` 把「判断 + 写入」合成一条语句，由数据库的行锁保证互斥：
     *   第二个线程影响行数 0，Service 立刻抛 `REQUISITION_STATUS_INVALID`，整个事务回滚。
     *
     * 同时它还天然获得 InnoDB 的行锁：本语句执行期间该单据行被锁住，同单据的一次提交
     * 会阻塞到前者提交/回滚，因此「先锁单据、再锁库存行」的加锁顺序在所有路径上一致，
     * 不会与取消操作形成 AB-BA 死锁。
     *
     * `deleted = 0` 必须自己写（注解 SQL 不走 MyBatis-Plus 逻辑删除拼接）；
     * `tenant_id` 由多租户插件自动追加，此处不写。
     *
     * `timeoutTime` 传 null 时用 `COALESCE` 保留原值（Java 侧不用再补一次 update）：
     * 参数显式声明 `jdbcType=TIMESTAMP`——MyBatis 对 **null 且未声明 jdbcType** 的参数会报
     * `JdbcType OTHER` 而无法绑定（MySQL 驱动下偶发，H2 下必现），这是注解 SQL 的常见坑。
     *
     * @param id             申领单编号
     * @param fromStatus     期望的当前状态（CAS 期望值）
     * @param toStatus       目标状态
     * @param approvalStatus 同时写入的审批状态（V1 恒为 -1 未开始；P5 由审批回调推进）
     * @param timeoutTime    超时关闭时间（提交时写入；传 null 表示保持不变）
     * @return 影响行数（1 成功；0 表示状态已被别人改过或单据不存在/已删除）
     */
    @Update("UPDATE bc_requisition SET status = #{toStatus}, approval_status = #{approvalStatus}, "
            + "timeout_time = COALESCE(#{timeoutTime,jdbcType=TIMESTAMP}, timeout_time), update_time = NOW() "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int updateStatusCas(@Param("id") Long id,
                         @Param("fromStatus") Integer fromStatus,
                         @Param("toStatus") Integer toStatus,
                         @Param("approvalStatus") Integer approvalStatus,
                         @Param("timeoutTime") LocalDateTime timeoutTime);

    // ========== 审批流（W4-P5） ==========

    /**
     * **审批发起 CAS**：草稿(0) → 已提交(1)，同时写审批中 / 审批人列表 / 超时时间
     *
     * <pre>
     *   UPDATE bc_requisition
     *      SET status = 1, approval_status = 1, approvers = #{approvers},
     *          timeout_time = #{timeoutTime}, update_time = NOW()
     *    WHERE id = #{id} AND status = 0 AND deleted = 0
     * </pre>
     *
     * ========== 为什么审批路径不复用 {@link #updateStatusCas} ==========
     *
     * 1. 审批路径还要写 {@code approvers}（会签审批人列表），而 updateStatusCas 的语义是
     *    「纯状态推进」，把审批人塞进去会让 V1 的同步链路（不传审批人）也得传一堆 null；
     * 2. 审批路径的**前态是固定的草稿(0)**，把 `status = 0` 直接写进 WHERE 里，
     *    等于把「只有草稿可提交审批」这条业务规则下沉成一条不可能绕过的一致性条件——
     *    即使将来有人在 Service 外面调它，也绝不可能把已扣减的单拉回审批中（那会重复扣库存）。
     *
     * `timeout_time` 与 V1 的 submit 同口径（+30 分钟，W5 延迟消息据此关单），
     * 但**语义更强**：审批路径里它是「超时未审完则回滚预扣」的截止时间（spec §5.2 的 8-已关闭 边）。
     * 参数显式声明 jdbcType 的理由同 {@link #updateStatusCas}（lessons-learned L1-1g）。
     *
     * @param id        申领单编号
     * @param approvers 会签审批人用户编号（逗号分隔，如 "1,100"）
     * @param timeoutTime 超时关闭时间
     * @return 影响行数（1 成功；0 表示单据不是草稿 / 不存在 / 已删除）
     */
    @Update("UPDATE bc_requisition SET status = 1, approval_status = 1, approvers = #{approvers}, "
            + "timeout_time = #{timeoutTime,jdbcType=TIMESTAMP}, update_time = NOW() "
            + "WHERE id = #{id} AND status = 0 AND deleted = 0")
    int startApprovalCas(@Param("id") Long id,
                         @Param("approvers") String approvers,
                         @Param("timeoutTime") LocalDateTime timeoutTime);

    /**
     * 回写流程实例编号（只在尚未回写时生效，防并发二次覆盖）
     *
     * 条件 {@code process_instance_id IS NULL} 的含义：审批发起后回写实例号的瞬间，
     * 如果 bpm 回调已经先到并把单据推进到终态，本语句仍应把实例号写进去（审计要能反查流程），
     * 但**不允许**覆盖一个已有实例号——那是「同一单据被发起两次流程」的痕迹，
     * 覆盖掉就等于把「双实例」这个排障线索抹掉了。
     *
     * @param id                申领单编号
     * @param processInstanceId 流程实例编号
     * @return 影响行数（0 表示已有实例号或单据不存在）
     */
    @Update("UPDATE bc_requisition SET process_instance_id = #{processInstanceId}, update_time = NOW() "
            + "WHERE id = #{id} AND process_instance_id IS NULL AND deleted = 0")
    int updateProcessInstanceIdIfAbsent(@Param("id") Long id,
                                        @Param("processInstanceId") String processInstanceId);

    /**
     * **审批结果 CAS**：按「当前审批状态 = 审批中」把单据推进到终态
     *
     * <pre>
     *   UPDATE bc_requisition
     *      SET status = #{toStatus}, approval_status = #{toApprovalStatus}, update_time = NOW()
     *    WHERE id = #{id} AND approval_status = 1 AND deleted = 0
     * </pre>
     *
     * ========== 这是审批回调幂等的**主闸门**（比 Service 里的内存判断更可靠） ==========
     *
     * 审批回调可能是重复的（bpm 重投、人工补投、进程内事件与 HTTP 回调同时命中）。
     * 若只用「先 select 看 approval_status 是否已是终态」来判重，两个并发回调会**同时**读到
     * 「还在审批中」，于是双双执行「回补库存 + 写冲正流水」→ 库存翻倍。
     * `WHERE approval_status = 1` 把「判断 + 写入」合成一条语句，第二个回调影响行数 0，
     * 调用方据此幂等返回、**不执行任何库存动作**（与 V1 的 updateStatusCas 同一套思路）。
     *
     * toStatus 与 toApprovalStatus 由调用方按语义传：通过 = (2, 2)、驳回 = (9, 3)。
     *
     * @param id               申领单编号
     * @param toStatus         目标业务状态
     * @param toApprovalStatus 目标审批状态
     * @return 影响行数（1 成功；0 表示不是审批中 / 已被别的回调处理过 / 单据不存在）
     */
    @Update("UPDATE bc_requisition SET status = #{toStatus}, approval_status = #{toApprovalStatus}, "
            + "update_time = NOW() WHERE id = #{id} AND approval_status = 1 AND deleted = 0")
    int finishApprovalCas(@Param("id") Long id,
                          @Param("toStatus") Integer toStatus,
                          @Param("toApprovalStatus") Integer toApprovalStatus);

    /**
     * **出库 CAS**：已扣减(2) → 已出库(3)，同时写实际出库时间
     *
     * <pre>
     *   UPDATE bc_requisition SET status = 3, actual_out_time = #{actualOutTime}, update_time = NOW()
     *    WHERE id = #{id} AND status = 2 AND deleted = 0
     * </pre>
     *
     * 出库**不动库存**（V1 在提交审批时就已扣减），因此这里没有回补/扣减逻辑，
     * 只需要保证「一次且仅一次」：CAS 拦在 2→3 这条边上，重复出库影响行数 0
     * （否则 actual_out_time 会被刷成新时间，「在途料时长」报表失真）。
     *
     * @param id            申领单编号
     * @param actualOutTime 实际出库时间（时间只算一次并复用给内存态，见 L3-10）
     * @return 影响行数（1 成功；0 表示不是已扣减 / 单据不存在）
     */
    @Update("UPDATE bc_requisition SET status = 3, actual_out_time = #{actualOutTime,jdbcType=TIMESTAMP}, "
            + "update_time = NOW() WHERE id = #{id} AND status = 2 AND deleted = 0")
    int outboundCas(@Param("id") Long id, @Param("actualOutTime") LocalDateTime actualOutTime);

    /**
     * 记录最近一次退料时间（不改状态；状态由调用方按「是否全退」决定并另行 CAS）
     *
     * 为什么与状态推进分开：一次退料请求里可能退多行明细，每行都要更新 returned_qty，
     * 而单据状态只有「全退 → 4 / 未全退 → 5」两种结论，必须等所有明细行都更新完才知道。
     * 因此先更新明细 → 再判全退 → 最后一条语句同时改状态与时间（见 {@link #returnCas}）。
     *
     * @param id                申领单编号
     * @param actualReturnTime  本次退料时间
     * @return 影响行数
     */
    @Update("UPDATE bc_requisition SET actual_return_time = #{actualReturnTime,jdbcType=TIMESTAMP}, "
            + "update_time = NOW() WHERE id = #{id} AND deleted = 0")
    int updateReturnTime(@Param("id") Long id, @Param("actualReturnTime") LocalDateTime actualReturnTime);

    /**
     * **退料状态 CAS**：已出库(3)/部分回退(5) → 部分回退(5)/已完成(4)
     *
     * <pre>
     *   UPDATE bc_requisition SET status = #{toStatus}, update_time = NOW()
     *    WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0
     * </pre>
     *
     * WHY 要 fromStatus 参数而不是固定条件：允许的前态有两个（3 和 5），
     * 用「调用方读到的那个值」做 CAS 才是精确的并发检测——
     * 若两个退料请求并发，第一个把 3 改进 5，第二个用 fromStatus=3 就会影响行数 0 →
     * 抛错整体回滚，不会出现「两个请求都各自算了一次可退量、把库存退两次」。
     *
     * @param id         申领单编号
     * @param fromStatus 调用方读到的当前状态（期望值）
     * @param toStatus   目标状态（5 部分回退 / 4 已完成）
     * @return 影响行数（1 成功；0 表示状态已被并发修改）
     */
    @Update("UPDATE bc_requisition SET status = #{toStatus}, update_time = NOW() "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int returnCas(@Param("id") Long id,
                  @Param("fromStatus") Integer fromStatus,
                  @Param("toStatus") Integer toStatus);

}
