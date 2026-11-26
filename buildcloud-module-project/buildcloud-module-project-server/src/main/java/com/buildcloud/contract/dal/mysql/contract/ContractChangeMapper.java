package com.buildcloud.contract.dal.mysql.contract;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangePageReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractChangeDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合同变更签证 Mapper
 *
 * @author buildcloud
 */
@Mapper
public interface ContractChangeMapper extends BaseMapperX<ContractChangeDO> {

    /**
     * 变更分页查询（按合同 / 类型筛选）
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<ContractChangeDO> selectPage(ContractChangePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ContractChangeDO>()
                .eqIfPresent(ContractChangeDO::getContractId, reqVO.getContractId())
                .eqIfPresent(ContractChangeDO::getType, reqVO.getType())
                .eqIfPresent(ContractChangeDO::getApprovalNo, reqVO.getApprovalNo())
                .orderByDesc(ContractChangeDO::getId));
    }

    /**
     * 按合同查询全部变更（合同详情页，按 id 升序）
     *
     * @param contractId 合同编号
     * @return 变更列表
     */
    default List<ContractChangeDO> selectListByContractId(Long contractId) {
        return selectList(new LambdaQueryWrapperX<ContractChangeDO>()
                .eq(ContractChangeDO::getContractId, contractId)
                .orderByAsc(ContractChangeDO::getId));
    }

    /**
     * 统计某合同变更净额（带符号 SUM：正数增项、负数减项）
     *
     * @param contractId 合同编号
     * @return 变更净额；无变更时返回 0
     */
    @Select("SELECT COALESCE(SUM(change_amount), 0) FROM bc_contract_change "
            + "WHERE contract_id = #{contractId} AND deleted = 0")
    BigDecimal selectChangeAmountSum(@Param("contractId") Long contractId);

}
