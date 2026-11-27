package com.buildcloud.material.service.material;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.material.controller.admin.material.vo.MaterialPageReqVO;
import com.buildcloud.material.controller.admin.material.vo.MaterialSaveReqVO;
import com.buildcloud.material.dal.dataobject.material.MaterialDO;
import jakarta.validation.Valid;

/**
 * 物料 Service 接口
 *
 * 业务边界（spec v1.2 §5.2 用例「物料维护」）：
 * 1. material_no 租户内唯一（错误码 MATERIAL_NO_DUPLICATE）；
 * 2. category_id 若填写则必须指向存在的目录（否则物料成为挂在不存在的目录下的孤儿）；
 * 3. 按 category_id 筛选 + name/material_no/spec 模糊的多条件分页。
 *
 * @author buildcloud
 */
public interface MaterialService {

    /**
     * 创建物料
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createMaterial(@Valid MaterialSaveReqVO createReqVO);

    /**
     * 更新物料
     *
     * @param updateReqVO 更新信息
     */
    void updateMaterial(@Valid MaterialSaveReqVO updateReqVO);

    /**
     * 删除物料
     *
     * @param id 编号
     */
    void deleteMaterial(Long id);

    /**
     * 获得物料
     *
     * @param id 编号
     * @return 物料
     */
    MaterialDO getMaterial(Long id);

    /**
     * 获得物料分页
     *
     * @param pageReqVO 分页查询
     * @return 物料分页
     */
    PageResult<MaterialDO> getMaterialPage(MaterialPageReqVO pageReqVO);

}
