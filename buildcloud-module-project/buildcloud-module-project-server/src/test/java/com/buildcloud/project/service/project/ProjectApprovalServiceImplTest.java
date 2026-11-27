package com.buildcloud.project.service.project;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.buildcloud.project.controller.admin.project.vo.ProjectApprovalStatusRespVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import com.buildcloud.project.enums.project.ProjectApprovalStatusEnum;
import com.buildcloud.project.enums.project.ProjectStatusEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.Map;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomString;
import static cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum.APPROVE;
import static cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum.REJECT;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_APPROVAL_ALREADY_RUNNING;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_APPROVAL_START_FAILED;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_APPROVAL_STATUS_NOT_PREPARE;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_NOT_EXISTS;
import static com.buildcloud.project.enums.project.ProjectBpmConstants.PROCESS_KEY_PROJECT_APPROVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectApprovalServiceImpl} 的单元测试类 —— 立项审批与 BPM 集成
 *
 * 覆盖范围（= 本 Phase 验收清单的 5 条核心断言）：
 * 1. 提交成功 → 回写 processInstanceId + approvalStatus=审批中，且 Feign 入参正确（key/businessKey/变量）；
 * 2. 通过回调 → 项目状态 筹备(0) → 在建(1)、approvalStatus=2；
 * 3. 驳回回调 → 项目状态**保持**筹备(0)、approvalStatus=3；
 * 4. 重复回调幂等 → 不重复执行状态机（不会因 1→1 同状态流转抛错），结果仍为终态；
 * 5. 非法状态提交被拒 → 非筹备状态 / 审批中 两种情况各抛对应错误码。
 *
 * 测试手法：{@code @MockBean} 替换 {@link BpmProcessInstanceApi}（Feign 代理无法在单测里真调），
 * 数据库用 H2 真表（BaseDbUnitTest），因此「回写是否真的落库」是被真实验证的，
 * 而不是靠 Mock 断言自说自话。
 *
 * @author buildcloud
 */
