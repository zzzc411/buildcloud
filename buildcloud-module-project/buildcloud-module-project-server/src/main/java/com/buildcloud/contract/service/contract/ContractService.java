package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.contract.controller.admin.contract.vo.ContractPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.ContractSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.service.contract.bo.ContractReconcileBO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 合同 Service 接口
 *
 * 边界（AGENTS §2 / spec §5.1）：contract 子域与 project 子域同服务但分包隔离，
 * 两域只通过 projectId 关联；此处**不注入 ProjectMapper**，project 存在性由调用方/后续
 * 跨域校验补齐（错误码 CONTRACT_PROJECT_NOT_EXISTS 已预留）。
 *
 * @author buildcloud
 */
public interface ContractService {

    /**
     * 创建合同（草稿状态，校验 contract_no 租户内唯一）
     *
     * @param createReqVO 创建信息
     * @return 合同编号
     */
    Long createContract(@Valid ContractSaveReqVO createReqVO);

    /**
     * 更新合同（仅草稿可改，CONTRACT_NOT_DRAFT）
     *
     * @param updateReqVO 更新信息
     */
    void updateContract(@Valid ContractSaveReqVO updateReqVO);

    /**
     * 删除合同（仅草稿可删）
     *
     * @param id 合同编号
     */
    void deleteContract(Long id);

    /**
     * 批量删除合同（仅草稿可删，含任一非草稿即整体失败）
     *
     * @param ids 合同编号数组
     */
    void deleteContractListByIds(List<Long> ids);

    /**
     * 获得合同
     *
     * @param id 合同编号
     * @return 合同
     */
    ContractDO getContract(Long id);

    /**
     * 获得合同分页（按 project_id / status / direction 等筛选）
     *
     * @param pageReqVO 分页查询
     * @return 合同分页
     */
    PageResult<ContractDO> getContractPage(ContractPageReqVO pageReqVO);

    /**
     * 更新合同状态（状态机：草稿→审批中→履行中→已结算，旁路作废）
     *
     * @param id     合同编号
     * @param status 目标状态
     */
    void updateContractStatus(Long id, Integer status);

    /**
     * 合同台账对账（计划 vs 实际差异，一条聚合 SQL）
     *
     * @param contractId 合同编号
     * @return 对账结果（含派生差异列）
     */
    ContractReconcileBO getReconcile(Long contractId);

}
