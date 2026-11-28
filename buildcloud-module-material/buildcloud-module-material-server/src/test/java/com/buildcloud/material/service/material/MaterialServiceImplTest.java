package com.buildcloud.material.service.material;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.material.controller.admin.material.vo.MaterialPageReqVO;
import com.buildcloud.material.controller.admin.material.vo.MaterialSaveReqVO;
import com.buildcloud.material.dal.dataobject.category.MaterialCategoryDO;
import com.buildcloud.material.dal.dataobject.material.MaterialDO;
import com.buildcloud.material.dal.mysql.category.MaterialCategoryMapper;
import com.buildcloud.material.dal.mysql.material.MaterialMapper;
import com.buildcloud.material.service.ServiceExceptionAssert;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomString;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_CATEGORY_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_NO_DUPLICATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MaterialServiceImpl} 的单元测试类
 *
 * H2 建表：{@code /sql/material-test-tables.sql}（本子域独立脚本，前置 DROP+CREATE）。
 *
 * 覆盖点：
 * 1. CRUD 基础链路（含 categoryId 存在性校验）；
 * 2. **material_no 唯一**（新增重复被拒、更新排除自身、更新撞他人被拒）；
 * 3. 分页多条件：按 categoryId 筛选 + name/materialNo/spec 模糊匹配。
 *
 * @author buildcloud
 */
