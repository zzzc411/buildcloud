package com.buildcloud.contract.service.contract;

import cn.hutool.core.util.ObjectUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.contract.controller.admin.contract.vo.ContractPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.ContractSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import com.buildcloud.contract.enums.contract.ContractStatusEnum;
import com.buildcloud.contract.service.contract.bo.ContractReconcileBO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_AMOUNT_INVALID;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_DRAFT;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NO_DUPLICATE;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_STATUS_INVALID;

/**
 * 合同 Service 实现类
 *
 * 业务要点（spec v1.2 §5.1 合同子域用例）：
 * 1. 登记：contract_no 租户内唯一（uk_contract_no 的业务侧校验），新建合同固定为草稿(0)，
 *    金额必须 > 0；
 * 2. 编辑：仅草稿可改（CONTRACT_NOT_DRAFT）——审批中/履行中的合同金额与编号已进入台账与审批链，
 *    改动会造成账实不符，要走变更签证（bc_contract_change）而不是直接改合同；
 * 3. 状态机：草稿(0)→审批中(1)→履行中(2)→已结算(3)，旁路 已作废(9)；
 *    非法流转抛 CONTRACT_STATUS_INVALID，规则唯一实现在 ContractStatusEnum#canTransfer；
 * 4. 对账：一条聚合 SQL 取计划/实际/变更三个口径，派生差异在本地一次算完（见 getReconcile）。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class ContractServiceImpl implements ContractService {

    /**
     * 金额最小值（元）：合同金额必须大于 0（错误码 CONTRACT_AMOUNT_INVALID 的判定阈值）
     */
    private static final BigDecimal MIN_AMOUNT = BigDecimal.ZERO;

    /**
     * 对账派生金额的小数位（decimal(18,2) 口径，避免除法/加法产生 0.000 之类的多余精度）
     */
    private static final int AMOUNT_SCALE = 2;

    @Resource
    private ContractMapper contractMapper;

    @Override
    public Long createContract(ContractSaveReqVO createReqVO) {
        // 校验合同编号唯一
        validateContractNoUnique(null, createReqVO.getContractNo());
        // 校验金额
        validateAmount(createReqVO.getAmount());
        // 插入：新登记的合同一律为草稿（状态只能经 updateContractStatus 走状态机推进）
        ContractDO contract = BeanUtils.toBean(createReqVO, ContractDO.class);
        contract.setId(null);
        contract.setStatus(ContractStatusEnum.DRAFT.getStatus());
        contract.setPaidAmount(BigDecimal.ZERO);
        contractMapper.insert(contract);
        return contract.getId();
    }

    @Override
    public void updateContract(ContractSaveReqVO updateReqVO) {
        // 校验存在
        ContractDO dbContract = validateContractExists(updateReqVO.getId());
        // 校验草稿（审批中/履行中/已结算/已作废 均不允许直接改字段）
        validateContractDraft(dbContract);
        // 校验合同编号唯一（排除自身）
        validateContractNoUnique(updateReqVO.getId(), updateReqVO.getContractNo());
        // 校验金额
        validateAmount(updateReqVO.getAmount());
        // 更新：status / paidAmount 不随 update 变更（BeanUtils 只拷 VO 里有的字段，VO 无这两个字段）
        ContractDO updateObj = BeanUtils.toBean(updateReqVO, ContractDO.class);
        contractMapper.updateById(updateObj);
    }

    @Override
    public void deleteContract(Long id) {
        // 校验存在 + 草稿
        validateContractDraft(validateContractExists(id));
        // 删除（逻辑删除）
        contractMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteContractListByIds(List<Long> ids) {
        // 逐个校验草稿，任何一个非草稿都整体回滚，避免删一半
        ids.forEach(id -> validateContractDraft(validateContractExists(id)));
        contractMapper.deleteByIds(ids);
    }

    @Override
    public ContractDO getContract(Long id) {
        return contractMapper.selectById(id);
    }

    @Override
    public PageResult<ContractDO> getContractPage(ContractPageReqVO pageReqVO) {
        return contractMapper.selectPage(pageReqVO);
    }

    @Override
    public void updateContractStatus(Long id, Integer status) {
        // 校验存在
        ContractDO contract = validateContractExists(id);
        // 校验状态机流转合法性（草稿→审批中→履行中→已结算，旁路作废）
        if (!ContractStatusEnum.canTransfer(contract.getStatus(), status)) {
            log.warn("[updateContractStatus][合同({}) 状态从 {} 流转到 {} 非法]", id, contract.getStatus(), status);
            throw exception(CONTRACT_STATUS_INVALID);
        }
        // 只更新状态列，避免把其它字段回写覆盖（并发编辑时尤其重要）
        ContractDO updateObj = new ContractDO();
        updateObj.setId(id);
        updateObj.setStatus(status);
        contractMapper.updateById(updateObj);
    }

    @Override
    public ContractReconcileBO getReconcile(Long contractId) {
        // 一条聚合 SQL 取回四个口径（合同主表 + 计划/收付/变更 三个子查询 LEFT JOIN）
        ContractReconcileBO reconcile = contractMapper.selectReconcile(contractId);
        if (reconcile == null) {
            throw exception(CONTRACT_NOT_EXISTS);
        }
        // 派生列统一在此计算，保证「同一口径」只有一处实现（前端/报表/Job 复用同一份语义）
        BigDecimal contractAmount = zeroIfNull(reconcile.getContractAmount());
        BigDecimal planTotal = zeroIfNull(reconcile.getPlanAmountTotal());
        BigDecimal receiptTotal = zeroIfNull(reconcile.getReceiptAmountTotal());
        BigDecimal paymentTotal = zeroIfNull(reconcile.getPaymentAmountTotal());
        BigDecimal changeTotal = zeroIfNull(reconcile.getChangeAmountTotal());
        BigDecimal paidAmount = zeroIfNull(reconcile.getPaidAmount());

        BigDecimal actualTotal = receiptTotal.add(paymentTotal);
        reconcile.setActualAmountTotal(scale(actualTotal));
        // 变更后金额 = 合同金额 + 变更净额（change_amount 带符号，故直接相加，无 CASE WHEN）
        reconcile.setChangedContractAmount(scale(contractAmount.add(changeTotal)));
        // 差异 1：计划 vs 实际
        reconcile.setPlanActualDiff(scale(planTotal.subtract(actualTotal)));
        // 差异 2：合同 vs 变更后
        reconcile.setContractChangedDiff(scale(contractAmount.add(changeTotal).subtract(contractAmount)));
        // 双写一致性：冗余 paid_amount 与流水之和的差（正常为 0）
        reconcile.setPaidAmountDiff(scale(paidAmount.subtract(actualTotal)));
        return reconcile;
    }

    // ========== 校验方法 ==========

    private ContractDO validateContractExists(Long id) {
        if (id == null) {
            throw exception(CONTRACT_NOT_EXISTS);
        }
        ContractDO contract = contractMapper.selectById(id);
        if (contract == null) {
            throw exception(CONTRACT_NOT_EXISTS);
        }
        return contract;
    }

    /**
     * 校验合同为草稿状态（只有草稿允许改字段与删除）
     *
     * @param contract 合同
     */
    private void validateContractDraft(ContractDO contract) {
        if (!ContractStatusEnum.DRAFT.match(contract.getStatus())) {
            throw exception(CONTRACT_NOT_DRAFT);
        }
    }

    /**
     * 校验合同编号在同一租户内唯一
     *
     * 租户隔离由基座多租户插件在 SQL 层自动追加 tenant_id，此处无需显式过滤。
     *
     * @param id         合同编号（更新时排除自身；新增传 null）
     * @param contractNo 业务合同编号
     */
    private void validateContractNoUnique(Long id, String contractNo) {
        ContractDO contract = contractMapper.selectByContractNo(contractNo);
        if (contract == null) {
            return;
        }
        if (ObjectUtil.notEqual(contract.getId(), id)) {
            throw exception(CONTRACT_NO_DUPLICATE);
        }
    }

    /**
     * 校验合同金额必须大于 0
     *
     * @param amount 合同金额
     */
    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(MIN_AMOUNT) <= 0) {
            throw exception(CONTRACT_AMOUNT_INVALID);
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

}
