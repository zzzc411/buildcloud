package com.buildcloud.material.service.material;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.material.controller.admin.material.vo.MaterialPageReqVO;
import com.buildcloud.material.controller.admin.material.vo.MaterialSaveReqVO;
import com.buildcloud.material.dal.dataobject.material.MaterialDO;
import com.buildcloud.material.dal.mysql.category.MaterialCategoryMapper;
import com.buildcloud.material.dal.mysql.material.MaterialMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_CATEGORY_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.MATERIAL_NO_DUPLICATE;

/**
 * 物料 Service 实现类
 *
 * 精修要点（相对生成器产物）：
 * 1. 补 material_no 唯一校验（对应真库 uk_material_no）——生成器只做 CRUD；
 * 2. 补 categoryId 存在性校验：填了目录就必须真实存在，否则物料挂在不存在的目录下，
 *    目录树删掉后这些物料的「已归类」状态是假的；
 * 3. 更新时唯一校验必须排除自身，否则"编码不变"会被自己判为重复。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class MaterialServiceImpl implements MaterialService {

    @Resource
    private MaterialMapper materialMapper;

    @Resource
    private MaterialCategoryMapper materialCategoryMapper;

    @Override
    public Long createMaterial(MaterialSaveReqVO createReqVO) {
        // 校验物料编码唯一（新增不排除自身）
        validateMaterialNoUnique(null, createReqVO.getMaterialNo());
        // 校验所属目录存在（categoryId 可为空，表示未归类）
        validateCategoryExists(createReqVO.getCategoryId());
        // 插入
        MaterialDO material = BeanUtils.toBean(createReqVO, MaterialDO.class);
        materialMapper.insert(material);
        // 返回
        return material.getId();
    }

    @Override
    public void updateMaterial(MaterialSaveReqVO updateReqVO) {
        // 校验存在
        validateMaterialExists(updateReqVO.getId());
        // 校验物料编码唯一（排除自身）
        validateMaterialNoUnique(updateReqVO.getId(), updateReqVO.getMaterialNo());
        // 校验所属目录存在
        validateCategoryExists(updateReqVO.getCategoryId());
        // 更新
        MaterialDO updateObj = BeanUtils.toBean(updateReqVO, MaterialDO.class);
        materialMapper.updateById(updateObj);
    }

    @Override
    public void deleteMaterial(Long id) {
        // 校验存在
        validateMaterialExists(id);
        // 删除
        materialMapper.deleteById(id);
    }

    @Override
    public MaterialDO getMaterial(Long id) {
        return materialMapper.selectById(id);
    }

    @Override
    public PageResult<MaterialDO> getMaterialPage(MaterialPageReqVO pageReqVO) {
        return materialMapper.selectPage(pageReqVO);
    }

    // ========== 校验方法 ==========

    private void validateMaterialExists(Long id) {
        if (materialMapper.selectById(id) == null) {
            throw exception(MATERIAL_NOT_EXISTS);
        }
    }

    /**
     * 校验物料编码在**未删除行**中唯一（ADR-004 A 类口径）
     *
     * 唯一性只约束活跃行（物料停用/录错后可删除再用同编码重建，是现场常态）；
     * 数据库侧的 uk_material_no 已由 `09-unique-index-realign.sql` 降级为普通索引
     * （`idx_material_no`），不再承担唯一性，故这里**不做**「探测已删行」的拦截。
     * 详见 {@code MaterialCategoryServiceImpl#validateCodeUnique} 的历史提醒（L3-7）。
     *
     * @param id         物料编号（更新时排除自身；新增传 null）
     * @param materialNo 物料编码
     */
    private void validateMaterialNoUnique(Long id, String materialNo) {
        MaterialDO existing = materialMapper.selectByMaterialNo(materialNo);
        if (existing != null && !Objects.equals(existing.getId(), id)) {
            throw exception(MATERIAL_NO_DUPLICATE);
        }
    }

    /**
     * 校验所属目录存在
     *
     * categoryId 为空表示「未归类」，是合法状态（spec §5.2 未要求物料必须归类），
     * 故只在非空时才校验。
     *
     * @param categoryId 目录编号
     */
    private void validateCategoryExists(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        if (materialCategoryMapper.selectById(categoryId) == null) {
            throw exception(MATERIAL_CATEGORY_NOT_EXISTS);
        }
    }

}
