package com.buildcloud.contract.service.contract;

import cn.hutool.core.util.ObjectUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentPlanDO;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import com.buildcloud.contract.dal.mysql.contract.ContractPaymentPlanMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;
import static com.buildcloud.contract.enums.ErrorCodeConstants.PAYMENT_PLAN_AMOUNT_EXCEED;
import static com.buildcloud.contract.enums.ErrorCodeConstants.PAYMENT_PLAN_NOT_EXISTS;
import static com.buildcloud.contract.enums.ErrorCodeConstants.PAYMENT_PLAN_PERIOD_DUPLICATE;

/**
 * 合同收付款计划 Service 实现类
 *
 * 业务要点（spec v1.2 §5.1「计划编制」）：
 * 1. 期次唯一：uk(tenant_id, contract_id, period) 的业务侧前置校验 → PAYMENT_PLAN_PERIOD_DUPLICATE；
 * 2. 计划总额不超过合同金额：SUM(plan_amount) 含本次新增/更新后的值不得大于 contract.amount
 *    → PAYMENT_PLAN_AMOUNT_EXCEED（超编计划会让「计划 vs 实际」对账口径失真）。
 *
 * 说明：跨子域只读 contract 主表（合同是计划存在的父实体），不注入 project 域任何 DAL，
 * 分包边界不破（AGENTS §2 / contract/package-info）。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class ContractPaymentPlanServiceImpl implements ContractPaymentPlanService {

    @Resource
    private ContractPaymentPlanMapper paymentPlanMapper;

    @Resource
    private ContractMapper contractMapper;

    @Override
    public Long createPaymentPlan(ContractPaymentPlanSaveReqVO createReqVO) {
        // 校验合同存在
        ContractDO contract = validateContractExists(createReqVO.getContractId());
        // 校验期次唯一
        validatePeriodUnique(null, createReqVO.getContractId(), createReqVO.getPeriod());
        // 校验计划金额合计不超合同金额（新增：排除自身传 null）
        validateAmountNotExceed(contract, null, createReqVO.getPlanAmount());
        // 插入
        ContractPaymentPlanDO plan = BeanUtils.toBean(createReqVO, ContractPaymentPlanDO.class);
        plan.setId(null);
        paymentPlanMapper.insert(plan);
        return plan.getId();
    }

    @Override
    public void updatePaymentPlan(ContractPaymentPlanSaveReqVO updateReqVO) {
        // 校验存在
        validatePaymentPlanExists(updateReqVO.getId());
        // 校验合同存在
        ContractDO contract = validateContractExists(updateReqVO.getContractId());
        // 校验期次唯一（排除自身）
        validatePeriodUnique(updateReqVO.getId(), updateReqVO.getContractId(), updateReqVO.getPeriod());
        // 校验计划金额合计不超合同金额（更新：排除自身，避免把"自己原来的金额"重复算进去）
        validateAmountNotExceed(contract, updateReqVO.getId(), updateReqVO.getPlanAmount());
        // 更新
        ContractPaymentPlanDO updateObj = BeanUtils.toBean(updateReqVO, ContractPaymentPlanDO.class);
        paymentPlanMapper.updateById(updateObj);
    }

    @Override
    public void deletePaymentPlan(Long id) {
        // 校验存在
        validatePaymentPlanExists(id);
        // 删除
        paymentPlanMapper.deleteById(id);
    }

    @Override
    public ContractPaymentPlanDO getPaymentPlan(Long id) {
        return paymentPlanMapper.selectById(id);
    }

    @Override
    public PageResult<ContractPaymentPlanDO> getPaymentPlanPage(ContractPaymentPlanPageReqVO pageReqVO) {
        return paymentPlanMapper.selectPage(pageReqVO);
    }

    @Override
    public List<ContractPaymentPlanDO> getPaymentPlanListByContractId(Long contractId) {
        return paymentPlanMapper.selectListByContractId(contractId);
    }

    // ========== 校验方法 ==========

    private ContractPaymentPlanDO validatePaymentPlanExists(Long id) {
        if (id == null) {
            throw exception(PAYMENT_PLAN_NOT_EXISTS);
        }
        ContractPaymentPlanDO plan = paymentPlanMapper.selectById(id);
        if (plan == null) {
            throw exception(PAYMENT_PLAN_NOT_EXISTS);
        }
        return plan;
    }

    private ContractDO validateContractExists(Long contractId) {
        ContractDO contract = contractId != null ? contractMapper.selectById(contractId) : null;
        if (contract == null) {
            throw exception(CONTRACT_NOT_EXISTS);
        }
        return contract;
    }

    /**
     * 校验同一合同内期次唯一
     *
     * @param id         计划编号（更新时排除自身；新增传 null）
     * @param contractId 合同编号
     * @param period     期次
     */
    private void validatePeriodUnique(Long id, Long contractId, Integer period) {
        ContractPaymentPlanDO plan = paymentPlanMapper.selectByContractIdAndPeriod(contractId, period);
        if (plan == null) {
            return;
        }
        if (ObjectUtil.notEqual(plan.getId(), id)) {
            throw exception(PAYMENT_PLAN_PERIOD_DUPLICATE);
        }
    }

    /**
     * 校验计划金额合计不超过合同金额
     *
     * 用一条 SUM 聚合（selectPlanAmountSum）而不是把已有计划全查回应用层加总：
     * 期数多时少传一整个列表；且 SUM 在库内完成，精度按 decimal(18,2) 口径。
     *
     * @param contract     合同（取 amount 作为上限）
     * @param excludePlanId 需排除的计划编号（更新时排除自身；新增传 null）
     * @param planAmount   本次新增/修改的计划金额
     */
    private void validateAmountNotExceed(ContractDO contract, Long excludePlanId, BigDecimal planAmount) {
        BigDecimal existSum = paymentPlanMapper.selectPlanAmountSum(contract.getId(), excludePlanId);
        if (existSum == null) {
            existSum = BigDecimal.ZERO;
        }
        BigDecimal newSum = existSum.add(planAmount != null ? planAmount : BigDecimal.ZERO);
        BigDecimal contractAmount = contract.getAmount() != null ? contract.getAmount() : BigDecimal.ZERO;
        if (newSum.compareTo(contractAmount) > 0) {
            log.warn("[validateAmountNotExceed][合同({}) 计划合计 {} 超过合同金额 {}]",
                    contract.getId(), newSum, contractAmount);
            throw exception(PAYMENT_PLAN_AMOUNT_EXCEED);
        }
    }

}
