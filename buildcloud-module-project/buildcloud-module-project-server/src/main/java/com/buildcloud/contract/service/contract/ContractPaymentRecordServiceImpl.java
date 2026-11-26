package com.buildcloud.contract.service.contract;

import cn.hutool.core.util.ObjectUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentRecordDO;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import com.buildcloud.contract.dal.mysql.contract.ContractPaymentRecordMapper;
import com.buildcloud.contract.enums.contract.ContractStatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EFFECTIVE;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;
import static com.buildcloud.contract.enums.ErrorCodeConstants.PAYMENT_RECORD_APPROVAL_DUPLICATE;
import static com.buildcloud.contract.enums.ErrorCodeConstants.PAYMENT_RECORD_NOT_EXISTS;

/**
 * 合同实际收付款流水 Service 实现类
 *
 * 业务要点（spec v1.2 §5.1「进度款申请…通过后生成 payment_record 并回写合同累计收付」）：
 *
 * 1. **回写口径**：每笔流水落库后，用一条 `UPDATE bc_contract SET paid_amount = paid_amount + #{delta}`
 *    原子累加合同累计收付（{@link ContractMapper#updatePaidAmount}）。**不做**「先 select 取旧值、
 *    加完再 update」——那样两个并发请求会读到同一个旧值，后提交者覆盖前者的累加结果（典型丢失更新）。
 *    delta 符号：登记 +amount，删除/冲正 -amount，更新走差额 = 新值 - 旧值。
 * 2. **登记前置**：合同必须处于「履行中」(2)，否则抛 CONTRACT_NOT_EFFECTIVE
 *    （草稿/审批中还没生效、已结算/已作废不允许再发生收付）。
 * 3. **审批幂等**：approval_no 非空时同租户内唯一（PAYMENT_RECORD_APPROVAL_DUPLICATE），
 *    对应 BPM 回调「同一审批单只生成一条流水」的约束（ER 图 §3.1）。
 * 4. 回写与流水插入在同一本地事务内（{@code @Transactional}），避免出现"有流水没累加"或反之。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class ContractPaymentRecordServiceImpl implements ContractPaymentRecordService {

    @Resource
    private ContractPaymentRecordMapper paymentRecordMapper;

    @Resource
    private ContractMapper contractMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPaymentRecord(ContractPaymentRecordSaveReqVO createReqVO) {
        // 校验合同存在且履行中
        validateContractEffective(createReqVO.getContractId());
        // 校验审批单号幂等
        validateApprovalNoUnique(null, createReqVO.getApprovalNo());
        // 插入流水
        ContractPaymentRecordDO record = BeanUtils.toBean(createReqVO, ContractPaymentRecordDO.class);
        record.setId(null);
        paymentRecordMapper.insert(record);
        // 原子累加回写合同累计收付（+amount）
        int updated = contractMapper.updatePaidAmount(record.getContractId(), record.getAmount());
        if (updated == 0) {
            // 合同在并发中被删除：抛异常回滚本事务，避免产生孤儿流水
            log.warn("[createPaymentRecord][合同({}) 回写累计收付失败，可能已被删除]", record.getContractId());
            throw exception(CONTRACT_NOT_EXISTS);
        }
        return record.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePaymentRecord(ContractPaymentRecordSaveReqVO updateReqVO) {
        // 校验流水存在
        ContractPaymentRecordDO dbRecord = validatePaymentRecordExists(updateReqVO.getId());
        // 校验合同存在且履行中
        validateContractEffective(updateReqVO.getContractId());
        // 校验审批单号幂等（排除自身）
        validateApprovalNoUnique(updateReqVO.getId(), updateReqVO.getApprovalNo());
        // 更新流水
        ContractPaymentRecordDO updateObj = BeanUtils.toBean(updateReqVO, ContractPaymentRecordDO.class);
        paymentRecordMapper.updateById(updateObj);
        // 回写合同累计收付：
        // ① 合同未变：按差额 delta = 新金额 - 旧金额 原子累加；
        // ② 合同变了（极少见的改挂）：先从旧合同冲减，再累加到新合同。
        if (ObjectUtil.equal(dbRecord.getContractId(), updateReqVO.getContractId())) {
            BigDecimal delta = nullToZero(updateReqVO.getAmount()).subtract(nullToZero(dbRecord.getAmount()));
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                contractMapper.updatePaidAmount(updateReqVO.getContractId(), delta);
            }
        } else {
            contractMapper.updatePaidAmount(dbRecord.getContractId(), nullToZero(dbRecord.getAmount()).negate());
            contractMapper.updatePaidAmount(updateReqVO.getContractId(), nullToZero(updateReqVO.getAmount()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePaymentRecord(Long id) {
        // 校验流水存在
        ContractPaymentRecordDO dbRecord = validatePaymentRecordExists(id);
        // 删除流水
        paymentRecordMapper.deleteById(id);
        // 原子冲减合同累计收付（-amount），保证 paid_amount 与流水之和始终一致
        contractMapper.updatePaidAmount(dbRecord.getContractId(), nullToZero(dbRecord.getAmount()).negate());
    }

    @Override
    public ContractPaymentRecordDO getPaymentRecord(Long id) {
        return paymentRecordMapper.selectById(id);
    }

    @Override
    public PageResult<ContractPaymentRecordDO> getPaymentRecordPage(ContractPaymentRecordPageReqVO pageReqVO) {
        return paymentRecordMapper.selectPage(pageReqVO);
    }

    @Override
    public List<ContractPaymentRecordDO> getPaymentRecordListByContractId(Long contractId) {
        return paymentRecordMapper.selectListByContractId(contractId);
    }

    // ========== 校验方法 ==========

    private ContractPaymentRecordDO validatePaymentRecordExists(Long id) {
        if (id == null) {
            throw exception(PAYMENT_RECORD_NOT_EXISTS);
        }
        ContractPaymentRecordDO record = paymentRecordMapper.selectById(id);
        if (record == null) {
            throw exception(PAYMENT_RECORD_NOT_EXISTS);
        }
        return record;
    }

    /**
     * 校验合同存在且处于「履行中」状态（只有履行中的合同才能发生实际收付）
     *
     * @param contractId 合同编号
     * @return 合同
     */
    private ContractDO validateContractEffective(Long contractId) {
        ContractDO contract = contractId != null ? contractMapper.selectById(contractId) : null;
        if (contract == null) {
            throw exception(CONTRACT_NOT_EXISTS);
        }
        if (!ContractStatusEnum.canPay(contract.getStatus())) {
            log.warn("[validateContractEffective][合同({}) 状态为 {}，不在履行中，不能登记收付款]",
                    contractId, contract.getStatus());
            throw exception(CONTRACT_NOT_EFFECTIVE);
        }
        return contract;
    }

    /**
     * 校验审批单号幂等（同一审批单只生成一条流水）
     *
     * approval_no 可空（手工补录无审批流），空值不做校验。
     *
     * @param id         流水编号（更新时排除自身；新增传 null）
     * @param approvalNo 审批单号
     */
    private void validateApprovalNoUnique(Long id, String approvalNo) {
        ContractPaymentRecordDO record = paymentRecordMapper.selectByApprovalNo(approvalNo);
        if (record == null) {
            return;
        }
        if (ObjectUtil.notEqual(record.getId(), id)) {
            throw exception(PAYMENT_RECORD_APPROVAL_DUPLICATE);
        }
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

}
