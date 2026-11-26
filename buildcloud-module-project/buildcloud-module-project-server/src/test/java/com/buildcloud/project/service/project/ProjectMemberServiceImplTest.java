package com.buildcloud.project.service.project;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.project.controller.admin.project.vo.ProjectMemberPageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectMemberSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import com.buildcloud.project.dal.dataobject.project.ProjectMemberDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import com.buildcloud.project.dal.mysql.project.ProjectMemberMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Set;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomInteger;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomLongId;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomString;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MEMBER_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MEMBER_PROJECT_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MEMBER_ROLE_INVALID;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MEMBER_USER_DUPLICATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectMemberServiceImpl} 的单元测试类
 *
 * 覆盖点：
 * 1. CRUD 基础链路 + 项目存在性校验；
 * 2. **uk_project_user 冲突**（同一用户在同一项目内重复添加 → 业务错误码，而非 DB 唯一索引异常）；
 *    以及「不同项目可以加同一用户」「更新自身不算冲突」两个反例；
 * 3. 角色取值白名单校验；
 * 4. 数据权限第二维度的取数入口 {@code getProjectIdSetByUserId}（按 user_id 反查项目集合）。
 *
 * @author buildcloud
 */
@Import(ProjectMemberServiceImpl.class)
public class ProjectMemberServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ProjectMemberServiceImpl memberService;

    @Resource
    private ProjectMemberMapper memberMapper;

    @Resource
    private ProjectMapper projectMapper;

    // ========== 基础 CRUD ==========

    @Test
    public void testCreateMember_success() {
        // mock 数据
        ProjectDO project = insertProject();
        // 准备参数
        ProjectMemberSaveReqVO createReqVO = randomPojo(ProjectMemberSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(project.getId());
            o.setUserId(randomLongId());
            o.setProjectRole(3);
        });

        // 调用
        Long memberId = memberService.createMember(createReqVO);
        // 断言
        assertNotNull(memberId);
        ProjectMemberDO member = memberMapper.selectById(memberId);
        assertPojoEquals(createReqVO, member, "id");
    }

    @Test
    public void testCreateMember_projectNotExists() {
        // 准备参数：项目不存在
        ProjectMemberSaveReqVO createReqVO = randomPojo(ProjectMemberSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(randomInteger().longValue() + 100000L);
            o.setProjectRole(1);
        });

        // 调用，并断言异常
        assertServiceException(() -> memberService.createMember(createReqVO),
                PROJECT_MEMBER_PROJECT_NOT_EXISTS);
    }

    @Test
    public void testCreateMember_roleInvalid() {
        // mock 数据
        ProjectDO project = insertProject();
        // 准备参数：角色不在枚举白名单内（如 6）
        ProjectMemberSaveReqVO createReqVO = randomPojo(ProjectMemberSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(project.getId());
            o.setProjectRole(6);
        });

        // 调用，并断言异常
        assertServiceException(() -> memberService.createMember(createReqVO),
                PROJECT_MEMBER_ROLE_INVALID);
    }

    @Test
    public void testCreateMember_userDuplicate() {
        // mock 数据：项目里已有该用户
        ProjectDO project = insertProject();
        ProjectMemberDO dbMember = insertMember(project.getId(), 1001L, 1);
        // 准备参数：同项目 + 同用户
        ProjectMemberSaveReqVO createReqVO = randomPojo(ProjectMemberSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(project.getId());
            o.setUserId(dbMember.getUserId());
            o.setProjectRole(3);
        });

        // 调用，并断言异常（uk_project_user 冲突）
        assertServiceException(() -> memberService.createMember(createReqVO),
                PROJECT_MEMBER_USER_DUPLICATE);
    }

    @Test
    public void testCreateMember_sameUserDifferentProject() {
        // mock 数据：用户 1001 已在 A 项目
        ProjectDO projectA = insertProject();
        ProjectDO projectB = insertProject();
        insertMember(projectA.getId(), 1001L, 1);
        // 准备参数：把同一用户加到 B 项目 —— 唯一键是 (tenant_id, project_id, user_id)，应允许
        ProjectMemberSaveReqVO createReqVO = randomPojo(ProjectMemberSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(projectB.getId());
            o.setUserId(1001L);
            o.setProjectRole(2);
        });

        // 调用
        Long memberId = memberService.createMember(createReqVO);
        // 断言
        assertNotNull(memberId);
    }

    @Test
    public void testUpdateMember_success() {
        // mock 数据
        ProjectDO project = insertProject();
        ProjectMemberDO dbMember = insertMember(project.getId(), 1001L, 3);
        // 准备参数：角色 3 → 1（走 UPDATE，不新增行；自身不算唯一键冲突）
        ProjectMemberSaveReqVO updateReqVO = randomPojo(ProjectMemberSaveReqVO.class, o -> {
            o.setId(dbMember.getId());
            o.setProjectId(project.getId());
            o.setUserId(1001L);
            o.setProjectRole(1);
        });

        // 调用
        memberService.updateMember(updateReqVO);
        // 校验是否更新正确
        ProjectMemberDO member = memberMapper.selectById(updateReqVO.getId());
        assertPojoEquals(updateReqVO, member);
        // 断言：角色变更不新增行
        assertEquals(1, memberMapper.selectListByProjectId(project.getId()).size());
    }

    @Test
    public void testUpdateMember_notExists() {
        // 准备参数
        ProjectMemberSaveReqVO updateReqVO = randomPojo(ProjectMemberSaveReqVO.class, o -> {
            o.setId(randomInteger().longValue() + 100000L);
            o.setProjectRole(1);
        });

        // 调用，并断言异常
        assertServiceException(() -> memberService.updateMember(updateReqVO), PROJECT_MEMBER_NOT_EXISTS);
    }

    @Test
    public void testUpdateMember_userDuplicate() {
        // mock 数据：同项目内已有用户 1001 与 1002
        ProjectDO project = insertProject();
        insertMember(project.getId(), 1001L, 1);
        ProjectMemberDO second = insertMember(project.getId(), 1002L, 3);
        // 准备参数：把第二条的 userId 改成 1001 → 与第一条冲突
        ProjectMemberSaveReqVO updateReqVO = randomPojo(ProjectMemberSaveReqVO.class, o -> {
            o.setId(second.getId());
            o.setProjectId(project.getId());
            o.setUserId(1001L);
            o.setProjectRole(3);
        });

        // 调用，并断言异常
        assertServiceException(() -> memberService.updateMember(updateReqVO),
                PROJECT_MEMBER_USER_DUPLICATE);
    }

    @Test
    public void testDeleteMember_success() {
        // mock 数据
        ProjectDO project = insertProject();
        ProjectMemberDO dbMember = insertMember(project.getId(), 1001L, 1);

        // 调用
        memberService.deleteMember(dbMember.getId());
        // 校验数据不存在了
        assertNull(memberMapper.selectById(dbMember.getId()));
    }

    @Test
    public void testDeleteMember_notExists() {
        // 准备参数
        Long id = randomInteger().longValue() + 100000L;

        // 调用，并断言异常
        assertServiceException(() -> memberService.deleteMember(id), PROJECT_MEMBER_NOT_EXISTS);
    }

    @Test
    public void testGetMember_success() {
        // mock 数据
        ProjectDO project = insertProject();
        ProjectMemberDO dbMember = insertMember(project.getId(), 1001L, 2);

        // 调用
        ProjectMemberDO member = memberService.getMember(dbMember.getId());
        // 断言
        assertPojoEquals(dbMember, member);
    }

    @Test
    public void testGetMemberPage_byProject() {
        // mock 数据：本项目 2 条、另一项目 1 条
        ProjectDO project = insertProject();
        insertMember(project.getId(), 1001L, 1);
        insertMember(project.getId(), 1002L, 3);
        insertMember(insertProject().getId(), 1003L, 1);

        // 准备参数
        ProjectMemberPageReqVO reqVO = new ProjectMemberPageReqVO();
        reqVO.setProjectId(project.getId());

        // 调用
        var pageResult = memberService.getMemberPage(reqVO);
        // 断言
        assertEquals(2, pageResult.getTotal());
        assertEquals(2, pageResult.getList().size());
        // 排序：projectRole 升序 → 项目经理(1) 在前
        assertEquals(1, pageResult.getList().get(0).getProjectRole());
    }

    @Test
    public void testGetMemberListByProjectId_returnsUserIdAndRole() {
        // mock 数据
        ProjectDO project = insertProject();
        insertMember(project.getId(), 2001L, 3);
        insertMember(project.getId(), 2002L, 1);

        // 调用
        List<ProjectMemberDO> list = memberService.getMemberListByProjectId(project.getId());
        // 断言：projectRole 升序，且返回 userId / projectRole
        assertEquals(2, list.size());
        assertEquals(1, list.get(0).getProjectRole());
        assertEquals(2002L, list.get(0).getUserId());
        assertEquals(3, list.get(1).getProjectRole());
        assertEquals(2001L, list.get(1).getUserId());
    }

    // ========== 数据权限第二维度：成员白名单取数 ==========

    @Test
    public void testGetProjectIdSetByUserId() {
        // mock 数据：用户 3001 参与 A、B 两个项目；用户 3002 参与 C 项目
        ProjectDO projectA = insertProject();
        ProjectDO projectB = insertProject();
        ProjectDO projectC = insertProject();
        insertMember(projectA.getId(), 3001L, 1);
        insertMember(projectB.getId(), 3001L, 3);
        insertMember(projectC.getId(), 3002L, 1);

        // 调用
        Set<Long> projectIds = memberService.getProjectIdSetByUserId(3001L);
        // 断言：只含 A、B
        assertEquals(2, projectIds.size());
        assertTrue(projectIds.contains(projectA.getId()));
        assertTrue(projectIds.contains(projectB.getId()));
    }

    @Test
    public void testGetProjectIdSetByUserId_noProject() {
        // 调用：用户没有参与任何项目 → 空集合（不是 null，可直接拼 IN 条件）
        Set<Long> projectIds = memberService.getProjectIdSetByUserId(999999L);
        // 断言
        assertNotNull(projectIds);
        assertTrue(projectIds.isEmpty());
    }

    @Test
    public void testGetProjectIdSetByUserId_nullUser() {
        // 调用：userId 为 null → 空集合，不抛异常
        Set<Long> projectIds = memberService.getProjectIdSetByUserId(null);
        // 断言
        assertNotNull(projectIds);
        assertTrue(projectIds.isEmpty());
    }

    // ========== 测试数据构造 ==========

    private ProjectDO insertProject() {
        ProjectDO project = randomPojo(ProjectDO.class, o -> {
            o.setProjectNo("BC-MB-" + randomString());
            o.setProjectName("测试项目");
            o.setStatus(0);
        });
        projectMapper.insert(project);
        return project;
    }

    private ProjectMemberDO insertMember(Long projectId, Long userId, Integer projectRole) {
        ProjectMemberDO member = randomPojo(ProjectMemberDO.class, o -> {
            o.setProjectId(projectId);
            o.setUserId(userId);
            o.setProjectRole(projectRole);
        });
        memberMapper.insert(member);
        return member;
    }

}
