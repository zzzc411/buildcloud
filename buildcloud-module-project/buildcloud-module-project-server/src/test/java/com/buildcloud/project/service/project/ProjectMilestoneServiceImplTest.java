package com.buildcloud.project.service.project;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.project.controller.admin.project.vo.ProjectMilestonePageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectMilestoneSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import com.buildcloud.project.dal.dataobject.project.ProjectMilestoneDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import com.buildcloud.project.dal.mysql.project.ProjectMilestoneMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomInteger;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomString;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MILESTONE_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MILESTONE_PROJECT_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MILESTONE_WEIGHT_EXCEED;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MILESTONE_WEIGHT_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectMilestoneServiceImpl} 的单元测试类
 *
 * 风格照 {@link ProjectServiceImplTest}：继承 {@link BaseDbUnitTest}（H2 内存库 + MyBatis），
 * @Import 被测 Service；H2 建表见 src/test/resources/sql/create_tables.sql，每个用例后 clean.sql 清库。
 *
 * 覆盖点：
 * 1. CRUD 基础链路（含 projectId 存在性校验）；
 * 2. 权重区间校验（单个 0~100）与**同项目权重合计 ≤ 100**（含「更新时排除自身」这个易错点）；
 * 3. 分页与按项目列表的排序（sort 升序）。
 *
 * @author buildcloud
 */
