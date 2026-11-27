package com.buildcloud.material.service.category;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategoryPageReqVO;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategoryRespVO;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategorySaveReqVO;
import com.buildcloud.material.dal.dataobject.category.MaterialCategoryDO;
import com.buildcloud.material.dal.mysql.category.MaterialCategoryMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.invalidParamException;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_CATEGORY_HAS_CHILDREN;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_CATEGORY_NAME_DUPLICATE;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_CATEGORY_NOT_EXISTS;

/**
 * 材料目录 Service 实现类
 *
 * 树形结构的三条必须守住的规则（生成器产物全都没有，属精修重点）：
 * 1. **同级同名拒绝**：不同父节点下允许同名（「主材/钢材」与「周转材/钢材」是两回事），
 *    只有同一 parent_id 下重名才让前端树出现两个无法区分的节点；
 * 2. **有子节点拒绝删除**：否则子目录的 parent_id 指向已删节点，树加载出孤儿节点；
 * 3. **父子关系防环**：更新时不允许把自己或自己的子孙设为父节点（否则形成环，
 *    建树时这些节点永远挂不到根上，前端表现为「节点凭空消失」）。
 *
 * 错误码使用说明（P1 已就绪的 010 段三条，未新增码）：
 * - 同级同名 → {@link com.buildcloud.material.enums.ErrorCodeConstants#MATERIAL_CATEGORY_NAME_DUPLICATE}；
 * - 有子节点 → {@link com.buildcloud.material.enums.ErrorCodeConstants#MATERIAL_CATEGORY_HAS_CHILDREN}；
 * - 目录/父目录不存在 → {@link com.buildcloud.material.enums.ErrorCodeConstants#MATERIAL_CATEGORY_NOT_EXISTS}；
 * - 编码重复、父子成环 → {@code invalidParamException}（400 + 可读提示）。
 *   这两类在 010 段没有专属码；按「错误码集中管理、不新增段位内编号」的约束，
 *   先用框架参数异常表达，等 spec 该段位扩容时再提升为专属业务码。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class MaterialCategoryServiceImpl implements MaterialCategoryService {

    /**
     * 祖先链回溯的最大深度
     *
     * 防环校验是「从目标父节点往上爬，看会不会爬回自己」。若库里已有脏数据（历史遗留的环），
     * 无上限的 while 会死循环拖死请求线程，故设上限兜底并按「视为成环」拒绝。
     * 100 层对材料目录（通常 3-5 层）绰绰有余。
     */
    private static final int MAX_ANCESTOR_DEPTH = 100;

    @Resource
    private MaterialCategoryMapper materialCategoryMapper;

    @Override
    public Long createCategory(MaterialCategorySaveReqVO createReqVO) {
        // 归一化父节点：前端不传或传 null 一律视为根节点（0），
        // 避免库里同时存在 parent_id = null 与 = 0 两种"根"，导致查子节点漏数据
        Long parentId = normalizeParentId(createReqVO.getParentId());
        // 校验父目录存在（根节点的父不需要校验）
        validateParentExists(parentId);
        // 校验同级同名（新增不排除自身）
        validateNameUnique(null, parentId, createReqVO.getName());
        // 校验编码唯一（真库 uk_material_category_code 前的业务侧前置校验，给出可读错误）
        validateCodeUnique(null, createReqVO.getCode());
        // 插入
        MaterialCategoryDO category = BeanUtils.toBean(createReqVO, MaterialCategoryDO.class);
        category.setParentId(parentId);
        // sort 为 null 时落 0，避免前端不传排序号导致 DB 默认值与 DO 不一致
        if (category.getSort() == null) {
            category.setSort(0);
        }
        materialCategoryMapper.insert(category);
        // 返回
        return category.getId();
    }

    @Override
    public void updateCategory(MaterialCategorySaveReqVO updateReqVO) {
        // 校验存在
        validateCategoryExists(updateReqVO.getId());
        // 归一化父节点
        Long parentId = normalizeParentId(updateReqVO.getParentId());
        // 校验父目录存在
        validateParentExists(parentId);
        // 校验父子关系合法：不能把自己设为自己的父节点，也不能挂到自己的子孙下（成环）
        validateParentNotSelfOrDescendant(updateReqVO.getId(), parentId);
        // 校验同级同名（更新时排除自身，否则"名称不变"会被自己判为重复）
        validateNameUnique(updateReqVO.getId(), parentId, updateReqVO.getName());
        // 校验编码唯一（排除自身）
        validateCodeUnique(updateReqVO.getId(), updateReqVO.getCode());
        // 更新
        MaterialCategoryDO updateObj = BeanUtils.toBean(updateReqVO, MaterialCategoryDO.class);
        updateObj.setParentId(parentId);
        materialCategoryMapper.updateById(updateObj);
    }

    @Override
    public void deleteCategory(Long id) {
        // 校验存在
        validateCategoryExists(id);
        // 校验无子节点：有子节点必须先删/先挪走子节点
        // （原因见类注释第 2 条：否则子目录的 parent_id 会指向已删节点，树里出现孤儿）
        Long childrenCount = materialCategoryMapper.selectCountByParentId(id);
        if (childrenCount != null && childrenCount > 0) {
            throw exception(MATERIAL_CATEGORY_HAS_CHILDREN);
        }
        // 删除
        materialCategoryMapper.deleteById(id);
    }

    @Override
    public MaterialCategoryDO getCategory(Long id) {
        return materialCategoryMapper.selectById(id);
    }

    @Override
    public List<MaterialCategoryDO> getCategoryList() {
        return materialCategoryMapper.selectListOrderByTree();
    }

    @Override
    public PageResult<MaterialCategoryDO> getCategoryPage(MaterialCategoryPageReqVO pageReqVO) {
        return materialCategoryMapper.selectPage(pageReqVO);
    }

    @Override
    public List<MaterialCategoryRespVO> getCategoryTree() {
        List<MaterialCategoryDO> all = materialCategoryMapper.selectListOrderByTree();
        if (CollUtil.isEmpty(all)) {
            return new ArrayList<>();
        }
        return buildTree(all);
    }

    // ========== 建树 ==========

    /**
     * 把扁平列表组装成嵌套树
     *
     * 实现要点（为什么不是递归查库）：
     * 1. **一次查全表，内存组树**：目录是小表（租户内几十到几百节点），一次查询 + O(n) 组装
     *    远好过逐层递归查询（N+1 次 SQL）；bc_material_category 的 idx_parent_id 留给
     *    「按 parentId 懒加载单层」的场景，全树加载走顺序扫更划算；
     * 2. **parent 不在集合里的节点提升为根**：父节点可能已被别的请求删掉（或数据被直接改库），
     *    这类孤儿节点若直接丢弃，前端会发现"明明有这条数据却看不到"；提升为根至少可见可达，
     *    运维能发现并重新归类。这也让本方法对脏数据天然健壮，不会抛异常；
     * 3. **依赖入参已按 parent_id/sort/id 排好序**：子节点 append 的顺序即渲染顺序，
     *    故建树前不重新排序（排序是 Mapper 的职责，见 selectListOrderByTree）；
     * 4. **用 LinkedHashMap 保持插入顺序**，根节点顺序与查询顺序一致，前端渲染稳定。
     *
     * @param all 已排序的扁平目录列表
     * @return 根节点列表（每个节点含完整子树）
     */
    private List<MaterialCategoryRespVO> buildTree(List<MaterialCategoryDO> all) {
        // 1. DO -> VO，并建立 id -> VO 索引（LinkedHashMap 保序）
        Map<Long, MaterialCategoryRespVO> nodeMap = new LinkedHashMap<>(all.size());
        for (MaterialCategoryDO category : all) {
            MaterialCategoryRespVO node = BeanUtils.toBean(category, MaterialCategoryRespVO.class);
            // 显式初始化：叶子节点也要是空数组而非 null，前端 v-for 无需判空
            node.setChildren(new ArrayList<>());
            nodeMap.put(category.getId(), node);
        }
        // 2. 逐个挂到父节点下
        List<MaterialCategoryRespVO> roots = new ArrayList<>();
        for (MaterialCategoryDO category : all) {
            MaterialCategoryRespVO node = nodeMap.get(category.getId());
            Long parentId = category.getParentId();
            MaterialCategoryRespVO parent = parentId == null ? null : nodeMap.get(parentId);
            if (parent == null) {
                // 根节点（parent_id = 0）或父节点不在本次结果集内（见方法注释第 2 条）
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    // ========== 校验方法 ==========

    private void validateCategoryExists(Long id) {
        if (materialCategoryMapper.selectById(id) == null) {
            throw exception(MATERIAL_CATEGORY_NOT_EXISTS);
        }
    }

    /**
     * 校验父目录存在
     *
     * 根节点（0）不需要校验——0 不是一条真实记录，是约定值。
     *
     * @param parentId 父目录编号（已归一化）
     */
    private void validateParentExists(Long parentId) {
        if (MaterialCategoryDO.PARENT_ID_ROOT.equals(parentId)) {
            return;
        }
        if (materialCategoryMapper.selectById(parentId) == null) {
            // 复用「材料目录不存在」：父目录也是一条目录记录，语义一致（避免为其新增专属码）
            throw exception(MATERIAL_CATEGORY_NOT_EXISTS);
        }
    }

    /**
     * 校验同级下名称唯一
     *
     * @param excludeId 需要排除的目录编号（更新时排除自身；新增传 null）
     * @param parentId  父目录编号（已归一化）
     * @param name      目录名称
     */
    private void validateNameUnique(Long excludeId, Long parentId, String name) {
        if (materialCategoryMapper.existsByParentIdAndName(parentId, name, excludeId)) {
            throw exception(MATERIAL_CATEGORY_NAME_DUPLICATE);
        }
    }

    /**
     * 校验目录编码租户内唯一
     *
     * 两层校验，缺一不可：
     * 1. **未删除行重名** → 可读业务提示（正常的"编码已存在"）；
     * 2. **已逻辑删除行仍占用该编码** → 也给出可读提示。
     *
     * 第 2 层是 W4-P2 联调实测出来的真实缺陷：真库 uk_material_category_code 是普通唯一索引，
     * 不感知 deleted 列，逻辑删除的行**仍然占用**编码。只做第 1 层时，
     * 「删掉 STEEL 再新建 STEEL」会绕过 Service 校验直接撞索引，
     * 前端拿到的是 `code=500 系统异常`——比给不出原因更糟，用户完全无法自助解决。
     *
     * 这里只**探测并给出可操作提示**，不绕过索引（要真正支持"删后同编码重建"，
     * 须改真库唯一键 = AGENTS R-4 改数据库结构，属 P1 的 07-material.sql 决策范围）。
     *
     * @param excludeId 需要排除的目录编号（更新时排除自身；新增传 null）
     * @param code      目录编码
     */
    private void validateCodeUnique(Long excludeId, String code) {
        MaterialCategoryDO existing = materialCategoryMapper.selectByCode(code);
        if (existing != null && !Objects.equals(existing.getId(), excludeId)) {
            throw invalidParamException("材料目录编码【{}】已存在，请更换", code);
        }
        // 已被逻辑删除的行仍占用真库唯一索引：提前拦下，避免 500
        Long deletedCount = materialCategoryMapper.selectDeletedCountByCode(code);
        if (deletedCount != null && deletedCount > 0) {
            throw invalidParamException(
                    "材料目录编码【{}】曾被已删除的目录使用，仍占用唯一索引，请更换编码或联系管理员清理历史数据", code);
        }
    }

    /**
     * 校验父子关系不成环
     *
     * 三种非法情形：
     * 1. 把自己设为自己的父节点（parentId == id）；
     * 2. 把自己的某个子孙设为父节点（如 A -> B，再把 A 的 parentId 改成 B 的子节点 C）——
     *    这会让 A、B、C 成为一个闭环，建树时三者都挂不到根上；
     * 3. 祖先链脏数据成环（回溯超过 {@link #MAX_ANCESTOR_DEPTH} 层）。
     *
     * 判定法：从目标父节点开始沿 parent_id 往上爬，途中遇到自己即说明成环。
     * 复杂度 O(深度)，材料目录通常 3-5 层，成本可忽略。
     *
     * @param id       当前目录编号
     * @param parentId 目标父目录编号（已归一化）
     */
    private void validateParentNotSelfOrDescendant(Long id, Long parentId) {
        if (MaterialCategoryDO.PARENT_ID_ROOT.equals(parentId)) {
            return; // 挂到根节点永远不会成环
        }
        if (Objects.equals(id, parentId)) {
            throw invalidParamException("不能将目录【{}】的父目录设置为自己", id);
        }
        // 沿祖先链上溯，遇到自己即成环
        Long cursor = parentId;
        int depth = 0;
        while (cursor != null && !MaterialCategoryDO.PARENT_ID_ROOT.equals(cursor)) {
            if (Objects.equals(cursor, id)) {
                throw invalidParamException("不能将目录【{}】的父目录设置为其子目录，会形成循环", id);
            }
            if (++depth > MAX_ANCESTOR_DEPTH) {
                // 库里已有环（脏数据），无法判定安全，一律拒绝并留日志以便排查
                log.error("[validateParentNotSelfOrDescendant][目录({}) 祖先链超过 {} 层，疑似存在循环数据]", id, MAX_ANCESTOR_DEPTH);
                throw invalidParamException("材料目录层级异常，请联系管理员检查目录数据");
            }
            MaterialCategoryDO parent = materialCategoryMapper.selectById(cursor);
            cursor = parent == null ? null : parent.getParentId();
        }
    }

    /**
     * 父节点编号归一化：null → 0（根）
     *
     * @param parentId 原始父编号
     * @return 归一化后的父编号
     */
    private Long normalizeParentId(Long parentId) {
        return parentId == null ? MaterialCategoryDO.PARENT_ID_ROOT : parentId;
    }

}
