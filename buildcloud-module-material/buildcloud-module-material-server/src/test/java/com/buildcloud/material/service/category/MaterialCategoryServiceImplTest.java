package com.buildcloud.material.service.category;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategoryPageReqVO;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategoryRespVO;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategorySaveReqVO;
import com.buildcloud.material.dal.dataobject.category.MaterialCategoryDO;
import com.buildcloud.material.dal.mysql.category.MaterialCategoryMapper;
import com.buildcloud.material.service.ServiceExceptionAssert;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomString;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_CATEGORY_HAS_CHILDREN;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_CATEGORY_NAME_DUPLICATE;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_CATEGORY_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MaterialCategoryServiceImpl} 的单元测试类
 *
 * 风格照 project 模块的 {@code ProjectMilestoneServiceImplTest}：继承 {@link BaseDbUnitTest}
 * （H2 内存库 + MyBatis），@Import 被测 Service。
 *
 * H2 建表：用**本子域独立脚本** {@code /sql/material-test-tables.sql}（含 category/material/supplier 三张表），
 * 在每次测试方法前置阶段 DROP+CREATE，故用例之间数据天然隔离；
 * 不改 material 域共享的 create_tables.sql / clean.sql（并行 worker 约定，见脚本头注释）。
 *
 * 覆盖点：
 * 1. CRUD 基础链路（含父目录存在性校验）；
 * 2. **树形**：/list 扁平顺序、/tree 嵌套父子关系、叶子 children 为空数组、孤儿节点提升为根；
 * 3. **同级同名拒绝**（同父同名拒、不同父同名允许、更新排除自身）；
 * 4. **有子节点删除被拒**（MATERIAL_CATEGORY_HAS_CHILDREN）、无子节点可删；
 * 5. 编码唯一、父子成环拒绝。
 *
 * @author buildcloud
 */