@Import({ProjectApprovalServiceImpl.class, ProjectStatusServiceImpl.class})
public class ProjectApprovalServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ProjectApprovalServiceImpl projectApprovalService;

    @Resource
    private ProjectStatusServiceImpl projectStatusService;

    @Resource
    private ProjectMapper projectMapper;

    /**
     * BPM 流程实例 RPC 的 Mock
     *
     * 用 @MockBean 而不是手写 stub：需要让 Spring 容器里存在该 Bean 供 @Resource 注入，
     * 同时又能控制返回值。
     */
    @MockBean
    private BpmProcessInstanceApi processInstanceApi;

    // ========== 1. 提交审批：成功回写 ==========

    @Test
    public void testSubmitApproval_success() {
        // mock 数据：筹备中、未发起过审批的项目
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus(),
                ProjectApprovalStatusEnum.NOT_START.getStatus(), null, null);
        // mock BPM 返回流程实例编号
        String processInstanceId = PROCESS_KEY_PROJECT_APPROVE + ":1:" + randomString();
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(processInstanceId));

        // 调用
        String result = projectApprovalService.submitApproval(project.getId());

        // 断言 1：返回的就是 BPM 给的实例编号
        assertEquals(processInstanceId, result);

        // 断言 2：库里两列都被正确回写
        ProjectDO updated = projectMapper.selectById(project.getId());
        assertEquals(processInstanceId, updated.getProcessInstanceId());
        assertEquals(ProjectApprovalStatusEnum.RUNNING.getStatus(), updated.getApprovalStatus());
        // 断言 3：项目业务状态**不变**（发起审批不等于已开工，仍在筹备）
        assertEquals(ProjectStatusEnum.PREPARING.getStatus(), updated.getStatus());

        // 断言 4：Feign 入参正确 —— 这是「别臆造 BPM API」的回归护栏
        ArgumentCaptor<BpmProcessInstanceCreateReqDTO> captor =
                ArgumentCaptor.forClass(BpmProcessInstanceCreateReqDTO.class);
        verify(processInstanceApi).createProcessInstance(any(), captor.capture());
        BpmProcessInstanceCreateReqDTO reqDTO = captor.getValue();
        assertEquals(PROCESS_KEY_PROJECT_APPROVE, reqDTO.getProcessDefinitionKey());
        assertEquals(String.valueOf(project.getId()), reqDTO.getBusinessKey());
        assertNotNull(reqDTO.getVariables());
        assertEquals(project.getId(), reqDTO.getVariables().get("projectId"));
        assertEquals(project.getProjectNo(), reqDTO.getVariables().get("projectNo"));
        assertEquals(project.getProjectName(), reqDTO.getVariables().get("projectName"));
        // 自选审批人不使用（审批人由 BPMN 的用户组策略决定）
        assertNull(reqDTO.getStartUserSelectAssignees());
    }

    @Test
    public void testSubmitApproval_bpmReturnsBlankId() {
        // BPM 返回空实例编号 → 必须抛错让事务回滚，不能留下「审批中但无流程」的死状态
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus(),
                ProjectApprovalStatusEnum.NOT_START.getStatus(), null, null);
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(null));

        assertServiceException(() -> projectApprovalService.submitApproval(project.getId()),
                PROJECT_APPROVAL_START_FAILED);

        // 事务已回滚：approvalStatus 仍是「未开始」
        ProjectDO unchanged = projectMapper.selectById(project.getId());
        assertEquals(ProjectApprovalStatusEnum.NOT_START.getStatus(), unchanged.getApprovalStatus());
    }

    // ========== 2. 通过回调 → 项目在建 ==========

    @Test
    public void testHandleApprovalResult_approve_projectBecomesConstructing() {
        String processInstanceId = PROCESS_KEY_PROJECT_APPROVE + ":1:" + randomString();
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus(),
                ProjectApprovalStatusEnum.RUNNING.getStatus(), processInstanceId, null);

        // 调用：审批通过
        projectApprovalService.handleApprovalResult(project.getId(), processInstanceId, true, "同意立项");

        // 断言 1：审批状态 = 通过(2)
        ProjectDO updated = projectMapper.selectById(project.getId());
        assertEquals(ProjectApprovalStatusEnum.APPROVE.getStatus(), updated.getApprovalStatus());
        // 断言 2：项目业务状态 筹备(0) → 在建(1)（**经状态机**，不是直接 update）
        assertEquals(ProjectStatusEnum.CONSTRUCTING.getStatus(), updated.getStatus());
        // 断言 3：状态机的副作用生效——实际开工日期被回填（证明真的走了 ProjectStatusService）
        assertNotNull(updated.getActualStartDate());
    }

    @Test
    public void testHandleApprovalResult_approve_statusValuesAlignWithBpmEnum() {
        // 回归护栏：本域枚举与基座 BpmProcessInstanceStatusEnum 的数值必须一一对齐，
        // 否则回调时 approval_status 会写错位（这是最容易静默出错的点）
        assertEquals(APPROVE.getStatus(), ProjectApprovalStatusEnum.APPROVE.getStatus());
        assertEquals(REJECT.getStatus(), ProjectApprovalStatusEnum.REJECT.getStatus());
        assertEquals(-1, ProjectApprovalStatusEnum.NOT_START.getStatus());
        assertEquals(1, ProjectApprovalStatusEnum.RUNNING.getStatus());
        assertEquals(4, ProjectApprovalStatusEnum.CANCEL.getStatus());
    }

    // ========== 3. 驳回回调 → 项目保持筹备 ==========

    @Test
    public void testHandleApprovalResult_reject_projectStaysPreparing() {
        String processInstanceId = PROCESS_KEY_PROJECT_APPROVE + ":1:" + randomString();
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus(),
                ProjectApprovalStatusEnum.RUNNING.getStatus(), processInstanceId, null);

        // 调用：审批驳回
        projectApprovalService.handleApprovalResult(project.getId(), processInstanceId, false, "资料不全");

        // 断言 1：审批状态 = 不通过(3)
        ProjectDO updated = projectMapper.selectById(project.getId());
        assertEquals(ProjectApprovalStatusEnum.REJECT.getStatus(), updated.getApprovalStatus());
        // 断言 2：项目业务状态**保持筹备**（驳回不该把项目推进到在建，也不该改动状态机）
        assertEquals(ProjectStatusEnum.PREPARING.getStatus(), updated.getStatus());
        // 断言 3：驳回不触发开工日期回填
        assertNull(updated.getActualStartDate());
        // 断言 4：流程实例编号保留（审批历史可追溯，不因驳回清空）
        assertEquals(processInstanceId, updated.getProcessInstanceId());
    }

    // ========== 4. 重复回调幂等 ==========

    @Test
    public void testHandleApprovalResult_repeatApproveIsIdempotent() {
        String processInstanceId = PROCESS_KEY_PREFIX + randomString();
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus(),
                ProjectApprovalStatusEnum.RUNNING.getStatus(), processInstanceId, null);

        // 第一次通过：筹备 → 在建
        projectApprovalService.handleApprovalResult(project.getId(), processInstanceId, true, "同意立项");
        ProjectDO afterFirst = projectMapper.selectById(project.getId());
        assertEquals(ProjectStatusEnum.CONSTRUCTING.getStatus(), afterFirst.getStatus());
        LocalDate firstStartDate = afterFirst.getActualStartDate();

        // 第二次同样的通过回调：必须安全返回，**不能**因「在建 → 在建」同状态流转抛
        // PROJECT_STATUS_TRANSITION_INVALID（这就是幂等逻辑存在的意义）
        projectApprovalService.handleApprovalResult(project.getId(), processInstanceId, true, "同意立项");

        ProjectDO afterSecond = projectMapper.selectById(project.getId());
        assertEquals(ProjectApprovalStatusEnum.APPROVE.getStatus(), afterSecond.getApprovalStatus());
        assertEquals(ProjectStatusEnum.CONSTRUCTING.getStatus(), afterSecond.getStatus());
        // 实际开工日期不被二次覆盖
        assertEquals(firstStartDate, afterSecond.getActualStartDate());
    }

    @Test
    public void testHandleApprovalResult_staleProcessInstanceIgnored() {
        // 场景：项目驳回后重新发起新流程，旧实例的迟到回调到达 → 必须丢弃，不能按旧结果覆盖
        String currentProcessInstanceId = "project-approve:2:" + randomString();
        String staleProcessInstanceId = "project-approve:1:" + randomString();
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus(),
                ProjectApprovalStatusEnum.RUNNING.getStatus(), currentProcessInstanceId, null);

        // 旧实例发来「通过」
        projectApprovalService.handleApprovalResult(project.getId(), staleProcessInstanceId, true, "旧流程");

        // 断言：什么都没变（状态仍是审批中、项目仍筹备）
        ProjectDO updated = projectMapper.selectById(project.getId());
        assertEquals(ProjectApprovalStatusEnum.RUNNING.getStatus(), updated.getApprovalStatus());
        assertEquals(ProjectStatusEnum.PREPARING.getStatus(), updated.getStatus());
    }

    @Test
    public void testHandleApprovalResult_projectNotExistsIgnored() {
        // 业务单据不存在：记日志丢弃，**不抛异常**（抛异常会让 bpm 侧 HTTP 调用失败并回滚流程结束事务）
        projectApprovalService.handleApprovalResult(999999L, "project-approve:1:x", true, "同意");
        // 无异常即通过
        assertTrue(true);
    }

    // ========== 5. 非法状态提交被拒 ==========

    @Test
    public void testSubmitApproval_projectNotExists() {
        assertServiceException(() -> projectApprovalService.submitApproval(999999L), PROJECT_NOT_EXISTS);
        // 项目不存在时不应调 BPM
        verify(processInstanceApi, never()).createProcessInstance(any(), any());
    }

    @Test
    public void testSubmitApproval_projectAlreadyConstructing() {
        // 已在建的项目不能再走立项审批
        ProjectDO project = insertProject(ProjectStatusEnum.CONSTRUCTING.getStatus(),
                ProjectApprovalStatusEnum.NOT_START.getStatus(), null, null);

        assertServiceException(() -> projectApprovalService.submitApproval(project.getId()),
                PROJECT_APPROVAL_STATUS_NOT_PREPARE, ProjectStatusEnum.CONSTRUCTING.getName());

        verify(processInstanceApi, never()).createProcessInstance(any(), any());
        // 状态未被误改
        ProjectDO unchanged = projectMapper.selectById(project.getId());
        assertEquals(ProjectStatusEnum.CONSTRUCTING.getStatus(), unchanged.getStatus());
        assertEquals(ProjectApprovalStatusEnum.NOT_START.getStatus(), unchanged.getApprovalStatus());
    }

    @Test
    public void testSubmitApproval_alreadyRunning() {
        // 已有审批中的流程 → 拒绝重复提交（防同一项目并发两个实例）
        String existing = PROCESS_KEY_PROJECT_APPROVE + ":1:" + randomString();
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus(),
                ProjectApprovalStatusEnum.RUNNING.getStatus(), existing, null);

        assertServiceException(() -> projectApprovalService.submitApproval(project.getId()),
                PROJECT_APPROVAL_ALREADY_RUNNING, existing);

        verify(processInstanceApi, never()).createProcessInstance(any(), any());
    }

    @Test
    public void testSubmitApproval_afterRejectCanResubmit() {
        // 驳回是终态，允许修改后重新发起（这是业务常态，不能被「防重复提交」挡住）
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus(),
                ProjectApprovalStatusEnum.REJECT.getStatus(), "project-approve:1:old", null);
        String newInstanceId = PROCESS_KEY_PROJECT_APPROVE + ":2:" + randomString();
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(newInstanceId));

        String result = projectApprovalService.submitApproval(project.getId());

        assertEquals(newInstanceId, result);
        ProjectDO updated = projectMapper.selectById(project.getId());
        // 流程实例编号被覆盖为最新（老实例成为历史，可撤回）
        assertEquals(newInstanceId, updated.getProcessInstanceId());
        assertEquals(ProjectApprovalStatusEnum.RUNNING.getStatus(), updated.getApprovalStatus());
    }

    // ========== 附加：查询审批状态 ==========

    @Test
    public void testGetApprovalStatus_running() {
        String processInstanceId = PROCESS_KEY_PROJECT_APPROVE + ":1:" + randomString();
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus(),
                ProjectApprovalStatusEnum.RUNNING.getStatus(), processInstanceId, null);

        ProjectApprovalStatusRespVO status = projectApprovalService.getApprovalStatus(project.getId());

        assertEquals(project.getId(), status.getProjectId());
        assertEquals(processInstanceId, status.getProcessInstanceId());
        assertEquals(ProjectApprovalStatusEnum.RUNNING.getStatus(), status.getApprovalStatus());
        assertEquals("审批中", status.getApprovalStatusName());
        assertEquals(ProjectStatusEnum.PREPARING.getStatus(), status.getProjectStatus());
        assertEquals("筹备", status.getProjectStatusName());
        // reason 是面向操作者的提示，不同状态下必须不同
        assertEquals("已提交审批，等待审批人处理", status.getReason());
    }

    @Test
    public void testGetApprovalStatus_notStarted_nullApprovalStatusFallback() {
        // 历史数据 approvalStatus 为 null 时兜底成「未开始」，不能让前端拿到 null
        ProjectDO project = insertProject(ProjectStatusEnum.PREPARING.getStatus(), null, null, null);
        projectMapper.updateById(new ProjectDO().setId(project.getId()).setApprovalStatus(null));

        ProjectApprovalStatusRespVO status = projectApprovalService.getApprovalStatus(project.getId());

        assertEquals(ProjectApprovalStatusEnum.NOT_START.getStatus(), status.getApprovalStatus());
        assertEquals("尚未提交立项审批", status.getReason());
    }

    @Test
    public void testGetApprovalStatus_projectNotExists() {
        assertServiceException(() -> projectApprovalService.getApprovalStatus(999999L), PROJECT_NOT_EXISTS);
    }

    // ========== 测试数据构造 ==========

    /**
     * project-approve 的 key 前缀（与 ProjectBpmConstants 保持一致，此处独立写死一次作为交叉校验）
     */
    private static final String PROCESS_KEY_PREFIX = "project-approve:1:";

    private ProjectDO insertProject(Integer status, Integer approvalStatus,
                                    String processInstanceId, LocalDate actualStartDate) {
        ProjectDO project = randomPojo(ProjectDO.class, o -> {
            o.setProjectNo("BC-AP-" + randomString());
            o.setProjectName("立项审批测试项目");
            o.setProjectType(1);
            o.setStatus(status);
            o.setApprovalStatus(approvalStatus);
            o.setProcessInstanceId(processInstanceId);
            o.setActualStartDate(actualStartDate);
            projectMapper.insert(o);
        });
        return project;
    }

}
