package com.buildcloud.project.service.project;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.project.controller.admin.project.vo.ProjectUpdateStatusReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import com.buildcloud.project.enums.project.ProjectStatusEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.Set;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomInteger;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomString;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_STATUS_INVALID;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_STATUS_TRANSITION_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectStatusServiceImpl} 的单元测试类 —— **状态机全路径 + 非法流转断言**
 *
 * 状态机（spec v1.2 §5.1）：筹备(0) → 在建(1) ⇄ 停工(2) → 竣工(3)
 *
 * 合法流转（5 条）：
 * - 筹备(0) → 在建(1)
 * - 在建(1) → 停工(2)
 * - 停工(2) → 在建(1)   （复工）
 * - 在建(1) → 竣工(3)
 * - 停工(2) → 竣工(3)
 *
 * 非法流转覆盖（断言抛 PROJECT_STATUS_TRANSITION_INVALID）：
 * - 筹备(0) → 竣工(3)：**跳级**
 * - 筹备(0) → 停工(2)：源头不可停工
 * - 竣工(3) → 在建(1) / 停工(2) / 筹备(0)：终态不可回退
 * - 任意同状态重复流转（0→0 / 1→1 / 2→2 / 3→3）：空操作，明确拒绝
 *
 * @author buildcloud
 */