@Import(MaterialCategoryServiceImpl.class)
@Sql(scripts = "/sql/material-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class MaterialCategoryServiceImplTest extends BaseDbUnitTest {

    @Resource
    private MaterialCategoryServiceImpl categoryService;

    @Resource
    private MaterialCategoryMapper categoryMapper;

    // ========== 基础 CRUD ==========

    @Test
    public void testCreateCategory_root() {
        // 准备参数：不传 parentId，期望归一化为 0（根节点）
        MaterialCategorySaveReqVO createReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(null);
            o.setParentId(null);
            o.setName("主材");
            o.setCode("MAIN");
            o.setSort(1);
            o.setStatus(0);
        });

        // 调用
        Long id = categoryService.createCategory(createReqVO);
        // 断言：parentId 已归一化为 0
        assertNotNull(id);
        MaterialCategoryDO category = categoryMapper.selectById(id);
        assertEquals(MaterialCategoryDO.PARENT_ID_ROOT, category.getParentId());
        assertPojoEquals(createReqVO, category, "id", "parentId");
    }

    @Test
    public void testCreateCategory_child() {
        // mock 数据：先建一个根节点
        MaterialCategoryDO parent = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        // 准备参数
        MaterialCategorySaveReqVO createReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(null);
            o.setParentId(parent.getId());
            o.setName("钢材");
            o.setCode("STEEL");
            o.setSort(1);
            o.setStatus(0);
        });

        // 调用
        Long id = categoryService.createCategory(createReqVO);
        // 断言
        MaterialCategoryDO category = categoryMapper.selectById(id);
        assertEquals(parent.getId(), category.getParentId());
    }

    @Test
    public void testCreateCategory_parentNotExists() {
        // 准备参数：父目录编号不存在
        MaterialCategorySaveReqVO createReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(null);
            o.setParentId(999999L);
            o.setCode("ORPHAN-" + randomString());
            o.setStatus(0);
        });

        // 调用，并断言异常
        assertServiceException(() -> categoryService.createCategory(createReqVO), MATERIAL_CATEGORY_NOT_EXISTS);
    }

    @Test
    public void testCreateCategory_nameDuplicate_sameParent() {
        // mock 数据：根节点下已有「主材」
        MaterialCategoryDO parent = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        insertCategory(parent.getId(), "钢材", "STEEL", 1);
        // 准备参数：同父下再加一个「钢材」（编码不同，仅名称重复）
        MaterialCategorySaveReqVO createReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(null);
            o.setParentId(parent.getId());
            o.setName("钢材");
            o.setCode("STEEL-2");
            o.setStatus(0);
        });

        // 调用，并断言异常：同级同名被拒
        assertServiceException(() -> categoryService.createCategory(createReqVO),
                MATERIAL_CATEGORY_NAME_DUPLICATE);
    }

    @Test
    public void testCreateCategory_sameName_differentParent_allowed() {
        // mock 数据：两个根节点下各一个「钢材」——不同父节点下同名是合法的
        MaterialCategoryDO parentA = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        MaterialCategoryDO parentB = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "周转材", "TEMP", 2);
        insertCategory(parentA.getId(), "钢材", "MAIN-STEEL", 1);

        // 准备参数：往 parentB 下加同名「钢材」
        MaterialCategorySaveReqVO createReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(null);
            o.setParentId(parentB.getId());
            o.setName("钢材");
            o.setCode("TEMP-STEEL");
            o.setStatus(0);
        });

        // 调用：应当允许
        Long id = categoryService.createCategory(createReqVO);
        // 断言
        assertNotNull(id);
        assertEquals(2, categoryMapper.selectCountByParentId(parentA.getId())
                + categoryMapper.selectCountByParentId(parentB.getId()));
    }

    @Test
    public void testCreateCategory_codeDuplicate() {
        // mock 数据：已存在编码 STEEL
        insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "钢材", "STEEL", 1);
        // 准备参数：编码重复（名称不同、父节点不同，仅 code 撞了）
        MaterialCategorySaveReqVO createReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(null);
            o.setParentId(MaterialCategoryDO.PARENT_ID_ROOT);
            o.setName("另一个钢材目录");
            o.setCode("STEEL");
            o.setStatus(0);
        });

        // 调用，并断言异常：编码重复被拒。
        // 注意：这里断言的是「参数异常（400）+ 提示里含该编码」，因为 010 段位没有专属的
        // MATERIAL_CATEGORY_CODE_DUPLICATE 码（详见 MaterialCategoryServiceImpl 类注释的错误码说明），
        // 不能伪造一个不存在的 ErrorCode 常量来断言。
        ServiceExceptionAssert.assertInvalidParam(
                () -> categoryService.createCategory(createReqVO), "STEEL");
    }

    @Test
    public void testCreateCategory_codeOccupiedByDeletedRow() {
        // mock 数据：建一个目录并**逻辑删除**它（模拟真库：deleted=1 的行仍占用 uk_material_category_code）
        MaterialCategoryDO deleted = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "钢材", "STEEL", 1);
        categoryMapper.deleteById(deleted.getId());

        // 准备参数：用同一个 code 重新建
        MaterialCategorySaveReqVO createReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(null);
            o.setParentId(MaterialCategoryDO.PARENT_ID_ROOT);
            o.setName("钢材（重建）");
            o.setCode("STEEL");
            o.setStatus(0);
        });

        // 断言：不撞真库唯一索引返回 500，而是给出**可操作**的可读提示
        // （这是 W4-P2 联调实测出的真实缺陷：真库 uk 不感知 deleted，逻辑删除行仍占编码）
        ServiceExceptionAssert.assertInvalidParam(
                () -> categoryService.createCategory(createReqVO), "STEEL");
    }

    @Test
    public void testUpdateCategory_success() {
        // mock 数据
        MaterialCategoryDO dbCategory = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        // 准备参数：名称不变（校验必须排除自身）、排序改
        MaterialCategorySaveReqVO updateReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(dbCategory.getId());
            o.setParentId(MaterialCategoryDO.PARENT_ID_ROOT);
            o.setName("主材");
            o.setCode("MAIN");
            o.setSort(9);
            o.setStatus(1);
        });

        // 调用
        categoryService.updateCategory(updateReqVO);
        // 校验是否更新正确
        MaterialCategoryDO category = categoryMapper.selectById(updateReqVO.getId());
        assertPojoEquals(updateReqVO, category);
    }

    @Test
    public void testUpdateCategory_notExists() {
        // 准备参数
        MaterialCategorySaveReqVO updateReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(999999L);
            o.setParentId(MaterialCategoryDO.PARENT_ID_ROOT);
            o.setCode("ANY");
            o.setStatus(0);
        });

        // 调用，并断言异常
        assertServiceException(() -> categoryService.updateCategory(updateReqVO),
                MATERIAL_CATEGORY_NOT_EXISTS);
    }

    @Test
    public void testUpdateCategory_nameDuplicate_otherSibling() {
        // mock 数据：根下已有「主材」与「周转材」
        MaterialCategoryDO first = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        MaterialCategoryDO second = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "周转材", "TEMP", 2);
        // 准备参数：把「周转材」改名为「主材」——与兄弟节点撞名
        MaterialCategorySaveReqVO updateReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(second.getId());
            o.setParentId(MaterialCategoryDO.PARENT_ID_ROOT);
            o.setName("主材");
            o.setCode("TEMP");
            o.setStatus(0);
        });

        // 调用，并断言异常
        assertServiceException(() -> categoryService.updateCategory(updateReqVO),
                MATERIAL_CATEGORY_NAME_DUPLICATE);
    }

    @Test
    public void testUpdateCategory_parentIsSelf() {
        // mock 数据
        MaterialCategoryDO dbCategory = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        // 准备参数：把自己设为自己的父节点
        MaterialCategorySaveReqVO updateReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(dbCategory.getId());
            o.setParentId(dbCategory.getId());
            o.setName("主材");
            o.setCode("MAIN");
            o.setStatus(0);
        });

        // 调用，并断言异常（成环被拒，400 参数异常）
        ServiceExceptionAssert.assertInvalidParam(
                () -> categoryService.updateCategory(updateReqVO), String.valueOf(dbCategory.getId()));
    }

    @Test
    public void testUpdateCategory_parentIsDescendant() {
        // mock 数据：A（根） → B（A 的子） → C（B 的子）
        MaterialCategoryDO a = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "A", "A", 1);
        MaterialCategoryDO b = insertCategory(a.getId(), "B", "B", 1);
        MaterialCategoryDO c = insertCategory(b.getId(), "C", "C", 1);
        // 准备参数：把 A 的父节点设为自己的孙子 C —— 成环
        MaterialCategorySaveReqVO updateReqVO = randomPojo(MaterialCategorySaveReqVO.class, o -> {
            o.setId(a.getId());
            o.setParentId(c.getId());
            o.setName("A");
            o.setCode("A");
            o.setStatus(0);
        });

        // 调用，并断言异常
        ServiceExceptionAssert.assertInvalidParam(
                () -> categoryService.updateCategory(updateReqVO), String.valueOf(a.getId()));
    }

    @Test
    public void testDeleteCategory_success() {
        // mock 数据：叶子节点
        MaterialCategoryDO dbCategory = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);

        // 调用
        categoryService.deleteCategory(dbCategory.getId());
        // 校验数据不存在了
        assertNull(categoryMapper.selectById(dbCategory.getId()));
    }

    @Test
    public void testDeleteCategory_hasChildren() {
        // mock 数据：父节点下挂一个子节点
        MaterialCategoryDO parent = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        insertCategory(parent.getId(), "钢材", "STEEL", 1);

        // 调用，并断言异常：有子节点拒绝删除
        assertServiceException(() -> categoryService.deleteCategory(parent.getId()),
                MATERIAL_CATEGORY_HAS_CHILDREN);
        // 断言父节点仍在（异常不应产生副作用）
        assertNotNull(categoryMapper.selectById(parent.getId()));
    }

    @Test
    public void testDeleteCategory_notExists() {
        // 调用，并断言异常
        assertServiceException(() -> categoryService.deleteCategory(999999L), MATERIAL_CATEGORY_NOT_EXISTS);
    }

    @Test
    public void testGetCategory_success() {
        // mock 数据
        MaterialCategoryDO dbCategory = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);

        // 调用
        MaterialCategoryDO category = categoryService.getCategory(dbCategory.getId());
        // 断言
        assertPojoEquals(dbCategory, category);
    }

    // ========== 树形查询 ==========

    @Test
    public void testGetCategoryList_flat_order() {
        // mock 数据：故意乱序插入。
        // 根节点按 sort 升序：主材(1) 在 周转材(2) 之前
        MaterialCategoryDO main = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        MaterialCategoryDO temp = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "周转材", "TEMP", 2);
        // 子节点
        MaterialCategoryDO steel = insertCategory(main.getId(), "钢材", "STEEL", 1);

        // 调用
        List<MaterialCategoryDO> list = categoryService.getCategoryList();
        // 断言：3 条全返回，且按 parent_id → sort 升序（根节点 0 排在前面）
        assertEquals(3, list.size());
        assertEquals(main.getId(), list.get(0).getId());
        assertEquals(temp.getId(), list.get(1).getId());
        assertEquals(steel.getId(), list.get(2).getId());
    }

    @Test
    public void testGetCategoryList_empty() {
        // 调用：无数据 → 空列表（不是 null，前端可直接遍历）
        List<MaterialCategoryDO> list = categoryService.getCategoryList();
        // 断言
        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    public void testGetCategoryTree_parentChild() {
        // mock 数据：根「主材」下两个子节点「钢材」「木材」，另一个根「周转材」
        MaterialCategoryDO main = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        MaterialCategoryDO temp = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "周转材", "TEMP", 2);
        MaterialCategoryDO steel = insertCategory(main.getId(), "钢材", "STEEL", 1);
        MaterialCategoryDO wood = insertCategory(main.getId(), "木材", "WOOD", 2);

        // 调用
        List<MaterialCategoryRespVO> tree = categoryService.getCategoryTree();

        // 断言 1：两个根节点，按 sort 升序
        assertEquals(2, tree.size());
        MaterialCategoryRespVO mainNode = tree.get(0);
        MaterialCategoryRespVO tempNode = tree.get(1);
        assertEquals(main.getId(), mainNode.getId());
        assertEquals(temp.getId(), tempNode.getId());

        // 断言 2：主材下两个子节点，按 sort 升序
        assertEquals(2, mainNode.getChildren().size());
        assertEquals(steel.getId(), mainNode.getChildren().get(0).getId());
        assertEquals(wood.getId(), mainNode.getChildren().get(1).getId());

        // 断言 3：周转材无子节点 → children 是空数组而非 null（前端 v-for 无需判空）
        assertNotNull(tempNode.getChildren());
        assertTrue(tempNode.getChildren().isEmpty());

        // 断言 4：叶子节点的 children 也是空数组
        assertNotNull(mainNode.getChildren().get(0).getChildren());
        assertTrue(mainNode.getChildren().get(0).getChildren().isEmpty());

        // 断言 5：嵌套节点自身的 parentId 正确（前端做面包屑/移动节点时要用）
        assertEquals(main.getId(), mainNode.getChildren().get(0).getParentId());
    }

    @Test
    public void testGetCategoryTree_multiLevel() {
        // mock 数据：三层 A → B → C
        MaterialCategoryDO a = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "A", "A", 1);
        MaterialCategoryDO b = insertCategory(a.getId(), "B", "B", 1);
        MaterialCategoryDO c = insertCategory(b.getId(), "C", "C", 1);

        // 调用
        List<MaterialCategoryRespVO> tree = categoryService.getCategoryTree();

        // 断言：三层嵌套结构完整
        assertEquals(1, tree.size());
        MaterialCategoryRespVO aNode = tree.get(0);
        assertEquals(a.getId(), aNode.getId());
        assertEquals(1, aNode.getChildren().size());
        MaterialCategoryRespVO bNode = aNode.getChildren().get(0);
        assertEquals(b.getId(), bNode.getId());
        assertEquals(1, bNode.getChildren().size());
        MaterialCategoryRespVO cNode = bNode.getChildren().get(0);
        assertEquals(c.getId(), cNode.getId());
        assertTrue(cNode.getChildren().isEmpty());
    }

    @Test
    public void testGetCategoryTree_orphanPromotedToRoot() {
        // mock 数据：直接落一个 parent_id 指向不存在目录的「孤儿」节点
        // （模拟父节点被别处删掉、或直接改库产生的脏数据）
        MaterialCategoryDO orphan = insertCategory(888888L, "孤儿目录", "ORPHAN", 1);

        // 调用
        List<MaterialCategoryRespVO> tree = categoryService.getCategoryTree();

        // 断言：孤儿节点不丢，被提升为根节点（否则前端"明明有数据却看不到"）
        assertEquals(1, tree.size());
        assertEquals(orphan.getId(), tree.get(0).getId());
        assertTrue(tree.get(0).getChildren().isEmpty());
    }

    @Test
    public void testGetCategoryTree_empty() {
        // 调用
        List<MaterialCategoryRespVO> tree = categoryService.getCategoryTree();
        // 断言：空树返回空列表（不是 null）
        assertNotNull(tree);
        assertTrue(tree.isEmpty());
    }

    @Test
    public void testGetCategoryPage() {
        // mock 数据：根下两个、另一根下一个
        MaterialCategoryDO main = insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        insertCategory(main.getId(), "钢材", "STEEL", 1);
        insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "周转材", "TEMP", 2);

        // 准备参数：按父节点筛选（只要根节点）
        MaterialCategoryPageReqVO reqVO = new MaterialCategoryPageReqVO();
        reqVO.setParentId(MaterialCategoryDO.PARENT_ID_ROOT);

        // 调用
        PageResult<MaterialCategoryDO> pageResult = categoryService.getCategoryPage(reqVO);
        // 断言：只返回 2 个根节点
        assertEquals(2, pageResult.getTotal());
        assertEquals(2, pageResult.getList().size());
    }

    @Test
    public void testGetCategoryPage_byNameLike() {
        // mock 数据
        insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "主材", "MAIN", 1);
        insertCategory(MaterialCategoryDO.PARENT_ID_ROOT, "周转材", "TEMP", 2);

        // 准备参数：按名称模糊
        MaterialCategoryPageReqVO reqVO = new MaterialCategoryPageReqVO();
        reqVO.setName("主");

        // 调用
        PageResult<MaterialCategoryDO> pageResult = categoryService.getCategoryPage(reqVO);
        // 断言：只命中「主材」
        assertEquals(1, pageResult.getTotal());
        assertEquals("主材", pageResult.getList().get(0).getName());
    }

    // ========== 测试数据构造 ==========

    /**
     * 插入一条测试目录（直接走 mapper，绕过服务层校验，便于构造边界/脏数据）
     */
    private MaterialCategoryDO insertCategory(Long parentId, String name, String code, Integer sort) {
        MaterialCategoryDO category = randomPojo(MaterialCategoryDO.class, o -> {
            o.setParentId(parentId);
            o.setName(name);
            o.setCode(code);
            o.setSort(sort);
            o.setStatus(0);
        });
        categoryMapper.insert(category);
        return category;
    }

}
