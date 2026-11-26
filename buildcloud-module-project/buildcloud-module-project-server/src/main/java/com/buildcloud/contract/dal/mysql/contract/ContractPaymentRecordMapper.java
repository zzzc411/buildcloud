package com.buildcloud.contract.dal.mysql.contract;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordPageReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentRecordDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合同实际收付款流水 Mapper
 *
 * @author buildcloud
 */
@Mapper
public interface ContractPaymentRecordMapper extends BaseMapperX<ContractPaymentRecordDO> {

    /**
     * 流水分页查询（按合同 / 类型 / 收付日期筛选）
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<ContractPaymentRecordDO> selectPage(ContractPaymentRecordPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ContractPaymentRecordDO>()
                .eqIfPresent(ContractPaymentRecordDO::getContractId, reqVO.getContractId())
                .eqIfPresent(ContractPaymentRecordDO::getType, reqVO.getType())
                .eqIfPresent(ContractPaymentRecordDO::getApprovalNo, reqVO.getApprovalNo())
                .betweenIfPresent(ContractPaymentRecordDO::getPayDate, reqVO.getPayDate())
                .orderByDesc(ContractPaymentRecordDO::getPayDate)
                .orderByDesc(ContractPaymentRecordDO::getId));
    }

    /**
     * 按合同查询全部流水（合同详情页/对账，按收付日期升序）
     *
     * @param contractId 合同编号
     * @return 流水列表
     */
    default List<ContractPaymentRecordDO> selectListByContractId(Long contractId) {
        return selectList(new LambdaQueryWrapperX<ContractPaymentRecordDO>()
                .eq(ContractPaymentRecordDO::getContractId, contractId)
                .orderByAsc(ContractPaymentRecordDO::getPayDate));
    }

    /**
     * 按审批单号查询流水（审批回调幂等：同一审批单只应产生一条流水）
     *
     * approval_no 可空（手工补录），空值不查。
     *
     * @param approvalNo 审批单号
     * @return 流水
     */
    default ContractPaymentRecordDO selectByApprovalNo(String approvalNo) {
        if (approvalNo == null || approvalNo.isEmpty()) {
            return null;
        }
        return selectOne(ContractPaymentRecordDO::getApprovalNo, approvalNo);
    }

    /**
     * 统计某合同的收付金额合计（按类型分组时传 type，全口径传 null）
     *
     * 用 MyBatis `&lt;script&gt;` 动态拼接，避免 `#{type} IS NULL` 在 H2 下的未定型参数问题。
     *
     * @param contractId 合同编号
     * @param type       类型：1-收款 2-付款；null 表示不分组
     * @return 金额合计；无流水时返回 0
     */
    @Select("<script>SELECT COALESCE(SUM(amount), 0) FROM bc_contract_payment_record "
            + "WHERE contract_id = #{contractId} AND deleted = 0 "
            + "<if test='type != null'> AND type = #{type}</if></script>")
    BigDecimal selectAmountSum(@Param("contractId") Long contractId, @Param("type") Integer type);

}
