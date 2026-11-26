package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentPlanDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 合同收付款计划 Service 接口
 *
 * @author buildcloud
 */
public interface ContractPaymentPlanService {

    /**
     * 创建收付款计划（校验期次唯一 + 计划金额合计不超合同金额）
     *
     * @param createReqVO 创建信息
     * @return 计划编号
     */
    Long createPaymentPlan(@Valid ContractPaymentPlanSaveReqVO createReqVO);

    /**
     * 更新收付款计划
     *
     * @param updateReqVO 更新信息
     */
    void updatePaymentPlan(@Valid ContractPaymentPlanSaveReqVO updateReqVO);

    /**
     * 删除收付款计划
     *
     * @param id 计划编号
     */
    void deletePaymentPlan(Long id);

    /**
     * 获得收付款计划
     *
     * @param id 计划编号
     * @return 计划
     */
    ContractPaymentPlanDO getPaymentPlan(Long id);

    /**
     * 获得收付款计划分页
     *
     * @param pageReqVO 分页查询
     * @return 计划分页
     */
    PageResult<ContractPaymentPlanDO> getPaymentPlanPage(ContractPaymentPlanPageReqVO pageReqVO);

    /**
     * 按合同查询全部计划（按期次升序，合同详情/对账页用）
     *
     * @param contractId 合同编号
     * @return 计划列表
     */
    List<ContractPaymentPlanDO> getPaymentPlanListByContractId(Long contractId);

}
