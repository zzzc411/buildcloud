package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentRecordDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 合同实际收付款流水 Service 接口
 *
 * @author buildcloud
 */
public interface ContractPaymentRecordService {

    /**
     * 登记实际收付款（校验合同履行中 + 审批单幂等，回写合同累计收付）
     *
     * @param createReqVO 创建信息
     * @return 流水编号
     */
    Long createPaymentRecord(@Valid ContractPaymentRecordSaveReqVO createReqVO);

    /**
     * 更新实际收付款（按差额调整合同累计收付）
     *
     * @param updateReqVO 更新信息
     */
    void updatePaymentRecord(@Valid ContractPaymentRecordSaveReqVO updateReqVO);

    /**
     * 删除实际收付款（冲减合同累计收付）
     *
     * @param id 流水编号
     */
    void deletePaymentRecord(Long id);

    /**
     * 获得实际收付款流水
     *
     * @param id 流水编号
     * @return 流水
     */
    ContractPaymentRecordDO getPaymentRecord(Long id);

    /**
     * 获得实际收付款流水分页
     *
     * @param pageReqVO 分页查询
     * @return 流水分页
     */
    PageResult<ContractPaymentRecordDO> getPaymentRecordPage(ContractPaymentRecordPageReqVO pageReqVO);

    /**
     * 按合同查询全部流水（按收付日期升序，合同详情/对账页用）
     *
     * @param contractId 合同编号
     * @return 流水列表
     */
    List<ContractPaymentRecordDO> getPaymentRecordListByContractId(Long contractId);

}
