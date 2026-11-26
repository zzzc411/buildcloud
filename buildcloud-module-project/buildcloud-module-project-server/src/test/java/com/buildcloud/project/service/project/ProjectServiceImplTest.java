package com.buildcloud.project.service.project;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.project.controller.admin.project.vo.ProjectPageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomInteger;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomString;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_NO_DUPLICATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ProjectServiceImpl} 的单元测试类
 *
 * 风格照基座：继承 {@link BaseDbUnitTest}（H2 内存库 + MyBatis），@Import 被测 Service。
 * H2 建表见 src/test/resources/sql/create_tables.sql，每个用例后由 clean.sql 清库。
 *
 * @author buildcloud
 */
@Import(ProjectServiceImpl.class)
public class ProjectServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ProjectServiceImpl projectService;

    @Resource
    private ProjectMapper projectMapper;

    @Test
    public void testCreateProject_success() {
        // 准备参数
        ProjectSaveReqVO createReqVO = randomPojo(ProjectSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectNo(randomString());
        });

        // 调用
        Long projectId = projectService.createProject(createReqVO);
        // 断言
        assertNotNull(projectId);
        ProjectDO project = projectMapper.selectById(projectId);
        assertPojoEquals(createReqVO, project, "id");
    }

    @Test
    public void testCreateProject_projectNoDuplicate() {
        // mock 数据：先插一条已有编号的项目
        ProjectDO dbProject = randomPojo(ProjectDO.class);
        projectMapper.insert(dbProject);
        // 准备参数：复用同编号
        ProjectSaveReqVO createReqVO = randomPojo(ProjectSaveReqVO.class, o -> {
            o.setId(null);
            o.setProjectNo(dbProject.getProjectNo());
        });

        // 调用，并断言异常
        assertServiceException(() -> projectService.createProject(createReqVO), PROJECT_NO_DUPLICATE);
    }

    @Test
    public void testUpdateProject_success() {
        // mock 数据
        ProjectDO dbProject = randomPojo(ProjectDO.class);
        projectMapper.insert(dbProject);
        // 准备参数
        ProjectSaveReqVO updateReqVO = randomPojo(ProjectSaveReqVO.class, o -> {
            o.setId(dbProject.getId());
            o.setProjectNo(dbProject.getProjectNo()); // 编号不变，避免触发唯一校验
        });

        // 调用
        projectService.updateProject(updateReqVO);
        // 校验是否更新正确
        ProjectDO project = projectMapper.selectById(updateReqVO.getId());
        assertPojoEquals(updateReqVO, project);
    }

    @Test
    public void testUpdateProject_notExists() {
        // 准备参数
        ProjectSaveReqVO updateReqVO = randomPojo(ProjectSaveReqVO.class);

        // 调用，并断言异常
        assertServiceException(() -> projectService.updateProject(updateReqVO), PROJECT_NOT_EXISTS);
    }

    @Test
    public void testDeleteProject_success() {
        // mock 数据
        ProjectDO dbProject = randomPojo(ProjectDO.class);
        projectMapper.insert(dbProject);
        // 准备参数
        Long id = dbProject.getId();

        // 调用
        projectService.deleteProject(id);
        // 校验数据不存在了
        assertNull(projectMapper.selectById(id));
    }

    @Test
    public void testDeleteProject_notExists() {
        // 准备参数
        Long id = randomInteger().longValue();

        // 调用，并断言异常
        assertServiceException(() -> projectService.deleteProject(id), PROJECT_NOT_EXISTS);
    }

    @Test
    public void testGetProject_success() {
        // mock 数据
        ProjectDO dbProject = randomPojo(ProjectDO.class);
        projectMapper.insert(dbProject);

        // 调用
        ProjectDO project = projectService.getProject(dbProject.getId());
        // 断言
        assertPojoEquals(dbProject, project);
    }

    @Test
    public void testGetProjectPage() {
        // mock 数据：一条命中筛选条件，一条不命中
        ProjectDO matchProject = randomPojo(ProjectDO.class, o -> o.setProjectNo("BC-TEST-0001"));
        projectMapper.insert(matchProject);
        projectMapper.insert(randomPojo(ProjectDO.class, o -> o.setProjectNo("BC-OTHER-0002")));

        // 准备参数：精确匹配编号
        ProjectPageReqVO reqVO = new ProjectPageReqVO();
        reqVO.setProjectNo("BC-TEST-0001");

        // 调用
        var pageResult = projectService.getProjectPage(reqVO);
        // 断言
        assertEquals(1, pageResult.getTotal());
        assertEquals(1, pageResult.getList().size());
        assertPojoEquals(matchProject, pageResult.getList().get(0));
    }

}
