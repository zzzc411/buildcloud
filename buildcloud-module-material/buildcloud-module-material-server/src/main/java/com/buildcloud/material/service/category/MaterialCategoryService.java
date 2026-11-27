package com.buildcloud.material.service.category;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategoryPageReqVO;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategoryRespVO;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategorySaveReqVO;
import com.buildcloud.material.dal.dataobject.category.MaterialCategoryDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 材料目录 Service 接口
 *
 * 业务边界（spec v1.2 §5.2 用例「目录维护」）：
 * 1. 树形结构：parent_id = 0 为根节点；同级同名拒绝（MATERIAL_CATEGORY_NAME_DUPLICATE）；
 * 2. 编码 code 租户内唯一（MATERIAL_CATEGORY_CODE_DUPLICATE 口径由真库唯一键兜底，
 *    Service 侧先查重给出可读错误码）；
 * 3. 删除前置校验：有子节点拒绝（MATERIAL_CATEGORY_HAS_CHILDREN）——
 *    否则子目录的 parent_id 会指向已删节点，整棵树出现挂不上的孤儿节点。
 *
 * @author buildcloud
 */
public interface MaterialCategoryService {

    /**
     * 创建目录
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createCategory(@Valid MaterialCategorySaveReqVO createReqVO);

    /**
     * 更新目录
     *
     * @param updateReqVO 更新信息
     */
    void updateCategory(@Valid MaterialCategorySaveReqVO updateReqVO);

    /**
     * 删除目录
     *
     * @param id 编号
     */
    void deleteCategory(Long id);

    /**
     * 获得目录
     *
     * @param id 编号
     * @return 目录
     */
    MaterialCategoryDO getCategory(Long id);

    /**
     * 获得全量目录列表（扁平，按 parent_id + sort 升序，供前端组树）
     *
     * @return 目录列表
     */
    List<MaterialCategoryDO> getCategoryList();

    /**
     * 获得目录分页（目录很多时按名称/编码搜索的运维场景）
     *
     * @param pageReqVO 分页查询
     * @return 目录分页
     */
    PageResult<MaterialCategoryDO> getCategoryPage(MaterialCategoryPageReqVO pageReqVO);

    /**
     * 获得嵌套目录树（children 已填充）
     *
     * 服务端建树的意义：父子归属与同级排序的口径只有一份，避免每个前端各自实现后顺序漂移；
     * 且「父节点已被删的孤儿节点提升为根」这条兜底规则也只有一份，放在前端会各写各的。
     *
     * 返回类型是 RespVO 而非 DO：本方法的产出天然带 children 嵌套结构（DO 是表映射，
     * 加非持久化字段会污染 DAL 层）。同模块其他 Service 的入参也用 controller.vo 包的类型
     * （SaveReqVO/PageReqVO），依赖方向一致。
     *
     * @return 根节点列表（每个节点含完整子树；叶子节点 children 为空数组而非 null）
     */
    List<MaterialCategoryRespVO> getCategoryTree();

}