@Import(MaterialServiceImpl.class)
@Sql(scripts = "/sql/material-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class MaterialServiceImplTest extends BaseDbUnitTest {

    @Resource
    private MaterialServiceImpl materialService;

    @Resource
    private MaterialMapper materialMapper;

    @Resource
    private MaterialCategoryMapper categoryMapper;

    // ========== 基础 CRUD ==========

    @Test
    public void testCreateMaterial_success() {
        // mock 数据：物料挂在存在的目录下
        MaterialCategoryDO category = insertCategory("STEEL", "钢材");
        // 准备参数
        MaterialSaveReqVO createReqVO = randomPojo(MaterialSaveReqVO.class, o -> {
            o.setId(null);
            o.setMaterialNo("MT-HRB400-20");
            o.setName("螺纹钢");
            o.setSpec("HRB400 Φ20");
            o.setUnit("吨");
            o.setCategoryId(category.getId());
            o.setStatus(0);
        });

        // 调用
        Long id = materialService.createMaterial(createReqVO);
        // 断言
        assertNotNull(id);
        MaterialDO material = materialMapper.selectById(id);
        assertPojoEquals(createReqVO, material, "id");
    }

    @Test
    public void testCreateMaterial_noCategory() {
        // 准备参数：categoryId 为空（未归类是合法状态，spec 未要求物料必须归类）
        MaterialSaveReqVO createReqVO = randomPojo(MaterialSaveReqVO.class, o -> {
            o.setId(null);
            o.setMaterialNo("MT-NOCAT-01");
            o.setName("未归类物料");
            o.setUnit("个");
            o.setCategoryId(null);
            o.setStatus(0);
        });

        // 调用：应当允许
        Long id = materialService.createMaterial(createReqVO);
        // 断言
        assertNotNull(id);
        assertNull(materialMapper.selectById(id).getCategoryId());
    }

    @Test
    public void testCreateMaterial_categoryNotExists() {
        // 准备参数：目录编号不存在
        MaterialSaveReqVO createReqVO = randomPojo(MaterialSaveReqVO.class, o -> {
            o.setId(null);
            o.setMaterialNo("MT-ORPHAN-01");
            o.setUnit("个");
            o.setCategoryId(999999L);
            o.setStatus(0);
        });

        // 调用，并断言异常
        assertServiceException(() -> materialService.createMaterial(createReqVO),
                MATERIAL_CATEGORY_NOT_EXISTS);
    }

    @Test
    public void testCreateMaterial_materialNoDuplicate() {
        // mock 数据：已存在编码 MT-DUP-01
        insertMaterial("MT-DUP-01", "螺纹钢", "HRB400 Φ20", null);
        // 准备参数：同一编码（名称/规格都不同，仅编码撞了）
        MaterialSaveReqVO createReqVO = randomPojo(MaterialSaveReqVO.class, o -> {
            o.setId(null);
            o.setMaterialNo("MT-DUP-01");
            o.setName("另一个物料");
            o.setUnit("米");
            o.setCategoryId(null);
            o.setStatus(0);
        });

        // 调用，并断言异常：物料编码重复被拒
        assertServiceException(() -> materialService.createMaterial(createReqVO), MATERIAL_NO_DUPLICATE);
    }

    @Test
    public void testCreateMaterial_rebuildAfterLogicalDelete_succeeds() {
        // ===== ADR-004 A 类验收（W4-P6）：物料编码「删后可重建」必须**成功** =====
        // 历史：W4-P2 时这条会返回 500（真库 uk_material_no 不感知 deleted，
        //       已删行仍占编码）。ADR-004 把该索引降级为普通索引 idx_material_no，
        //       唯一性只约束「未删除行」→ 同编码重建是合法业务动作。
        // mock 数据：建一条物料并逻辑删除
        MaterialDO deleted = insertMaterial("MT-REBUILD-01", "已删物料", "S1", null);
        Long deletedId = deleted.getId();
        materialMapper.deleteById(deletedId);
        // 前置断言：逻辑删除行仍在库里（不是物理删除），且唯一性探测能看见它
        assertEquals(1L, materialMapper.selectDeletedCountByMaterialNo("MT-REBUILD-01"),
                "逻辑删除后行仍在库中，仍占用 material_no（这正是索引冲突的来源）");
        assertNull(materialMapper.selectByMaterialNo("MT-REBUILD-01"),
                "逻辑删除插件过滤后 Service 看不到已删行");

        // 准备参数：用同一编码重建
        MaterialSaveReqVO createReqVO = randomPojo(MaterialSaveReqVO.class, o -> {
            o.setId(null);
            o.setMaterialNo("MT-REBUILD-01");
            o.setName("重建物料");
            o.setUnit("吨");
            o.setCategoryId(null);
            o.setStatus(0);
        });

        // 调用：**必须成功**（不再抛 invalidParamException）
        Long newId = materialService.createMaterial(createReqVO);

        // 断言 1：返回新 id，且不等同于已删行
        assertNotNull(newId);
        assertNotEquals(deletedId, newId);
        // 断言 2（L3-10：返回值 == 库值）：Service 返回的 id 能查到，字段与请求一致
        MaterialDO created = materialMapper.selectById(newId);
        assertNotNull(created);
        assertEquals("MT-REBUILD-01", created.getMaterialNo());
        assertEquals("重建物料", created.getName());
        assertEquals(newId, created.getId());
        // 断言 3：库里同编码并存 —— 1 条活跃 + 1 条已删（A 类的核心效果）
        //   注意：Mapper 的 selectCount 走逻辑删除插件，只能看到活跃行；
        //   已删行必须用原生 SQL 的 selectDeletedCount* 才能看到。
        assertEquals(1, materialMapper.selectCount(MaterialDO::getMaterialNo, "MT-REBUILD-01"),
                "活跃行应只有新建的这一条");
        assertEquals(1, materialMapper.selectDeletedCountByMaterialNo("MT-REBUILD-01"),
                "已删行仍在，与新行并存（ADR-004 A 类的核心效果）");
        // 断言 4：Service 的校验视图（selectByMaterialNo）返回的是**新**行，不是已删行
        assertEquals(newId, materialMapper.selectByMaterialNo("MT-REBUILD-01").getId());
    }

    @Test
    public void testUpdateMaterial_success() {
        // mock 数据
        MaterialDO dbMaterial = insertMaterial("MT-UPD-01", "螺纹钢", "HRB400 Φ20", null);
        // 准备参数：编码不变（校验必须排除自身）
        MaterialSaveReqVO updateReqVO = randomPojo(MaterialSaveReqVO.class, o -> {
            o.setId(dbMaterial.getId());
            o.setMaterialNo("MT-UPD-01");
            o.setName("螺纹钢（修订）");
            o.setSpec("HRB400 Φ22");
            o.setUnit("吨");
            o.setCategoryId(null);
            o.setStatus(1);
        });

        // 调用
        materialService.updateMaterial(updateReqVO);
        // 校验是否更新正确
        MaterialDO material = materialMapper.selectById(updateReqVO.getId());
        assertPojoEquals(updateReqVO, material);
    }

    @Test
    public void testUpdateMaterial_notExists() {
        // 准备参数
        MaterialSaveReqVO updateReqVO = randomPojo(MaterialSaveReqVO.class, o -> {
            o.setId(999999L);
            o.setUnit("个");
            o.setStatus(0);
        });

        // 调用，并断言异常
        assertServiceException(() -> materialService.updateMaterial(updateReqVO), MATERIAL_NOT_EXISTS);
    }

    @Test
    public void testUpdateMaterial_materialNoDuplicate() {
        // mock 数据：两个物料，编码分别为 A、B
        MaterialDO first = insertMaterial("MT-A", "螺纹钢", "HRB400 Φ20", null);
        insertMaterial("MT-B", "圆钢", "Q235 Φ10", null);
        // 准备参数：把 first 的编码改成 B —— 撞他人
        MaterialSaveReqVO updateReqVO = randomPojo(MaterialSaveReqVO.class, o -> {
            o.setId(first.getId());
            o.setMaterialNo("MT-B");
            o.setName("螺纹钢");
            o.setUnit("吨");
            o.setCategoryId(null);
            o.setStatus(0);
        });

        // 调用，并断言异常
        assertServiceException(() -> materialService.updateMaterial(updateReqVO), MATERIAL_NO_DUPLICATE);
    }

    @Test
    public void testDeleteMaterial_success() {
        // mock 数据
        MaterialDO dbMaterial = insertMaterial("MT-DEL-01", "待删物料", "S1", null);

        // 调用
        materialService.deleteMaterial(dbMaterial.getId());
        // 校验数据不存在了
        assertNull(materialMapper.selectById(dbMaterial.getId()));
    }

    @Test
    public void testDeleteMaterial_notExists() {
        // 调用，并断言异常
        assertServiceException(() -> materialService.deleteMaterial(999999L), MATERIAL_NOT_EXISTS);
    }

    @Test
    public void testGetMaterial_success() {
        // mock 数据
        MaterialDO dbMaterial = insertMaterial("MT-GET-01", "螺纹钢", "HRB400 Φ20", null);

        // 调用
        MaterialDO material = materialService.getMaterial(dbMaterial.getId());
        // 断言
        assertPojoEquals(dbMaterial, material);
    }

    // ========== 分页多条件 ==========

    @Test
    public void testGetMaterialPage_byCategoryId() {
        // mock 数据：钢材目录下 2 条、木材目录下 1 条
        MaterialCategoryDO steelCategory = insertCategory("STEEL", "钢材");
        MaterialCategoryDO woodCategory = insertCategory("WOOD", "木材");
        MaterialDO match1 = insertMaterial("MT-S-01", "螺纹钢", "HRB400 Φ20", steelCategory.getId());
        MaterialDO match2 = insertMaterial("MT-S-02", "圆钢", "Q235 Φ10", steelCategory.getId());
        insertMaterial("MT-W-01", "木方", "40x90", woodCategory.getId());

        // 准备参数：按目录筛选
        MaterialPageReqVO reqVO = new MaterialPageReqVO();
        reqVO.setCategoryId(steelCategory.getId());

        // 调用
        PageResult<MaterialDO> pageResult = materialService.getMaterialPage(reqVO);
        // 断言：只返回钢材目录下的 2 条
        assertEquals(2, pageResult.getTotal());
        assertEquals(2, pageResult.getList().size());
        // 排序为 id 降序：后插入的 match2 在前
        assertEquals(match2.getId(), pageResult.getList().get(0).getId());
        assertEquals(match1.getId(), pageResult.getList().get(1).getId());
    }

    @Test
    public void testGetMaterialPage_byNameLike() {
        // mock 数据
        insertMaterial("MT-L-01", "螺纹钢", "HRB400 Φ20", null);
        insertMaterial("MT-L-02", "圆钢", "Q235 Φ10", null);
        insertMaterial("MT-L-03", "木方", "40x90", null);

        // 准备参数：名称模糊「钢」
        MaterialPageReqVO reqVO = new MaterialPageReqVO();
        reqVO.setName("钢");

        // 调用
        PageResult<MaterialDO> pageResult = materialService.getMaterialPage(reqVO);
        // 断言：命中 2 条带「钢」的
        assertEquals(2, pageResult.getTotal());
    }

    @Test
    public void testGetMaterialPage_byMaterialNoLike() {
        // mock 数据
        insertMaterial("MT-HRB400-20", "螺纹钢", "HRB400 Φ20", null);
        insertMaterial("MT-Q235-10", "圆钢", "Q235 Φ10", null);

        // 准备参数：编码模糊「HRB」
        MaterialPageReqVO reqVO = new MaterialPageReqVO();
        reqVO.setMaterialNo("HRB");

        // 调用
        PageResult<MaterialDO> pageResult = materialService.getMaterialPage(reqVO);
        // 断言
        assertEquals(1, pageResult.getTotal());
        assertEquals("MT-HRB400-20", pageResult.getList().get(0).getMaterialNo());
    }

    @Test
    public void testGetMaterialPage_bySpecLike() {
        // mock 数据
        insertMaterial("MT-SP-01", "螺纹钢", "HRB400 Φ20", null);
        insertMaterial("MT-SP-02", "圆钢", "Q235 Φ10", null);

        // 准备参数：规格模糊「Φ10」
        MaterialPageReqVO reqVO = new MaterialPageReqVO();
        reqVO.setSpec("Φ10");

        // 调用
        PageResult<MaterialDO> pageResult = materialService.getMaterialPage(reqVO);
        // 断言
        assertEquals(1, pageResult.getTotal());
        assertEquals("MT-SP-02", pageResult.getList().get(0).getMaterialNo());
    }

    @Test
    public void testGetMaterialPage_multiCondition() {
        // mock 数据：同目录下规格不同的两条 + 另一目录同规格的一条
        MaterialCategoryDO steelCategory = insertCategory("STEEL", "钢材");
        MaterialCategoryDO woodCategory = insertCategory("WOOD", "木材");
        insertMaterial("MT-MC-01", "螺纹钢", "HRB400 Φ20", steelCategory.getId());
        insertMaterial("MT-MC-02", "圆钢", "Q235 Φ20", steelCategory.getId());
        insertMaterial("MT-MC-03", "木方", "HRB400 Φ20", woodCategory.getId());

        // 准备参数：目录 + 名称 + 规格三个条件同时生效
        MaterialPageReqVO reqVO = new MaterialPageReqVO();
        reqVO.setCategoryId(steelCategory.getId());
        reqVO.setName("钢");
        reqVO.setSpec("HRB400");

        // 调用
        PageResult<MaterialDO> pageResult = materialService.getMaterialPage(reqVO);
        // 断言：三个条件取交集，只剩「螺纹钢」
        assertEquals(1, pageResult.getTotal());
        assertEquals("MT-MC-01", pageResult.getList().get(0).getMaterialNo());
    }

    @Test
    public void testGetMaterialPage_empty() {
        // 调用：无数据
        PageResult<MaterialDO> pageResult = materialService.getMaterialPage(new MaterialPageReqVO());
        // 断言：空分页而非 null
        assertNotNull(pageResult);
        assertEquals(0, pageResult.getTotal());
    }

    // ========== 测试数据构造 ==========

    /**
     * 插入一条测试目录（物料需要一个存在的目录才能挂）
     */
    private MaterialCategoryDO insertCategory(String code, String name) {
        MaterialCategoryDO category = randomPojo(MaterialCategoryDO.class, o -> {
            o.setParentId(MaterialCategoryDO.PARENT_ID_ROOT);
            o.setName(name);
            o.setCode(code);
            o.setSort(1);
            o.setStatus(0);
        });
        categoryMapper.insert(category);
        return category;
    }

    /**
     * 插入一条测试物料（直接走 mapper，绕过服务层校验，便于构造边界数据）
     */
    private MaterialDO insertMaterial(String materialNo, String name, String spec, Long categoryId) {
        MaterialDO material = randomPojo(MaterialDO.class, o -> {
            o.setMaterialNo(materialNo);
            o.setName(name);
            o.setSpec(spec);
            o.setUnit("吨");
            o.setCategoryId(categoryId);
            o.setStatus(0);
        });
        materialMapper.insert(material);
        return material;
    }

}