@Import(ProjectMilestoneServiceImpl.class)
public class ProjectMilestoneServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ProjectMilestoneServiceImpl milestoneService;

    @Resource
    private ProjectMilestoneMapper milestoneMapper;

    @Resource
    private ProjectMapper projectMapper;

    // ========== 基础 CRUD ==========

    @Test
    public void testCreateMilestone_success() {
        // mock 数据：先建一个项目（里程碑必须挂在存在的项目下）
        ProjectDO project = insertProject();
        // 准备参数
        ProjectMilestoneSaveReqVO createReqVO = randomPojo(ProjectMilestoneSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(project.getId());
            o.setMilestoneName("主体结构封顶");
            o.setWeight(new BigDecimal("30.00"));
            o.setStatus(0);
            o.setSort(1);
        });

        // 调用
        Long milestoneId = milestoneService.createMilestone(createReqVO);
        // 断言
        assertNotNull(milestoneId);
        ProjectMilestoneDO milestone = milestoneMapper.selectById(milestoneId);
        assertPojoEquals(createReqVO, milestone, "id");
    }

    @Test
    public void testCreateMilestone_projectNotExists() {
        // 准备参数：项目编号不存在
        ProjectMilestoneSaveReqVO createReqVO = randomPojo(ProjectMilestoneSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(randomInteger().longValue() + 100000L);
            o.setWeight(new BigDecimal("10.00"));
        });

        // 调用，并断言异常
        assertServiceException(() -> milestoneService.createMilestone(createReqVO),
                PROJECT_MILESTONE_PROJECT_NOT_EXISTS);
    }

    @Test
    public void testCreateMilestone_weightNegative() {
        // mock 数据
        ProjectDO project = insertProject();
        // 准备参数：负权重
        ProjectMilestoneSaveReqVO createReqVO = randomPojo(ProjectMilestoneSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(project.getId());
            o.setWeight(new BigDecimal("-1.00"));
        });

        // 调用，并断言异常
        assertServiceException(() -> milestoneService.createMilestone(createReqVO),
                PROJECT_MILESTONE_WEIGHT_INVALID);
    }

    @Test
    public void testCreateMilestone_weightOver100() {
        // mock 数据
        ProjectDO project = insertProject();
        // 准备参数：单条权重超过 100
        ProjectMilestoneSaveReqVO createReqVO = randomPojo(ProjectMilestoneSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(project.getId());
            o.setWeight(new BigDecimal("100.01"));
        });

        // 调用，并断言异常
        assertServiceException(() -> milestoneService.createMilestone(createReqVO),
                PROJECT_MILESTONE_WEIGHT_INVALID);
    }

    @Test
    public void testCreateMilestone_weightTotalExceed() {
        // mock 数据：同项目已有 80 权重
        ProjectDO project = insertProject();
        insertMilestone(project.getId(), new BigDecimal("80.00"), 1);
        // 准备参数：再加 30 → 合计 110
        ProjectMilestoneSaveReqVO createReqVO = randomPojo(ProjectMilestoneSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(project.getId());
            o.setWeight(new BigDecimal("30.00"));
        });

        // 调用，并断言异常（错误提示带上当前合计 110）
        assertServiceException(() -> milestoneService.createMilestone(createReqVO),
                PROJECT_MILESTONE_WEIGHT_EXCEED, "110");
    }

    @Test
    public void testCreateMilestone_weightTotalExactly100() {
        // mock 数据：同项目已有 70 权重
        ProjectDO project = insertProject();
        insertMilestone(project.getId(), new BigDecimal("70.00"), 1);
        // 准备参数：再加 30 → 合计正好 100，应当允许（边界值）
        ProjectMilestoneSaveReqVO createReqVO = randomPojo(ProjectMilestoneSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(project.getId());
            o.setMilestoneName("竣工验收");
            o.setWeight(new BigDecimal("30.00"));
        });

        // 调用
        Long id = milestoneService.createMilestone(createReqVO);
        // 断言
        assertNotNull(id);
    }

    @Test
    public void testCreateMilestone_weightTotalAcrossProjects() {
        // mock 数据：A 项目已占满 100，B 项目为空
        ProjectDO projectA = insertProject();
        ProjectDO projectB = insertProject();
        insertMilestone(projectA.getId(), new BigDecimal("100.00"), 1);
        // 准备参数：往 B 项目加 100 —— 权重合计按项目隔离，不应被 A 占用影响
        ProjectMilestoneSaveReqVO createReqVO = randomPojo(ProjectMilestoneSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectId(projectB.getId());
            o.setWeight(new BigDecimal("100.00"));
        });

        // 调用
        Long id = milestoneService.createMilestone(createReqVO);
        // 断言
        assertNotNull(id);
    }

    @Test
    public void testUpdateMilestone_success() {
        // mock 数据
        ProjectDO project = insertProject();
        ProjectMilestoneDO dbMilestone = insertMilestone(project.getId(), new BigDecimal("20.00"), 1);
        // 准备参数：权重保持不变（校验必须排除自身，否则 20 + 20 = 40 会被误算成超限场景）
        ProjectMilestoneSaveReqVO updateReqVO = randomPojo(ProjectMilestoneSaveReqVO.class, o -> {
            o.setId(dbMilestone.getId());
            o.setProjectId(project.getId());
            o.setMilestoneName("主体结构封顶（修订）");
            o.setWeight(new BigDecimal("20.00"));
            o.setSort(2);
            o.setStatus(1);
        });

        // 调用
        milestoneService.updateMilestone(updateReqVO);
        // 校验是否更新正确
        ProjectMilestoneDO milestone = milestoneMapper.selectById(updateReqVO.getId());
        assertPojoEquals(updateReqVO, milestone);
    }

    @Test
    public void testUpdateMilestone_notExists() {
        // 准备参数
        ProjectMilestoneSaveReqVO updateReqVO = randomPojo(ProjectMilestoneSaveReqVO.class, o -> {
            o.setId(randomInteger().longValue() + 100000L);
            o.setWeight(new BigDecimal("10.00"));
        });

        // 调用，并断言异常
        assertServiceException(() -> milestoneService.updateMilestone(updateReqVO),
                PROJECT_MILESTONE_NOT_EXISTS);
    }

    @Test
    public void testUpdateMilestone_weightTotalExceed() {
        // mock 数据：同项目两条各 60，合计 120 —— 这里直接用 mapper 插入绕过校验（模拟历史脏数据）
        ProjectDO project = insertProject();
        ProjectMilestoneDO target = insertMilestone(project.getId(), new BigDecimal("60.00"), 1);
        insertMilestone(project.getId(), new BigDecimal("60.00"), 2);
        // 准备参数：把 target 权重改成 60（不变）——排除自身后合计 = 60 + 60 = 120 > 100
        ProjectMilestoneSaveReqVO updateReqVO = randomPojo(ProjectMilestoneSaveReqVO.class, o -> {
            o.setId(target.getId());
            o.setProjectId(project.getId());
            o.setWeight(new BigDecimal("60.00"));
        });

        // 调用，并断言异常
        assertServiceException(() -> milestoneService.updateMilestone(updateReqVO),
                PROJECT_MILESTONE_WEIGHT_EXCEED, "120");
    }

    @Test
    public void testDeleteMilestone_success() {
        // mock 数据
        ProjectDO project = insertProject();
        ProjectMilestoneDO dbMilestone = insertMilestone(project.getId(), new BigDecimal("10.00"), 1);

        // 调用
        milestoneService.deleteMilestone(dbMilestone.getId());
        // 校验数据不存在了
        assertNull(milestoneMapper.selectById(dbMilestone.getId()));
    }

    @Test
    public void testDeleteMilestone_notExists() {
        // 准备参数
        Long id = randomInteger().longValue() + 100000L;

        // 调用，并断言异常
        assertServiceException(() -> milestoneService.deleteMilestone(id), PROJECT_MILESTONE_NOT_EXISTS);
    }

    @Test
    public void testGetMilestone_success() {
        // mock 数据
        ProjectDO project = insertProject();
        ProjectMilestoneDO dbMilestone = insertMilestone(project.getId(), new BigDecimal("15.50"), 1);

        // 调用
        ProjectMilestoneDO milestone = milestoneService.getMilestone(dbMilestone.getId());
        // 断言
        assertPojoEquals(dbMilestone, milestone);
    }

    @Test
    public void testGetMilestonePage() {
        // mock 数据：同项目两条、另一项目一条
        ProjectDO project = insertProject();
        ProjectMilestoneDO match = insertMilestone(project.getId(), new BigDecimal("10.00"), 1);
        insertMilestone(project.getId(), new BigDecimal("20.00"), 2);
        insertMilestone(insertProject().getId(), new BigDecimal("30.00"), 1);

        // 准备参数：按项目筛选
        ProjectMilestonePageReqVO reqVO = new ProjectMilestonePageReqVO();
        reqVO.setProjectId(project.getId());

        // 调用
        var pageResult = milestoneService.getMilestonePage(reqVO);
        // 断言：只返回本项目的 2 条
        assertEquals(2, pageResult.getTotal());
        assertEquals(2, pageResult.getList().size());
        assertEquals(match.getId(), pageResult.getList().get(0).getId());
    }

    @Test
    public void testGetMilestoneListByProjectId_orderBySort() {
        // mock 数据：故意乱序插入，sort 分别为 3 / 1 / 2
        ProjectDO project = insertProject();
        ProjectMilestoneDO third = insertMilestone(project.getId(), new BigDecimal("10.00"), 3);
        ProjectMilestoneDO first = insertMilestone(project.getId(), new BigDecimal("10.00"), 1);
        ProjectMilestoneDO second = insertMilestone(project.getId(), new BigDecimal("10.00"), 2);

        // 调用
        List<ProjectMilestoneDO> list = milestoneService.getMilestoneListByProjectId(project.getId());
        // 断言：按 sort 升序
        assertEquals(3, list.size());
        assertEquals(first.getId(), list.get(0).getId());
        assertEquals(second.getId(), list.get(1).getId());
        assertEquals(third.getId(), list.get(2).getId());
    }

    @Test
    public void testGetMilestoneListByProjectId_empty() {
        // 调用：不存在的项目 → 空列表（不是 null，前端可直接遍历）
        List<ProjectMilestoneDO> list = milestoneService.getMilestoneListByProjectId(999999L);
        // 断言
        assertTrue(list.isEmpty());
    }

    // ========== 测试数据构造 ==========

    /**
     * 插入一个测试项目（project_no 随机保证唯一键不冲突）
     */
    private ProjectDO insertProject() {
        ProjectDO project = randomPojo(ProjectDO.class, o -> {
            o.setProjectNo("BC-MT-" + randomString());
            o.setProjectName("测试项目");
            o.setStatus(0);
        });
        projectMapper.insert(project);
        return project;
    }

    /**
     * 插入一条测试里程碑（直接走 mapper，绕过服务层权重合计校验，便于构造边界数据）
     */
    private ProjectMilestoneDO insertMilestone(Long projectId, BigDecimal weight, Integer sort) {
        ProjectMilestoneDO milestone = randomPojo(ProjectMilestoneDO.class, o -> {
            o.setProjectId(projectId);
            o.setMilestoneName("里程碑-" + randomString());
            o.setWeight(weight);
            o.setSort(sort);
            o.setStatus(0);
            o.setPlanDate(LocalDate.of(2026, 6, 30));
        });
        milestoneMapper.insert(milestone);
        return milestone;
    }

}
