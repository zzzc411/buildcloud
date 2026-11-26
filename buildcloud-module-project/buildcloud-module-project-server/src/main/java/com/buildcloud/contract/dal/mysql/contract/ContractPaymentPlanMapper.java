package com.buildcloud.contract.dal.mysql.contract;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanPageReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentPlanDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合同收付款计划 Mapper
 *
 * @author buildcloud
 */
@Mapper
public interface ContractPaymentPlanMapper extends BaseMapperX<ContractPaymentPlanDO> {

    /**
     * 计划分页查询（按合同 / 期次 / 状态筛选）
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<ContractPaymentPlanDO> selectPage(ContractPaymentPlanPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ContractPaymentPlanDO>()
                .eqIfPresent(ContractPaymentPlanDO::getContractId, reqVO.getContractId())
                .eqIfPresent(ContractPaymentPlanDO::getPeriod, reqVO.getPeriod())
                .eqIfPresent(ContractPaymentPlanDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(ContractPaymentPlanDO::getPlanDate, reqVO.getPlanDate())
                .orderByAsc(ContractPaymentPlanDO::getPeriod));
    }

    /**
     * 按合同查询全部计划（按 period 升序，台账/对账展示用）
     *
     * @param contractId 合同编号
     * @return 计划列表
     */
    default List<ContractPaymentPlanDO> selectListByContractId(Long contractId) {
        return selectList(new LambdaQueryWrapperX<ContractPaymentPlanDO>()
                .eq(ContractPaymentPlanDO::getContractId, contractId)
                .orderByAsc(ContractPaymentPlanDO::getPeriod));
    }

    /**
     * 按合同 + 期次查询（唯一键 uk_contract_period 的业务侧校验入口）
     *
     * @param contractId 合同编号
     * @param period     期次
     * @return 计划
     */
    default ContractPaymentPlanDO selectByContractIdAndPeriod(Long contractId, Integer period) {
        return selectOne(ContractPaymentPlanDO::getContractId, contractId,
                ContractPaymentPlanDO::getPeriod, period);
    }

    /**
     * 统计某合同已有计划金额合计（不含指定计划自身，用于更新时排除自身）
     *
     * 用 SUM 聚合而非把列表查回应用层加总：期数多时不产生多余的数据传输。
     * 条件用 MyBatis `&lt;script&gt;` 动态拼接而不是 `#{excludePlanId} IS NULL`——
     * 后者在 H2 下会因参数类型未定（jdbcType OTHER）报错，MySQL 能容忍但没必要为省一个 if 冒风险。
     *
     * @param contractId      合同编号
     * @param excludePlanId   需排除的计划编号（新增传 null）
     * @return 计划金额合计；无计划时返回 0
     */
    @Select("<script>SELECT COALESCE(SUM(plan_amount), 0) FROM bc_contract_payment_plan "
            + "WHERE contract_id = #{contractId} AND deleted = 0 "
            + "<if test='excludePlanId != null'> AND id != #{excludePlanId}</if></script>")
    BigDecimal selectPlanAmountSum(@Param("contractId") Long contractId,
                                   @Param("excludePlanId") Long excludePlanId);

}
