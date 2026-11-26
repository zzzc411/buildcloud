package com.buildcloud.project.service.project;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.project.controller.admin.project.vo.ProjectMilestonePageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectMilestoneSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectMilestoneDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import com.buildcloud.project.dal.mysql.project.ProjectMilestoneMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MILESTONE_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MILESTONE_PROJECT_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MILESTONE_WEIGHT_EXCEED;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MILESTONE_WEIGHT_INVALID;

/**
 * 项目里程碑 Service 实现类
 *
 * 精修要点（相对生成器产物）：
 * 1. 补业务校验：项目存在性、单个权重区间、同项目权重合计上限——生成器只做 CRUD；
 * 2. 权重合计校验必须排除自身（更新场景），否则「把 20 改成 20」会被自己算进合计而误报；
 * 3. 不提供跨项目全量列表：里程碑脱离项目没有业务含义，只暴露 selectListByProjectId。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class ProjectMilestoneServiceImpl implements ProjectMilestoneService {

    /**
     * 同一项目下里程碑权重合计上限（百分比）
     */
    private static final BigDecimal MAX_TOTAL_WEIGHT = new BigDecimal("100");

    @Resource
    private ProjectMilestoneMapper projectMilestoneMapper;

    @Resource
    private ProjectMapper projectMapper;

    @Override
    public Long createMilestone(ProjectMilestoneSaveReqVO createReqVO) {
        // 校验项目存在
        validateProjectExists(createReqVO.getProjectId());
        // 校验权重
        validateWeight(createReqVO.getWeight());
        validateWeightTotal(null, createReqVO.getProjectId(), createReqVO.getWeight());
        // 插入
        ProjectMilestoneDO milestone = BeanUtils.toBean(createReqVO, ProjectMilestoneDO.class);
        projectMilestoneMapper.insert(milestone);
        // 返回
        return milestone.getId();
    }

    @Override
    public void updateMilestone(ProjectMilestoneSaveReqVO updateReqVO) {
        // 校验存在
        validateMilestoneExists(updateReqVO.getId());
        // 校验项目存在
        validateProjectExists(updateReqVO.getProjectId());
        // 校验权重
        validateWeight(updateReqVO.getWeight());
        // 权重合计校验：按「变更后」目标项目的权重分布计算，排除自身
        // 说明：里程碑换项目时，源项目的合计只会减少（必然仍合规），故只需校验目标项目
        validateWeightTotal(updateReqVO.getId(), updateReqVO.getProjectId(), updateReqVO.getWeight());
        // 更新
        ProjectMilestoneDO updateObj = BeanUtils.toBean(updateReqVO, ProjectMilestoneDO.class);
        projectMilestoneMapper.updateById(updateObj);
    }

    @Override
    public void deleteMilestone(Long id) {
        // 校验存在
        validateMilestoneExists(id);
        // 删除
        projectMilestoneMapper.deleteById(id);
    }

    @Override
    public void deleteMilestoneListByIds(List<Long> ids) {
        // 删除
        projectMilestoneMapper.deleteByIds(ids);
    }

    @Override
    public ProjectMilestoneDO getMilestone(Long id) {
        return projectMilestoneMapper.selectById(id);
    }

    @Override
    public PageResult<ProjectMilestoneDO> getMilestonePage(ProjectMilestonePageReqVO pageReqVO) {
        return projectMilestoneMapper.selectPage(pageReqVO);
    }

    @Override
    public List<ProjectMilestoneDO> getMilestoneListByProjectId(Long projectId) {
        return projectMilestoneMapper.selectListByProjectId(projectId);
    }

    // ========== 校验方法 ==========

    private ProjectMilestoneDO validateMilestoneExists(Long id) {
        ProjectMilestoneDO milestone = projectMilestoneMapper.selectById(id);
        if (milestone == null) {
            throw exception(PROJECT_MILESTONE_NOT_EXISTS);
        }
        return milestone;
    }

    /**
     * 校验项目存在
     *
     * 说明：项目被逻辑删除后 selectById 返回 null，此处即拦截「往已删项目挂里程碑」。
     * 租户条件由基座多租户插件自动追加，此处无需显式过滤 tenant_id。
     *
     * @param projectId 项目编号
     */
    private void validateProjectExists(Long projectId) {
        if (projectMapper.selectById(projectId) == null) {
            throw exception(PROJECT_MILESTONE_PROJECT_NOT_EXISTS);
        }
    }

    /**
     * 校验单个权重区间：0 <= weight <= 100
     *
     * VO 层已有 @DecimalMin/@DecimalMax，这里再校验一次是防「Service 被内部代码直接调用」绕过 VO 校验；
     * 同时把错误码统一到 1-100-010-002，避免前端拿到框架校验异常与业务异常两种口径。
     *
     * @param weight 权重
     */
    private void validateWeight(BigDecimal weight) {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) < 0
                || weight.compareTo(MAX_TOTAL_WEIGHT) > 0) {
            throw exception(PROJECT_MILESTONE_WEIGHT_INVALID);
        }
    }

    /**
     * 校验同一项目下权重合计不超过 100
     *
     * @param excludeId 需要排除的里程碑编号（更新时排除自身，新增传 null）
     * @param projectId 项目编号
     * @param weight    本次要写入的权重
     */
    private void validateWeightTotal(Long excludeId, Long projectId, BigDecimal weight) {
        BigDecimal total = projectMilestoneMapper.selectListByProjectIdExcludeId(projectId, excludeId).stream()
                .map(ProjectMilestoneDO::getWeight)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal newTotal = total.add(weight);
        if (newTotal.compareTo(MAX_TOTAL_WEIGHT) > 0) {
            throw exception(PROJECT_MILESTONE_WEIGHT_EXCEED, newTotal.stripTrailingZeros().toPlainString());
        }
    }

}
