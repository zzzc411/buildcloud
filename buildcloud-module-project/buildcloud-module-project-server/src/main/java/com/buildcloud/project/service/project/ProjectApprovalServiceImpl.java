package com.buildcloud.project.service.project;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import com.buildcloud.project.controller.admin.project.vo.ProjectApprovalStatusRespVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectUpdateStatusReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import com.buildcloud.project.enums.project.ProjectApprovalStatusEnum;
import com.buildcloud.project.enums.project.ProjectStatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_APPROVAL_ALREADY_RUNNING;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_APPROVAL_PROCESS_INSTANCE_MISMATCH;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_APPROVAL_START_FAILED;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_APPROVAL_STATUS_NOT_PREPARE;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_NOT_EXISTS;
import static com.buildcloud.project.enums.project.ProjectBpmConstants.PROCESS_KEY_PROJECT_APPROVE;
import static com.buildcloud.project.enums.project.ProjectBpmConstants.VARIABLE_DEPT_ID;
import static com.buildcloud.project.enums.project.ProjectBpmConstants.VARIABLE_INITIATOR;
import static com.buildcloud.project.enums.project.ProjectBpmConstants.VARIABLE_PROJECT_ID;
import static com.buildcloud.project.enums.project.ProjectBpmConstants.VARIABLE_PROJECT_NAME;
import static com.buildcloud.project.enums.project.ProjectBpmConstants.VARIABLE_PROJECT_NO;

