package com.buildcloud.material.dal.mysql.supplier;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.controller.admin.supplier.vo.SupplierPageReqVO;
import com.buildcloud.material.dal.dataobject.supplier.SupplierDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 供应商 Mapper
 *
 * 继承基座 {@link BaseMapperX}；条件用 {@link LambdaQueryWrapperX} 的 xxxIfPresent 系列。
 *
 * @author buildcloud
 */
@Mapper
public interface SupplierMapper extends BaseMapperX<SupplierDO> {

    /**
     * 供应商分页查询
     *
     * 排序：先按 id 降序（新建的排在前面）。
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<SupplierDO> selectPage(SupplierPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<SupplierDO>()
                .likeIfPresent(SupplierDO::getSupplierNo, reqVO.getSupplierNo())
                .likeIfPresent(SupplierDO::getName, reqVO.getName())
                .likeIfPresent(SupplierDO::getContactPerson, reqVO.getContactPerson())
                .likeIfPresent(SupplierDO::getContactPhone, reqVO.getContactPhone())
                .eqIfPresent(SupplierDO::getStatus, reqVO.getStatus())
                .orderByDesc(SupplierDO::getId));
    }

    /**
     * 按供应商编码查询（唯一键 uk_supplier_no 的业务侧校验入口）
     *
     * @param supplierNo 供应商编码
     * @return 供应商
     */
    default SupplierDO selectBySupplierNo(String supplierNo) {
        return selectOne(SupplierDO::getSupplierNo, supplierNo);
    }

    /**
     * 统计某供应商编码被**已逻辑删除**的行占用的数量（真库唯一索引冲突的前置探测）
     *
     * 与目录/物料同源问题（详见 MaterialCategoryMapper#selectDeletedCountByCode）：
     * 真库 uk_supplier_no(tenant_id, supplier_no) 不感知 deleted，逻辑删除行仍占编码，
     * 「删掉 SUP-001 再新建 SUP-001」会绕过 Service 校验撞索引，前端只看到 500。
     *
     * 原生 @Select → 逻辑删除插件不生效（要看见已删行），多租户插件仍自动追加 tenant_id。
     *
     * @param supplierNo 供应商编码
     * @return 已删除行数量
     */
    @Select("SELECT COUNT(1) FROM bc_supplier WHERE supplier_no = #{supplierNo} AND deleted = 1")
    Long selectDeletedCountBySupplierNo(@Param("supplierNo") String supplierNo);

}
