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

}