@Import(ProjectStatusServiceImpl.class)
public class ProjectStatusServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ProjectStatusServiceImpl projectStatusService;

    @Resource
    private ProjectMapper projectMapper;

    // ========== 合法流转（全路径） ==========

    @Test
    public void testUpdateStatus_preparingToConstructing() {
        // mock 数据：筹备中的项目
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus());
        assertNull(project.getActualStartDate());

        // 调用
        projectStatusService.updateProjectStatus(buildReqVO(project.getId(), ProjectStatusEnum.CONSTRUCTING));

        // 断言：状态变为在建
        ProjectDO updated = projectMapper.selectById(project.getId());
        assertEquals(ProjectStatusEnum.CONSTRUCTING.getStatus(), updated.getStatus());
        // 副作用：实际开工日期自动回填（工程语义）
        assertEquals(LocalDate.now(), updated.getActualStartDate());
    }

    @Test
    public void testUpdateStatus_constructingToSuspended() {
        // mock 数据
        ProjectDO project = insertProject(ProjectStatusEnum.CONSTRUCTING.getStatus());

        // 调用
        projectStatusService.updateProjectStatus(buildReqVO(project.getId(), ProjectStatusEnum.SUSPENDED));

        // 断言
        assertEquals(ProjectStatusEnum.SUSPENDED.getStatus(), projectMapper.selectById(project.getId()).getStatus());
    }

    @Test
    public void testUpdateStatus_suspendedToConstructing_resume() {
        // mock 数据：停工项目，实际开工日期已有值（不应被复工覆盖）
        ProjectDO project = insertProject(ProjectStatusEnum.SUSPENDED.getStatus());
        LocalDate originalStartDate = LocalDate.of(2026, 3, 1);
        ProjectDO update = new ProjectDO();
        update.setId(project.getId());
        update.setActualStartDate(originalStartDate);
        projectMapper.updateById(update);

        // 调用：复工
        projectStatusService.updateProjectStatus(buildReqVO(project.getId(), ProjectStatusEnum.CONSTRUCTING));

        // 断言：状态回到在建，且原实际开工日期不被覆盖
        ProjectDO updated = projectMapper.selectById(project.getId());
        assertEquals(ProjectStatusEnum.CONSTRUCTING.getStatus(), updated.getStatus());
        assertEquals(originalStartDate, updated.getActualStartDate());
    }

    @Test
    public void testUpdateStatus_constructingToCompleted() {
        // mock 数据
        ProjectDO project = insertProject(ProjectStatusEnum.CONSTRUCTING.getStatus());

        // 调用
        projectStatusService.updateProjectStatus(buildReqVO(project.getId(), ProjectStatusEnum.COMPLETED));

        // 断言：状态竣工 + 实际竣工日期回填
        ProjectDO updated = projectMapper.selectById(project.getId());
        assertEquals(ProjectStatusEnum.COMPLETED.getStatus(), updated.getStatus());
        assertEquals(LocalDate.now(), updated.getActualEndDate());
    }

    @Test
    public void testUpdateStatus_suspendedToCompleted() {
        // mock 数据：停工后直接验收
        ProjectDO project = insertProject(ProjectStatusEnum.SUSPENDED.getStatus());

        // 调用
        projectStatusService.updateProjectStatus(buildReqVO(project.getId(), ProjectStatusEnum.COMPLETED));

        // 断言
        assertEquals(ProjectStatusEnum.COMPLETED.getStatus(), projectMapper.selectById(project.getId()).getStatus());
    }

    @Test
    public void testUpdateStatus_fullLifecycle() {
        // mock 数据：从筹备开始
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus());

        // 调用：筹备 → 在建 → 停工 → 在建 → 竣工（完整闭环连跑）
        projectStatusService.updateProjectStatus(buildReqVO(project.getId(), ProjectStatusEnum.CONSTRUCTING));
        projectStatusService.updateProjectStatus(buildReqVO(project.getId(), ProjectStatusEnum.SUSPENDED));
        projectStatusService.updateProjectStatus(buildReqVO(project.getId(), ProjectStatusEnum.CONSTRUCTING));
        projectStatusService.updateProjectStatus(buildReqVO(project.getId(), ProjectStatusEnum.COMPLETED));

        // 断言：终态竣工
        assertEquals(ProjectStatusEnum.COMPLETED.getStatus(), projectMapper.selectById(project.getId()).getStatus());
    }

    // ========== 非法流转（断言被拒） ==========

    @Test
    public void testUpdateStatus_preparingToCompleted_rejected() {
        // mock 数据：筹备中
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus());

        // 调用，并断言异常：筹备 → 竣工 跳级必须被拒（错误提示带源/目标状态名）
        assertServiceException(
                () -> projectStatusService.updateProjectStatus(
                        buildReqVO(project.getId(), ProjectStatusEnum.COMPLETED)),
                PROJECT_STATUS_TRANSITION_INVALID, "筹备", "竣工");
        // 断言：状态没有被改动
        assertEquals(ProjectStatusEnum.PREPARING.getStatus(), projectMapper.selectById(project.getId()).getStatus());
    }

    @Test
    public void testUpdateStatus_preparingToSuspended_rejected() {
        // mock 数据：筹备中
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus());

        // 调用，并断言异常：筹备阶段没有「停工」语义
        assertServiceException(
                () -> projectStatusService.updateProjectStatus(
                        buildReqVO(project.getId(), ProjectStatusEnum.SUSPENDED)),
                PROJECT_STATUS_TRANSITION_INVALID, "筹备", "停工");
    }

    @Test
    public void testUpdateStatus_completedToConstructing_rejected() {
        // mock 数据：已竣工（终态）
        ProjectDO project = insertProject(ProjectStatusEnum.COMPLETED.getStatus());

        // 调用，并断言异常：竣工不可回退到在建
        assertServiceException(
                () -> projectStatusService.updateProjectStatus(
                        buildReqVO(project.getId(), ProjectStatusEnum.CONSTRUCTING)),
                PROJECT_STATUS_TRANSITION_INVALID, "竣工", "在建");
    }

    @Test
    public void testUpdateStatus_completedToSuspended_rejected() {
        // mock 数据：已竣工
        ProjectDO project = insertProject(ProjectStatusEnum.COMPLETED.getStatus());

        // 调用，并断言异常
        assertServiceException(
                () -> projectStatusService.updateProjectStatus(
                        buildReqVO(project.getId(), ProjectStatusEnum.SUSPENDED)),
                PROJECT_STATUS_TRANSITION_INVALID, "竣工", "停工");
    }

    @Test
    public void testUpdateStatus_completedToPreparing_rejected() {
        // mock 数据：已竣工
        ProjectDO project = insertProject(ProjectStatusEnum.COMPLETED.getStatus());

        // 调用，并断言异常
        assertServiceException(
                () -> projectStatusService.updateProjectStatus(
                        buildReqVO(project.getId(), ProjectStatusEnum.PREPARING)),
                PROJECT_STATUS_TRANSITION_INVALID, "竣工", "筹备");
    }

    @Test
    public void testUpdateStatus_sameStatus_rejected() {
        // mock 数据：在建项目
        ProjectDO project = insertProject(ProjectStatusEnum.CONSTRUCTING.getStatus());

        // 调用，并断言异常：同状态重复流转是空操作，明确拒绝（便于发现调用方逻辑错误）
        assertServiceException(
                () -> projectStatusService.updateProjectStatus(
                        buildReqVO(project.getId(), ProjectStatusEnum.CONSTRUCTING)),
                PROJECT_STATUS_TRANSITION_INVALID, "在建", "在建");
    }

    @Test
    public void testUpdateStatus_invalidTargetStatus() {
        // mock 数据
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus());

        // 调用，并断言异常：目标状态不在枚举范围内（如 9）
        assertServiceException(
                () -> projectStatusService.updateProjectStatus(buildReqVO(project.getId(), 9)),
                PROJECT_STATUS_INVALID);
    }

    @Test
    public void testUpdateStatus_projectNotExists() {
        // 准备参数：项目编号不存在
        ProjectUpdateStatusReqVO reqVO = buildReqVO(randomInteger().longValue() + 100000L,
                ProjectStatusEnum.CONSTRUCTING);

        // 调用，并断言异常
        assertServiceException(() -> projectStatusService.updateProjectStatus(reqVO), PROJECT_NOT_EXISTS);
    }

    // ========== 纯函数：isTransitionAllowed / getNextStatusList ==========

    @Test
    public void testIsTransitionAllowed_legal() {
        assertTrue(projectStatusService.isTransitionAllowed(
                ProjectStatusEnum.PREPARING.getStatus(), ProjectStatusEnum.CONSTRUCTING.getStatus()));
        assertTrue(projectStatusService.isTransitionAllowed(
                ProjectStatusEnum.CONSTRUCTING.getStatus(), ProjectStatusEnum.SUSPENDED.getStatus()));
        assertTrue(projectStatusService.isTransitionAllowed(
                ProjectStatusEnum.SUSPENDED.getStatus(), ProjectStatusEnum.CONSTRUCTING.getStatus()));
        assertTrue(projectStatusService.isTransitionAllowed(
                ProjectStatusEnum.CONSTRUCTING.getStatus(), ProjectStatusEnum.COMPLETED.getStatus()));
        assertTrue(projectStatusService.isTransitionAllowed(
                ProjectStatusEnum.SUSPENDED.getStatus(), ProjectStatusEnum.COMPLETED.getStatus()));
    }

    @Test
    public void testIsTransitionAllowed_illegal() {
        // 筹备 → 竣工（跳级）
        assertFalse(projectStatusService.isTransitionAllowed(
                ProjectStatusEnum.PREPARING.getStatus(), ProjectStatusEnum.COMPLETED.getStatus()));
        // 筹备 → 停工
        assertFalse(projectStatusService.isTransitionAllowed(
                ProjectStatusEnum.PREPARING.getStatus(), ProjectStatusEnum.SUSPENDED.getStatus()));
        // 竣工 → 任何（终态）
        assertFalse(projectStatusService.isTransitionAllowed(
                ProjectStatusEnum.COMPLETED.getStatus(), ProjectStatusEnum.PREPARING.getStatus()));
        assertFalse(projectStatusService.isTransitionAllowed(
                ProjectStatusEnum.COMPLETED.getStatus(), ProjectStatusEnum.CONSTRUCTING.getStatus()));
        // 同一状态
        assertFalse(projectStatusService.isTransitionAllowed(
                ProjectStatusEnum.CONSTRUCTING.getStatus(), ProjectStatusEnum.CONSTRUCTING.getStatus()));
        // null 入参
        assertFalse(projectStatusService.isTransitionAllowed(null, ProjectStatusEnum.CONSTRUCTING.getStatus()));
        assertFalse(projectStatusService.isTransitionAllowed(ProjectStatusEnum.CONSTRUCTING.getStatus(), null));
    }

    @Test
    public void testGetNextStatusList() {
        // 筹备 → 仅在建
        Set<ProjectStatusEnum> fromPreparing = projectStatusService.getNextStatusList(
                ProjectStatusEnum.PREPARING.getStatus());
        assertEquals(1, fromPreparing.size());
        assertTrue(fromPreparing.contains(ProjectStatusEnum.CONSTRUCTING));

        // 在建 → 停工、竣工
        Set<ProjectStatusEnum> fromConstructing = projectStatusService.getNextStatusList(
                ProjectStatusEnum.CONSTRUCTING.getStatus());
        assertEquals(2, fromConstructing.size());
        assertTrue(fromConstructing.contains(ProjectStatusEnum.SUSPENDED));
        assertTrue(fromConstructing.contains(ProjectStatusEnum.COMPLETED));

        // 停工 → 在建、竣工
        Set<ProjectStatusEnum> fromSuspended = projectStatusService.getNextStatusList(
                ProjectStatusEnum.SUSPENDED.getStatus());
        assertEquals(2, fromSuspended.size());
        assertTrue(fromSuspended.contains(ProjectStatusEnum.CONSTRUCTING));
        assertTrue(fromSuspended.contains(ProjectStatusEnum.COMPLETED));

        // 竣工 → 无（终态）
        Set<ProjectStatusEnum> fromCompleted = projectStatusService.getNextStatusList(
                ProjectStatusEnum.COMPLETED.getStatus());
        assertNotNull(fromCompleted);
        assertTrue(fromCompleted.isEmpty());

        // 未知状态 → 无
        assertTrue(projectStatusService.getNextStatusList(99).isEmpty());
    }

    // ========== 测试数据构造 ==========

    private ProjectDO insertProject(Integer status) {
        ProjectDO project = randomPojo(ProjectDO.class, o -> {
            o.setProjectNo("BC-ST-" + randomString());
            o.setProjectName("状态机测试项目");
            o.setStatus(status);
            o.setActualStartDate(null);
            o.setActualEndDate(null);
        });
        projectMapper.insert(project);
        return project;
    }

    private ProjectUpdateStatusReqVO buildReqVO(Long projectId, ProjectStatusEnum targetStatus) {
        return buildReqVO(projectId, targetStatus.getStatus());
    }

    private ProjectUpdateStatusReqVO buildReqVO(Long projectId, Integer status) {
        ProjectUpdateStatusReqVO reqVO = new ProjectUpdateStatusReqVO();
        reqVO.setId(projectId);
        reqVO.setStatus(status);
        return reqVO;
    }

}
