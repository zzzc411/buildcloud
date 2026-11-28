package com.buildcloud.material.dal.mysql.material;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.controller.admin.material.vo.MaterialPageReqVO;
import com.buildcloud.material.dal.dataobject.material.MaterialDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 物料 Mapper
 *
 * 继承基座 {@link BaseMapperX}；条件用 {@link LambdaQueryWrapperX} 的 xxxIfPresent 系列。
 *
 * @author buildcloud
 */
@Mapper
public interface MaterialMapper extends BaseMapperX<MaterialDO> {

    /**
     * 物料分页查询（spec §5.2 用例：分页 + 多条件）
     *
     * 排序：先按 id 降序（新建的排在前面，符合维护台账的使用习惯）。
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<MaterialDO> selectPage(MaterialPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MaterialDO>()
                .likeIfPresent(MaterialDO::getMaterialNo, reqVO.getMaterialNo())
                .likeIfPresent(MaterialDO::getName, reqVO.getName())
                .likeIfPresent(MaterialDO::getSpec, reqVO.getSpec())
                .eqIfPresent(MaterialDO::getCategoryId, reqVO.getCategoryId())
                .eqIfPresent(MaterialDO::getStatus, reqVO.getStatus())
                .orderByDesc(MaterialDO::getId));
    }

    /**
     * 统计某物料编码被**已逻辑删除**的行占用的数量（审计/回归查询用）
     *
     * 历史：真库 uk_material_no(tenant_id, material_no) 曾不感知 deleted，逻辑删除行仍占编码，
     * 「删掉 MT-001 再新建 MT-001」会绕过 Service 校验撞索引，前端只看到 500。
     * 该索引已由 `09-unique-index-realign.sql` 降级为普通索引 idx_material_no（ADR-004 A 类），
     * 删后重建同编码现在是**合法且可用**的，Service 层不再用本方法拦截。
     * 详见 {@code MaterialCategoryMapper#selectDeletedCountByCode}。
     *
     * 原生 @Select → 逻辑删除插件不生效（要看见已删行），多租户插件仍自动追加 tenant_id。
     *
     * @param materialNo 物料编码
     * @return 已删除行数量
     */
    @Select("SELECT COUNT(1) FROM bc_material WHERE material_no = #{materialNo} AND deleted = 1")
    Long selectDeletedCountByMaterialNo(@Param("materialNo") String materialNo);

    /**
     * 按物料编码查询（ADR-004 A 类口径下，唯一性校验的唯一入口）
     *
     * @param materialNo 物料编码
     * @return 物料
     */
    default MaterialDO selectByMaterialNo(String materialNo) {
        return selectOne(MaterialDO::getMaterialNo, materialNo);
    }

    /**
     * 统计某目录下的物料数量
     *
     * 目录删除时用不上（目录删除只校验子节点，不拦物料——
     * 物料挂在被删目录下会成为「未归类」，由前端提示重新归类，不阻断删除），
     * 但目录详情/树节点上要展示「该目录下物料数」，故保留。
     *
     * @param categoryId 目录编号
     * @return 物料数量
     */
    default Long selectCountByCategoryId(Long categoryId) {
        return selectCount(MaterialDO::getCategoryId, categoryId);
    }

    /**
     * 按目录编号查询物料列表（导出/批量归类用）
     *
     * @param categoryId 目录编号
     * @return 物料列表
     */
    default List<MaterialDO> selectListByCategoryId(Long categoryId) {
        return selectList(MaterialDO::getCategoryId, categoryId);
    }

}
