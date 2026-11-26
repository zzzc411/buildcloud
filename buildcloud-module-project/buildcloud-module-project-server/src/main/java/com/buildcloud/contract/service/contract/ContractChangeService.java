package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangePageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangeSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractChangeDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 合同变更签证 Service 接口
 *
 * @author buildcloud
 */
public interface ContractChangeService {

    /**
     * 登记合同变更（change_amount 可负但不可 0）
     *
     * @param createReqVO 创建信息
     * @return 变更编号
     */
    Long createContractChange(@Valid ContractChangeSaveReqVO createReqVO);

    /**
     * 更新合同变更
     *
     * @param updateReqVO 更新信息
     */
    void updateContractChange(@Valid ContractChangeSaveReqVO updateReqVO);

    /**
     * 删除合同变更
     *
     * @param id 变更编号
     */
    void deleteContractChange(Long id);

    /**
     * 获得合同变更
     *
     * @param id 变更编号
     * @return 变更
     */
    ContractChangeDO getContractChange(Long id);

    /**
     * 获得合同变更分页
     *
     * @param pageReqVO 分页查询
     * @return 变更分页
     */
    PageResult<ContractChangeDO> getContractChangePage(ContractChangePageReqVO pageReqVO);

    /**
     * 按合同查询全部变更（按编号升序，合同详情/对账页用）
     *
     * @param contractId 合同编号
     * @return 变更列表
     */
    List<ContractChangeDO> getContractChangeListByContractId(Long contractId);

}
