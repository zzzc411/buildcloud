package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangePageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangeSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractChangeDO;
import com.buildcloud.contract.dal.mysql.contract.ContractChangeMapper;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_CHANGE_AMOUNT_ZERO;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_CHANGE_NOT_EXISTS;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;

/**
 * 合同变更签证 Service 实现类
 *
 * 业务要点（spec v1.2 §5.1「变更登记」）：
 * 1. change_amount 为**带符号金额**：正数=增项、负数=减项（ER 图 §3.2 设计取舍），
 *    故只拒绝 0 值（CONTRACT_CHANGE_AMOUNT_ZERO），**不**按正负分两个字段；
 * 2. 变更不改写 bc_contract.amount —— 合同原金额是签约事实，变更后金额由
 *    「amount + SUM(change_amount)」在对账接口现算，避免原值被覆盖不可追溯。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class ContractChangeServiceImpl implements ContractChangeService {

    @Resource
    private ContractChangeMapper contractChangeMapper;

    @Resource
    private ContractMapper contractMapper;

    @Override
    public Long createContractChange(ContractChangeSaveReqVO createReqVO) {
        // 校验合同存在
        validateContractExists(createReqVO.getContractId());
        // 校验变更金额非 0（可为负）
        validateChangeAmount(createReqVO.getChangeAmount());
        // 插入
        ContractChangeDO change = BeanUtils.toBean(createReqVO, ContractChangeDO.class);
        change.setId(null);
        contractChangeMapper.insert(change);
        return change.getId();
    }

    @Override
    public void updateContractChange(ContractChangeSaveReqVO updateReqVO) {
        // 校验存在
        validateContractChangeExists(updateReqVO.getId());
        // 校验合同存在
        validateContractExists(updateReqVO.getContractId());
        // 校验变更金额非 0
        validateChangeAmount(updateReqVO.getChangeAmount());
        // 更新
        ContractChangeDO updateObj = BeanUtils.toBean(updateReqVO, ContractChangeDO.class);
        contractChangeMapper.updateById(updateObj);
    }

    @Override
    public void deleteContractChange(Long id) {
        // 校验存在
        validateContractChangeExists(id);
        // 删除
        contractChangeMapper.deleteById(id);
    }

    @Override
    public ContractChangeDO getContractChange(Long id) {
        return contractChangeMapper.selectById(id);
    }

    @Override
    public PageResult<ContractChangeDO> getContractChangePage(ContractChangePageReqVO pageReqVO) {
        return contractChangeMapper.selectPage(pageReqVO);
    }

    @Override
    public List<ContractChangeDO> getContractChangeListByContractId(Long contractId) {
        return contractChangeMapper.selectListByContractId(contractId);
    }

    // ========== 校验方法 ==========

    private ContractChangeDO validateContractChangeExists(Long id) {
        if (id == null) {
            throw exception(CONTRACT_CHANGE_NOT_EXISTS);
        }
        ContractChangeDO change = contractChangeMapper.selectById(id);
        if (change == null) {
            throw exception(CONTRACT_CHANGE_NOT_EXISTS);
        }
        return change;
    }

    private void validateContractExists(Long contractId) {
        if (contractId == null || contractMapper.selectById(contractId) == null) {
            throw exception(CONTRACT_NOT_EXISTS);
        }
    }

    /**
     * 校验变更金额：必须非空且不等于 0（正数=增项，负数=减项）
     *
     * @param changeAmount 变更金额
     */
    private void validateChangeAmount(BigDecimal changeAmount) {
        if (changeAmount == null || changeAmount.compareTo(BigDecimal.ZERO) == 0) {
            throw exception(CONTRACT_CHANGE_AMOUNT_ZERO);
        }
    }

}
