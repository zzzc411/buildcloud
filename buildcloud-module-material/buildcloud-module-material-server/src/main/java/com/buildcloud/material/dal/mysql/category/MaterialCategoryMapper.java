package com.buildcloud.material.dal.mysql.category;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategoryPageReqVO;
import com.buildcloud.material.dal.dataobject.category.MaterialCategoryDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 材料目录 Mapper
 *
 * 继承基座 {@link BaseMapperX}；条件用 {@link LambdaQueryWrapperX} 的 xxxIfPresent 系列
 * （参数为空则不加条件，基座约定，勿改写成手写 XML）。
 *
 * @author buildcloud
 */
@Mapper
public interface MaterialCategoryMapper extends BaseMapperX<MaterialCategoryDO> {

    /**
     * 查询全量目录列表（供前端组树 / 服务端建树共用）
     *
     * 排序规则：先按 parent_id、再按 sort、最后按 id 兜底。
     * 三者一起才能保证同一 sort 值下渲染顺序稳定（H2/MySQL 对并列行不保证顺序）——
     * 服务端建树时依赖「父节点一定出现在子节点之前」这一点，排序不稳会导致树结构抖动。
     *
     * @return 目录列表（扁平）
     */
    default List<MaterialCategoryDO> selectListOrderByTree() {
        return selectList(new LambdaQueryWrapperX<MaterialCategoryDO>()
                .orderByAsc(MaterialCategoryDO::getParentId)
                .orderByAsc(MaterialCategoryDO::getSort)
                .orderByAsc(MaterialCategoryDO::getId));
    }

    /**
     * 目录分页查询（目录很多时的搜索场景）
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<MaterialCategoryDO> selectPage(MaterialCategoryPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MaterialCategoryDO>()
                .eqIfPresent(MaterialCategoryDO::getParentId, reqVO.getParentId())
                .likeIfPresent(MaterialCategoryDO::getName, reqVO.getName())
                .likeIfPresent(MaterialCategoryDO::getCode, reqVO.getCode())
                .eqIfPresent(MaterialCategoryDO::getStatus, reqVO.getStatus())
                .orderByAsc(MaterialCategoryDO::getSort)
                .orderByAsc(MaterialCategoryDO::getId));
    }

    /**
     * 按 parent_id 查询直接子节点（删除校验用：有子节点则拒绝删除）
     *
     * @param parentId 父目录编号
     * @return 子目录列表
     */
    default List<MaterialCategoryDO> selectListByParentId(Long parentId) {
        return selectList(MaterialCategoryDO::getParentId, parentId);
    }

    /**
     * 统计某父目录下的直接子节点数量
     *
     * 比 selectList 更轻：删除校验只关心「有没有」，不需要把子节点捞回来。
     *
     * @param parentId 父目录编号
     * @return 子节点数量
     */
    default Long selectCountByParentId(Long parentId) {
        return selectCount(MaterialCategoryDO::getParentId, parentId);
    }

    /**
     * 按编码查询目录（ADR-004 A 类口径下，唯一性校验的唯一入口）
     *
     * **只查未删除的行**：走 MyBatis-Plus 逻辑删除，用于「正常重复」的快速判定。
     *
     * @param code 目录编码
     * @return 目录
     */
    default MaterialCategoryDO selectByCode(String code) {
        return selectOne(MaterialCategoryDO::getCode, code);
    }

    /**
     * 统计某编码被**已逻辑删除**的行占用的数量
     *
     * ========== 历史（W4-P2，问题现场） ==========
     * 当初真库 uk_material_category_code(tenant_id, code) 是**普通唯一索引**，不感知 deleted 列——
     * 逻辑删除（deleted=1）的行**仍然占用**该编码。于是「删掉目录 A(STEEL) 再新建 STEEL」时：
     * {@link #selectByCode} 因逻辑删除过滤而查不到 → Service 校验通过 → INSERT 撞索引 →
     * DuplicateKeyException → 前端只拿到 `code=500 系统异常`，完全无法自助解决。
     * 这个方法在当时的作用是「提前探测并给出可读提示」，避免 500。
     *
     * ========== 现状（W4-P6，ADR-004 落地后） ==========
     * `sql/buildcloud/09-unique-index-realign.sql` 已把该索引降级为**普通索引**
     * （`idx_material_category_code`），「同编码 + 一条活跃 + 若干已删」从此是合法数据，
     * 「删后重建同编码」**能够成功**。因此 Service 层**不再需要用本方法拦截**——
     * 拦下它反而会挡掉 ADR-004 明确要放行的业务动作（目录撤销后重建同编码是常态）。
     * 本方法保留的用途只剩两个：
     * 1. **审计/运维查询**：管理端展示「这个编码历史上被删过几次」；
     * 2. **回归护栏**：W4-P6 的单测用它断言「逻辑删除行确实还在库里」（不是物理删除）。
     *
     * 这条用原生 @Select，因此：
     * - 逻辑删除插件不生效（正是我们要的：能看见已删行）；
     * - 多租户插件仍生效（tenant_id 条件由基座自动追加，无需手写）。
     *
     * @param code 目录编码
     * @return 已删除行数量
     */
    @Select("SELECT COUNT(1) FROM bc_material_category WHERE code = #{code} AND deleted = 1")
    Long selectDeletedCountByCode(@Param("code") String code);

    /**
     * 查询同级下是否有同名目录（排除指定编号）
     *
     * 「同级同名」才是业务上真正要防的重复：不同父节点下允许存在「钢材」子目录
     * （如「主材/钢材」与「周转材/钢材」），只有同一父节点下重名才让前端树出现两个无法区分的节点。
     *
     * @param parentId  父目录编号
     * @param name      目录名称
     * @param excludeId 需要排除的目录编号（更新时排除自身；新增传 null）
     * @return 是否已存在
     */
    default boolean existsByParentIdAndName(Long parentId, String name, Long excludeId) {
        Long count = selectCount(new LambdaQueryWrapperX<MaterialCategoryDO>()
                .eq(MaterialCategoryDO::getParentId, parentId)
                .eq(MaterialCategoryDO::getName, name)
                .neIfPresent(MaterialCategoryDO::getId, excludeId));
        return count != null && count > 0;
    }

}
