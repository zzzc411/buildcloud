package com.buildcloud.material.service.supplier;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.material.controller.admin.supplier.vo.SupplierPageReqVO;
import com.buildcloud.material.controller.admin.supplier.vo.SupplierSaveReqVO;
import com.buildcloud.material.dal.dataobject.supplier.SupplierDO;
import com.buildcloud.material.dal.mysql.supplier.SupplierMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.material.enums.ErrorCodeConstants.SUPPLIER_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.SUPPLIER_NO_DUPLICATE;

/**
 * 供应商 Service 实现类
 *
 * 精修要点（相对生成器产物）：补 supplier_no 唯一校验（对应真库 uk_supplier_no），
 * 更新时排除自身，避免"编码不变"被自己判为重复。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class SupplierServiceImpl implements SupplierService {

    @Resource
    private SupplierMapper supplierMapper;

    @Override
    public Long createSupplier(SupplierSaveReqVO createReqVO) {
        // 校验供应商编码唯一（新增不排除自身）
        validateSupplierNoUnique(null, createReqVO.getSupplierNo());
        // 插入
        SupplierDO supplier = BeanUtils.toBean(createReqVO, SupplierDO.class);
        supplierMapper.insert(supplier);
        // 返回
        return supplier.getId();
    }

    @Override
    public void updateSupplier(SupplierSaveReqVO updateReqVO) {
        // 校验存在
        validateSupplierExists(updateReqVO.getId());
        // 校验供应商编码唯一（排除自身）
        validateSupplierNoUnique(updateReqVO.getId(), updateReqVO.getSupplierNo());
        // 更新
        SupplierDO updateObj = BeanUtils.toBean(updateReqVO, SupplierDO.class);
        supplierMapper.updateById(updateObj);
    }

    @Override
    public void deleteSupplier(Long id) {
        // 校验存在
        validateSupplierExists(id);
        // 删除
        supplierMapper.deleteById(id);
    }

    @Override
    public SupplierDO getSupplier(Long id) {
        return supplierMapper.selectById(id);
    }

    @Override
    public PageResult<SupplierDO> getSupplierPage(SupplierPageReqVO pageReqVO) {
        return supplierMapper.selectPage(pageReqVO);
    }

    // ========== 校验方法 ==========

    private void validateSupplierExists(Long id) {
        if (supplierMapper.selectById(id) == null) {
            throw exception(SUPPLIER_NOT_EXISTS);
        }
    }

    /**
     * 校验供应商编码在**未删除行**中唯一（ADR-004 A 类口径）
     *
     * 唯一性只约束活跃行（供方退场后同编码再入库是常态）；
     * 数据库侧的 uk_supplier_no 已由 `09-unique-index-realign.sql` 降级为普通索引
     * （`idx_supplier_no`），不再承担唯一性，故这里**不做**「探测已删行」的拦截。
     * 详见 {@code MaterialCategoryServiceImpl#validateCodeUnique} 的历史提醒（L3-7）。
     *
     * @param id         供应商编号（更新时排除自身；新增传 null）
     * @param supplierNo 供应商编码
     */
    private void validateSupplierNoUnique(Long id, String supplierNo) {
        SupplierDO existing = supplierMapper.selectBySupplierNo(supplierNo);
        if (existing != null && !Objects.equals(existing.getId(), id)) {
            throw exception(SUPPLIER_NO_DUPLICATE);
        }
    }

}
