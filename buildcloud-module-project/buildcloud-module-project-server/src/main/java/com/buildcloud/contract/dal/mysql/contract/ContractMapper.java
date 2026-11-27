package com.buildcloud.contract.dal.mysql.contract;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.contract.controller.admin.contract.vo.ContractPageReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.service.contract.bo.ContractReconcileBO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 合同 Mapper
 *
 * 继承基座 {@link BaseMapperX}，复用分页/单条查询封装与 {@link LambdaQueryWrapperX} 的
 * xxxIfPresent 条件拼接（AGENTS §4：与基座风格一致，勿改写成手写 XML）。
 *
 * 精修点：
 * 1. {@link #updatePaidAmount} 用**一条 UPDATE 原子累加**回写 paid_amount，不做「读-改-写」，
 *    避免并发登记收付款时丢失更新（spec §5.1 进度款通过后回写累计收付）；
 * 2. {@link #selectReconcile} 用**一条聚合 SQL** 一次性取回计划/实际/变更三个口径的合计，
 *    避免按表逐个查再在应用层拼（N+1），对账接口只打一次库。
 *
 * @author buildcloud
 */
@Mapper
public interface ContractMapper extends BaseMapperX<ContractDO> {

    /**
     * 合同台账分页查询（spec §5.1 用例：合同登记 + 台账查询）
     *
     * 只保留走索引的检索维度（contract_no / project_id / status / direction / sign_date），
     * 名称用模糊匹配；remark 等无索引列不参与筛选。
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<ContractDO> selectPage(ContractPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ContractDO>()
                .eqIfPresent(ContractDO::getContractNo, reqVO.getContractNo())
                .likeIfPresent(ContractDO::getContractName, reqVO.getContractName())
                .eqIfPresent(ContractDO::getProjectId, reqVO.getProjectId())
                .eqIfPresent(ContractDO::getDirection, reqVO.getDirection())
                .eqIfPresent(ContractDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(ContractDO::getSignDate, reqVO.getSignDate())
                .betweenIfPresent(ContractDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(ContractDO::getId));
    }

    /**
     * 按合同编号查询（唯一键 uk_contract_no 的业务侧校验入口）
     *
     * 租户隔离由基座多租户插件在 SQL 层自动追加 tenant_id，此处无需显式过滤。
     *
     * @param contractNo 合同编号
     * @return 合同
     */
    default ContractDO selectByContractNo(String contractNo) {
        return selectOne(ContractDO::getContractNo, contractNo);
    }

    /**
     * 原子累加合同累计已收/已付金额
     *
     * 关键：`paid_amount = paid_amount + #{delta}` 由数据库在一次 UPDATE 内完成读+写，
     * 并发登记多笔收付款时不会互相覆盖（若先 select 再 set，两个事务会读到同一个旧值，
     * 后提交者把前者的累加结果覆盖掉——典型丢失更新）。
     *
     * delta 的符号约定：登记流水传 +amount，删除/冲正流水传 -amount。
     *
     * @param id    合同编号
     * @param delta 增量（可为负）
     * @return 更新行数（0 表示合同不存在或已删除）
     */
    @Update("UPDATE bc_contract SET paid_amount = paid_amount + #{delta}, update_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int updatePaidAmount(@Param("id") Long id, @Param("delta") BigDecimal delta);

    /**
     * 清空在途的进度款申请（amount + period 同时置 NULL）
     *
     * 为什么单独写一条注解 SQL 而不走 {@code updateById}：
     * MyBatis Plus 的 {@code updateById} 默认忽略值为 null 的字段（FieldStrategy.NOT_NULL），
     * 想置空就得加 {@code @TableField(updateStrategy = FieldStrategy.IGNORED)} 之类的注解，
     * 那会影响该 DO 上所有更新路径（包括表单 update，可能把别的列意外清空）。
     * 这里用一条只动这两列的显式 SQL，作用域最小、语义最直白。
     *
     * 调用时机（见 {@code ContractApprovalServiceImpl}）：审批通过（流水已生成）与审批驳回/取消
     * （申请作废）之后各一次，保证「在途金额」不会残留到下一期申请。
     *
     * @param id 合同编号
     * @return 更新行数（0 表示合同不存在或已删除）
     */
    @Update("UPDATE bc_contract SET pending_payment_amount = NULL, pending_period = NULL, "
            + "update_time = NOW() WHERE id = #{id} AND deleted = 0")
    int clearPendingPayment(@Param("id") Long id);

    /**
     * 合同台账对账（spec §5.1「台账对账：计划 vs 实际差异」）
     *
     * 一条 SQL 聚合四个口径，避免 4 次查库 + 应用层拼装：
     * 1. 合同金额 amount 与冗余累计收付 paid_amount（bc_contract 主表）；
     * 2. 计划合计 plan_amount_total（bc_contract_payment_plan 按期汇总）；
     * 3. 实际收付按 type 分收/付合计（bc_contract_payment_record）；
     * 4. 变更净额 change_amount_total（bc_contract_change，带符号 SUM 即可得净增减）。
     *
     * 说明：子查询各自按 contract_id 聚合并用 LEFT JOIN 挂到主表，**不用** 4 张表直接 JOIN
     * （那会产生笛卡尔积把金额放大 n 倍）；`deleted = 0` 显式过滤，因为注解 SQL 不走
     * MyBatis Plus 的逻辑删除自动拼接。差异列（计划-实际、变更后-合同）在 Service 层用
     * 一份口径计算，SQL 只负责取数。
     *
     * @param contractId 合同编号
     * @return 对账聚合结果；合同不存在时返回 null
     */
    @Select("SELECT c.id             AS contract_id, "
            + "       c.contract_no    AS contract_no, "
            + "       c.contract_name  AS contract_name, "
            + "       c.direction      AS direction, "
            + "       c.status         AS status, "
            + "       c.amount         AS contract_amount, "
            + "       c.paid_amount    AS paid_amount, "
            + "       COALESCE(p.plan_amount_total, 0)    AS plan_amount_total, "
            + "       COALESCE(p.plan_count, 0)           AS plan_count, "
            + "       COALESCE(r.receipt_amount_total, 0) AS receipt_amount_total, "
            + "       COALESCE(r.payment_amount_total, 0) AS payment_amount_total, "
            + "       COALESCE(r.record_count, 0)         AS record_count, "
            + "       COALESCE(g.change_amount_total, 0)  AS change_amount_total, "
            + "       COALESCE(g.change_count, 0)         AS change_count "
            + "FROM bc_contract c "
            + "LEFT JOIN (SELECT contract_id, SUM(plan_amount) AS plan_amount_total, COUNT(*) AS plan_count "
            + "           FROM bc_contract_payment_plan WHERE deleted = 0 GROUP BY contract_id) p "
            + "       ON p.contract_id = c.id "
            + "LEFT JOIN (SELECT contract_id, "
            + "                  SUM(CASE WHEN type = 1 THEN amount ELSE 0 END) AS receipt_amount_total, "
            + "                  SUM(CASE WHEN type = 2 THEN amount ELSE 0 END) AS payment_amount_total, "
            + "                  COUNT(*) AS record_count "
            + "           FROM bc_contract_payment_record WHERE deleted = 0 GROUP BY contract_id) r "
            + "       ON r.contract_id = c.id "
            + "LEFT JOIN (SELECT contract_id, SUM(change_amount) AS change_amount_total, COUNT(*) AS change_count "
            + "           FROM bc_contract_change WHERE deleted = 0 GROUP BY contract_id) g "
            + "       ON g.contract_id = c.id "
            + "WHERE c.id = #{contractId} AND c.deleted = 0")
    @Results(id = "contractReconcileResult", value = {
            @Result(column = "contract_id", property = "contractId"),
            @Result(column = "contract_no", property = "contractNo"),
            @Result(column = "contract_name", property = "contractName"),
            @Result(column = "direction", property = "direction"),
            @Result(column = "status", property = "status"),
            @Result(column = "contract_amount", property = "contractAmount"),
            @Result(column = "paid_amount", property = "paidAmount"),
            @Result(column = "plan_amount_total", property = "planAmountTotal"),
            @Result(column = "plan_count", property = "planCount"),
            @Result(column = "receipt_amount_total", property = "receiptAmountTotal"),
            @Result(column = "payment_amount_total", property = "paymentAmountTotal"),
            @Result(column = "record_count", property = "recordCount"),
            @Result(column = "change_amount_total", property = "changeAmountTotal"),
            @Result(column = "change_count", property = "changeCount")
    })
    ContractReconcileBO selectReconcile(@Param("contractId") Long contractId);

}
