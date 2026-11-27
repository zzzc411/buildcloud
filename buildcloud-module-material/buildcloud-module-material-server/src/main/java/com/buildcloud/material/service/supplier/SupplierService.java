package com.buildcloud.material.service.supplier;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.material.controller.admin.supplier.vo.SupplierPageReqVO;
import com.buildcloud.material.controller.admin.supplier.vo.SupplierSaveReqVO;
import com.buildcloud.material.dal.dataobject.supplier.SupplierDO;
import jakarta.validation.Valid;

/**
 * 供应商 Service 接口
 *
 * 业务边界（spec v1.2 §5.2 用例「供应商维护」）：
 * supplier_no 租户内唯一（错误码 SUPPLIER_NO_DUPLICATE）。
 *
 * @author buildcloud
 */
public interface SupplierService {

    /**
     * 创建供应商
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createSupplier(@Valid SupplierSaveReqVO createReqVO);

    /**
     * 更新供应商
     *
     * @param updateReqVO 更新信息
     */
    void updateSupplier(@Valid SupplierSaveReqVO updateReqVO);

    /**
     * 删除供应商
     *
     * @param id 编号
     */
    void deleteSupplier(Long id);

    /**
     * 获得供应商
     *
     * @param id 编号
     * @return 供应商
     */
    SupplierDO getSupplier(Long id);

    /**
     * 获得供应商分页
     *
     * @param pageReqVO 分页查询
     * @return 供应商分页
     */
    PageResult<SupplierDO> getSupplierPage(SupplierPageReqVO pageReqVO);

}
