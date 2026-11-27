package com.buildcloud.material.service.supplier;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.material.controller.admin.supplier.vo.SupplierPageReqVO;
import com.buildcloud.material.controller.admin.supplier.vo.SupplierSaveReqVO;
import com.buildcloud.material.dal.dataobject.supplier.SupplierDO;
import com.buildcloud.material.dal.mysql.supplier.SupplierMapper;
import com.buildcloud.material.service.ServiceExceptionAssert;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static com.buildcloud.material.enums.ErrorCodeConstants.SUPPLIER_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.SUPPLIER_NO_DUPLICATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SupplierServiceImpl} 的单元测试类
 *
 * H2 建表：{@code /sql/material-test-tables.sql}（本子域独立脚本，前置 DROP+CREATE）。
 *
 * 覆盖点：CRUD 基础链路 + **supplier_no 唯一**（新增重复被拒、更新排除自身、更新撞他人被拒）+ 分页。
 *
 * @author buildcloud
 */
@Import(SupplierServiceImpl.class)
@Sql(scripts = "/sql/material-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class SupplierServiceImplTest extends BaseDbUnitTest {

    @Resource
    private SupplierServiceImpl supplierService;

    @Resource
    private SupplierMapper supplierMapper;

    // ========== 基础 CRUD ==========

    @Test
    public void testCreateSupplier_success() {
        // 准备参数
        SupplierSaveReqVO createReqVO = randomPojo(SupplierSaveReqVO.class, o -> {
            o.setId(null);
            o.setSupplierNo("SUP-0001");
            o.setName("杭州钢铁贸易有限公司");
            o.setContactPerson("张三");
            o.setContactPhone("13800138000");
            o.setAddress("浙江省杭州市拱墅区xx路 1 号");
            o.setStatus(0);
        });

        // 调用
        Long id = supplierService.createSupplier(createReqVO);
        // 断言
        assertNotNull(id);
        SupplierDO supplier = supplierMapper.selectById(id);
        assertPojoEquals(createReqVO, supplier, "id");
    }

    @Test
    public void testCreateSupplier_supplierNoDuplicate() {
        // mock 数据：已存在编码 SUP-DUP
        insertSupplier("SUP-DUP", "已有供应商");
        // 准备参数：同一编码（名称不同）
        SupplierSaveReqVO createReqVO = randomPojo(SupplierSaveReqVO.class, o -> {
            o.setId(null);
            o.setSupplierNo("SUP-DUP");
            o.setName("另一个供应商");
            o.setStatus(0);
        });

        // 调用，并断言异常：供应商编码重复被拒
        assertServiceException(() -> supplierService.createSupplier(createReqVO), SUPPLIER_NO_DUPLICATE);
    }

    @Test
    public void testCreateSupplier_supplierNoOccupiedByDeletedRow() {
        // mock 数据：建一条供应商并逻辑删除（模拟真库：deleted=1 的行仍占用 uk_supplier_no）
        SupplierDO deleted = insertSupplier("SUP-DELETED", "已删供应商");
        supplierMapper.deleteById(deleted.getId());

        // 准备参数：用同一编码重建
        SupplierSaveReqVO createReqVO = randomPojo(SupplierSaveReqVO.class, o -> {
            o.setId(null);
            o.setSupplierNo("SUP-DELETED");
            o.setName("重建供应商");
            o.setStatus(0);
        });

        // 断言：给出可读提示，而不是撞唯一索引返回 500
        ServiceExceptionAssert.assertInvalidParam(
                () -> supplierService.createSupplier(createReqVO), "SUP-DELETED");
    }

    @Test
    public void testUpdateSupplier_success() {
        // mock 数据
        SupplierDO dbSupplier = insertSupplier("SUP-UPD", "原名称");
        // 准备参数：编码不变（校验必须排除自身）
        SupplierSaveReqVO updateReqVO = randomPojo(SupplierSaveReqVO.class, o -> {
            o.setId(dbSupplier.getId());
            o.setSupplierNo("SUP-UPD");
            o.setName("新名称");
            o.setContactPerson("李四");
            o.setContactPhone("0571-88888888");
            o.setStatus(1);
        });

        // 调用
        supplierService.updateSupplier(updateReqVO);
        // 校验是否更新正确
        SupplierDO supplier = supplierMapper.selectById(updateReqVO.getId());
        assertPojoEquals(updateReqVO, supplier);
    }

    @Test
    public void testUpdateSupplier_notExists() {
        // 准备参数
        SupplierSaveReqVO updateReqVO = randomPojo(SupplierSaveReqVO.class, o -> {
            o.setId(999999L);
            o.setStatus(0);
        });

        // 调用，并断言异常
        assertServiceException(() -> supplierService.updateSupplier(updateReqVO), SUPPLIER_NOT_EXISTS);
    }

    @Test
    public void testUpdateSupplier_supplierNoDuplicate() {
        // mock 数据：两个供应商
        SupplierDO first = insertSupplier("SUP-A", "供应商A");
        insertSupplier("SUP-B", "供应商B");
        // 准备参数：把 first 的编码改成 B —— 撞他人
        SupplierSaveReqVO updateReqVO = randomPojo(SupplierSaveReqVO.class, o -> {
            o.setId(first.getId());
            o.setSupplierNo("SUP-B");
            o.setName("供应商A");
            o.setStatus(0);
        });

        // 调用，并断言异常
        assertServiceException(() -> supplierService.updateSupplier(updateReqVO), SUPPLIER_NO_DUPLICATE);
    }

    @Test
    public void testDeleteSupplier_success() {
        // mock 数据
        SupplierDO dbSupplier = insertSupplier("SUP-DEL", "待删供应商");

        // 调用
        supplierService.deleteSupplier(dbSupplier.getId());
        // 校验数据不存在了
        assertNull(supplierMapper.selectById(dbSupplier.getId()));
    }

    @Test
    public void testDeleteSupplier_notExists() {
        // 调用，并断言异常
        assertServiceException(() -> supplierService.deleteSupplier(999999L), SUPPLIER_NOT_EXISTS);
    }

    @Test
    public void testGetSupplier_success() {
        // mock 数据
        SupplierDO dbSupplier = insertSupplier("SUP-GET", "杭州钢铁贸易有限公司");

        // 调用
        SupplierDO supplier = supplierService.getSupplier(dbSupplier.getId());
        // 断言
        assertPojoEquals(dbSupplier, supplier);
    }

    // ========== 分页 ==========

    @Test
    public void testGetSupplierPage_byNameLike() {
        // mock 数据
        insertSupplier("SUP-P-01", "杭州钢铁贸易有限公司");
        insertSupplier("SUP-P-02", "宁波水泥厂");
        insertSupplier("SUP-P-03", "杭州木材市场");

        // 准备参数：名称模糊「杭州」
        SupplierPageReqVO reqVO = new SupplierPageReqVO();
        reqVO.setName("杭州");

        // 调用
        PageResult<SupplierDO> pageResult = supplierService.getSupplierPage(reqVO);
        // 断言：命中 2 条
        assertEquals(2, pageResult.getTotal());
    }

    @Test
    public void testGetSupplierPage_byStatus() {
        // mock 数据：一条开启、一条停用
        insertSupplier("SUP-S-01", "开启的供应商", 0);
        insertSupplier("SUP-S-02", "停用的供应商", 1);

        // 准备参数：只看停用
        SupplierPageReqVO reqVO = new SupplierPageReqVO();
        reqVO.setStatus(1);

        // 调用
        PageResult<SupplierDO> pageResult = supplierService.getSupplierPage(reqVO);
        // 断言
        assertEquals(1, pageResult.getTotal());
        assertEquals("SUP-S-02", pageResult.getList().get(0).getSupplierNo());
    }

    @Test
    public void testGetSupplierPage_empty() {
        // 调用：无数据
        PageResult<SupplierDO> pageResult = supplierService.getSupplierPage(new SupplierPageReqVO());
        // 断言：空分页而非 null
        assertNotNull(pageResult);
        assertEquals(0, pageResult.getTotal());
    }

    // ========== 测试数据构造 ==========

    /**
     * 插入一条测试供应商（直接走 mapper，绕过服务层校验）
     */
    private SupplierDO insertSupplier(String supplierNo, String name) {
        return insertSupplier(supplierNo, name, 0);
    }

    /**
     * 插入一条测试供应商（指定状态）
     */
    private SupplierDO insertSupplier(String supplierNo, String name, Integer status) {
        SupplierDO supplier = randomPojo(SupplierDO.class, o -> {
            o.setSupplierNo(supplierNo);
            o.setName(name);
            o.setContactPerson("联系人");
            o.setContactPhone("13800138000");
            o.setStatus(status);
        });
        supplierMapper.insert(supplier);
        return supplier;
    }

}