/**
 * 项目立项审批 Service 实现类
 *
 * 复刻基座 oa-server 的 {@code OaLeaveApplyServiceImpl#submitLeaveApply}（读码笔记 §3.1），
 * 与之对齐的三个关键点：
 * 1. **先落「审批中」再调 Feign**：避免 bpm 回调先于本地提交到达时读到的还是旧状态；
 *    （本实现用 @Transactional，回调侧有事务隔离，实际不会读到未提交数据，
 *    但仍按基座顺序写，减少与基座模式的分叉）
 * 2. **businessKey = 业务单据主键**（这里是 projectId 的字符串形态），回调时原样带回；
 * 3. **发起人 userId 显式传参**（Feign 的第一个参数），由 bpm 侧以该身份执行流程。
 *
 * 事务与远程调用的取舍：
 * Feign 调用在事务内，若后续回写失败会回滚本地数据但 bpm 侧实例已建（分布式一致性缺口）。
 * 基座 OA 同样如此，属「框架现状」而非本域缺陷；补偿手段是提交前用
 * approvalStatus=RUNNING 做重复提交拦截，且重新提交时会覆盖 processInstanceId，
 * 老实例成为孤儿但不影响业务（审批中心仍可查、可撤回）。此取舍在 ADR-003 中记录。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class ProjectApprovalServiceImpl implements ProjectApprovalService {

    @Resource
    private ProjectMapper projectMapper;

    @Resource
    private ProjectStatusService projectStatusService;

    /**
     * 基座 BPM 流程实例 RPC（bpm-server，@FeignClient(name="bpm-server")）
     */
    @Resource
    private BpmProcessInstanceApi processInstanceApi;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submitApproval(Long projectId) {
        // 1.1 校验项目存在
        ProjectDO project = projectMapper.selectById(projectId);
        if (project == null) {
            throw exception(PROJECT_NOT_EXISTS);
        }
        // 1.2 校验项目业务状态必须是「筹备」：立项审批的意义就是让筹备中的项目转为在建，
        //     已在建/停工/竣工的项目再走立项没有业务含义（也别绕过状态机把竣工项目拉回在建）
        if (!ObjectUtil.equal(project.getStatus(), ProjectStatusEnum.PREPARING.getStatus())) {
            throw exception(PROJECT_APPROVAL_STATUS_NOT_PREPARE,
                    getProjectStatusName(project.getStatus()));
        }
        // 1.3 校验没有审批中的流程：防同一项目并发发起两个实例
        //     注意只拦「审批中」，终态（通过/驳回/取消）允许重新发起——驳回后修改资料再提交是常态
        if (ObjectUtil.equal(project.getApprovalStatus(), ProjectApprovalStatusEnum.RUNNING.getStatus())) {
            throw exception(PROJECT_APPROVAL_ALREADY_RUNNING,
                    StrUtil.blankToDefault(project.getProcessInstanceId(), "-"));
        }

        // 2. 先置「审批中」：即使后面 Feign 抛异常，事务回滚也一致；
        //    顺序与基座 OA 一致，便于以后对照排查
        ProjectDO updateObj = new ProjectDO();
        updateObj.setId(project.getId());
        updateObj.setApprovalStatus(ProjectApprovalStatusEnum.RUNNING.getStatus());
        projectMapper.updateById(updateObj);

        // 3. 发起流程实例（读码笔记 §2.1：四个入参 = key + variables + businessKey + 自选审批人）
        //    本流程的审批人由 BPMN 里配置的「用户组 9201 项目经理组」决定，不用发起人自选，故第四个参数传 null
        Long userId = getLoginUserId();
        String processInstanceId = processInstanceApi.createProcessInstance(userId,
                        new BpmProcessInstanceCreateReqDTO()
                                .setProcessDefinitionKey(PROCESS_KEY_PROJECT_APPROVE)
                                .setBusinessKey(String.valueOf(project.getId()))
                                .setVariables(buildProcessVariables(project, userId)))
                .getCheckedData();
        // 3.1 基座返回空实例编号说明 bpm 侧没有真正建实例，必须抛错回滚，
        //     否则业务表会留下「审批中但没有流程」的死状态（永远等不到回调）
        if (StrUtil.isBlank(processInstanceId)) {
            throw exception(PROJECT_APPROVAL_START_FAILED);
        }

        // 4. 回写流程实例编号（approvalStatus 已在第 2 步写入）
        projectMapper.updateById(new ProjectDO().setId(project.getId()).setProcessInstanceId(processInstanceId));
        log.info("[submitApproval][项目({}) 发起立项审批成功，processInstanceId={}, userId={}]",
                project.getId(), processInstanceId, userId);
        return processInstanceId;
    }

    @Override
    public ProjectApprovalStatusRespVO getApprovalStatus(Long projectId) {
        ProjectDO project = projectMapper.selectById(projectId);
        if (project == null) {
            throw exception(PROJECT_NOT_EXISTS);
        }
        return buildStatusRespVO(project);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleApprovalResult(Long projectId, String processInstanceId, boolean approved, String reason) {
        ProjectDO project = projectMapper.selectById(projectId);
        if (project == null) {
            // 回调早于业务提交 / 业务单据被物理删除：记日志丢弃，不抛异常打断 bpm 的转发链
            // （抛异常会让 bpm 侧 HTTP 调用抛 PROCESS_INSTANCE_HTTP_CALL_ERROR，污染流程结束事务）
            log.warn("[handleApprovalResult][项目({}) 不存在，忽略审批回调，processInstanceId={}]",
                    projectId, processInstanceId);
            return;
        }

        // ========== 幂等判定（核心） ==========
        // 1) 串号校验：回调带的实例编号与库中当前实例不一致 → 是旧流程的迟到通知，丢弃。
        //    典型场景：驳回后重新发起新流程，旧实例的取消/完成事件随后到达。
        if (StrUtil.isNotBlank(processInstanceId)
                && StrUtil.isNotBlank(project.getProcessInstanceId())
                && !StrUtil.equals(processInstanceId, project.getProcessInstanceId())) {
            log.warn("[handleApprovalResult][项目({}) 审批回调实例({}) 与当前实例({}) 不一致，忽略]",
                    projectId, processInstanceId, project.getProcessInstanceId());
            return;
        }
        // 2) 终态短路：已经是终态且结果相同 → 重复通知，直接返回。
        //    正常链路里 bpm 只会转发一次，但 HTTP 重试、运维手工重放、多实例部署重复消费都可能触发；
        //    这里保证「通过」不会被重复执行（否则 ProjectStatusService 会因 1→1 同状态流转抛
        //    PROJECT_STATUS_TRANSITION_INVALID），「驳回」也不会把已有状态反复覆盖。
        Integer current = project.getApprovalStatus();
        Integer target = approved ? ProjectApprovalStatusEnum.APPROVE.getStatus()
                : ProjectApprovalStatusEnum.REJECT.getStatus();
        if (ObjectUtil.equal(current, target)) {
            log.info("[handleApprovalResult][项目({}) 已是终态 {}, 判定为重复回调，幂等返回]",
                    projectId, ProjectApprovalStatusEnum.valueOf(current).getDesc());
            return;
        }

        // ========== 审批通过：走状态机 筹备(0) → 在建(1) ==========
        if (approved) {
            // 复用 ProjectStatusService，不直接 UPDATE status——状态机是唯一入口（spec §5.1），
            // 这样「筹备 → 在建」的合法性校验、实际开工日期回填都在同一处生效
            ProjectUpdateStatusReqVO statusReqVO = new ProjectUpdateStatusReqVO();
            statusReqVO.setId(project.getId());
            statusReqVO.setStatus(ProjectStatusEnum.CONSTRUCTING.getStatus());
            projectStatusService.updateProjectStatus(statusReqVO);
        }
        // 驳回：项目业务状态保持「筹备」，不做任何状态机调用（这是本流程的既定语义）

        // 回写审批状态（通过=2 / 不通过=3）
        projectMapper.updateById(new ProjectDO().setId(project.getId()).setApprovalStatus(target));
        log.info("[handleApprovalResult][项目({}) 审批结果回写完成：approvalStatus {} -> {}，项目状态={}, reason={}]",
                projectId, current, target,
                approved ? ProjectStatusEnum.CONSTRUCTING.getStatus() : project.getStatus(), reason);
    }

    // ========== 私有方法 ==========

    /**
     * 构造流程变量
     *
     * businessKey 已经在 {@link BpmProcessInstanceCreateReqDTO#setBusinessKey} 里传了项目编号，
     * 这里再冗余一份 {@code projectId} 是为了让流程详情页/待办列表能直接读变量展示，
     * 不必反查业务库（基座对 OA 请假也是同样的做法：变量里带 days 等业务字段）。
     */
    private Map<String, Object> buildProcessVariables(ProjectDO project, Long userId) {
        Map<String, Object> variables = new HashMap<>(8);
        variables.put(VARIABLE_PROJECT_ID, project.getId());
        variables.put(VARIABLE_PROJECT_NO, project.getProjectNo());
        variables.put(VARIABLE_PROJECT_NAME, project.getProjectName());
        variables.put(VARIABLE_INITIATOR, userId);
        if (project.getDeptId() != null) {
            variables.put(VARIABLE_DEPT_ID, project.getDeptId());
        }
        return variables;
    }

    private ProjectApprovalStatusRespVO buildStatusRespVO(ProjectDO project) {
        ProjectApprovalStatusRespVO respVO = new ProjectApprovalStatusRespVO();
        respVO.setProjectId(project.getId());
        respVO.setProcessInstanceId(project.getProcessInstanceId());
        // approvalStatus 列为 NOT NULL DEFAULT -1，但历史数据/手工导入可能为 null，兜底成「未开始」
        Integer approvalStatus = project.getApprovalStatus() != null
                ? project.getApprovalStatus() : ProjectApprovalStatusEnum.NOT_START.getStatus();
        respVO.setApprovalStatus(approvalStatus);
        ProjectApprovalStatusEnum approvalStatusEnum = ProjectApprovalStatusEnum.valueOf(approvalStatus);
        respVO.setApprovalStatusName(approvalStatusEnum != null ? approvalStatusEnum.getDesc() : "未知");
        respVO.setProjectStatus(project.getStatus());
        respVO.setProjectStatusName(getProjectStatusName(project.getStatus()));
        // reason 只在状态本身有语义时给出：避免前端拿固定话术当「审批意见」展示
        respVO.setReason(buildReason(approvalStatus));
        return respVO;
    }

    /**
     * 按审批状态给出面向操作者的说明
     *
     * 说明：本域不落审批意见（需要审批意见时经 bpm 的评论/任务详情接口查，
     * 那是审批中心的本职，业务表不重复存储）。
     */
    private String buildReason(Integer approvalStatus) {
        if (ObjectUtil.equal(approvalStatus, ProjectApprovalStatusEnum.RUNNING.getStatus())) {
            return "已提交审批，等待审批人处理";
        }
        if (ObjectUtil.equal(approvalStatus, ProjectApprovalStatusEnum.NOT_START.getStatus())) {
            return "尚未提交立项审批";
        }
        if (ObjectUtil.equal(approvalStatus, ProjectApprovalStatusEnum.APPROVE.getStatus())) {
            return "立项审批已通过，项目已进入在建";
        }
        if (ObjectUtil.equal(approvalStatus, ProjectApprovalStatusEnum.REJECT.getStatus())) {
            return "立项审批未通过，项目仍为筹备状态，可修改后重新提交";
        }
        if (ObjectUtil.equal(approvalStatus, ProjectApprovalStatusEnum.CANCEL.getStatus())) {
            return "审批已取消";
        }
        return null;
    }

    /**
     * 取项目业务状态中文名；未知状态返回「未知状态(x)」而不是抛异常
     * （与 ProjectStatusServiceImpl#getStatusName 同口径，便于错误提示可读）
     */
    private String getProjectStatusName(Integer status) {
        for (ProjectStatusEnum value : ProjectStatusEnum.values()) {
            if (ObjectUtil.equal(value.getStatus(), status)) {
                return value.getName();
            }
        }
        return "未知状态(" + status + ")";
    }

}
