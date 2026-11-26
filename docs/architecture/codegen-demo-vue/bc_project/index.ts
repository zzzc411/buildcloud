import request from '@/config/axios'
import type { Dayjs } from 'dayjs'

/** 项目信息 */
export interface Project {
  id: number // 项目编号（主键）
  projectNo?: string // 项目编号（业务编码，租户内唯一）
  projectName?: string // 项目名称
  projectType?: number // 项目类型，见 ProjectTypeEnum
  status?: number // 项目状态，见 ProjectStatusEnum
  provinceCode?: string // 省份编码
  provinceName?: string // 省份名称
  cityCode?: string // 城市编码
  cityName?: string // 城市名称
  address?: string // 详细地址
  buildUnit?: string // 建设单位（业主）
  contractAmount?: number // 合同额（元）
  planStartDate?: string | Dayjs // 计划开工日期
  planEndDate?: string | Dayjs // 计划竣工日期
  actualStartDate?: string | Dayjs // 实际开工日期
  actualEndDate?: string | Dayjs // 实际竣工日期
  managerUserId?: number // 项目经理（system_users.id）
  deptId?: number // 所属部门（system_dept.id）
  remark?: string // 备注
  createTime?: Date // 创建时间
}

// 项目 API
export const ProjectApi = {
  // 查询项目分页
  getProjectPage: async (params: any) => {
    return await request.get({ url: `/project/project/page`, params })
  },

  // 查询项目详情
  getProject: async (id: number) => {
    return await request.get({ url: `/project/project/get?id=` + id })
  },

  // 新增项目
  createProject: async (data: Project) => {
    return await request.post({ url: `/project/project/create`, data })
  },

  // 修改项目
  updateProject: async (data: Project) => {
    return await request.put({ url: `/project/project/update`, data })
  },

  // 删除项目
  deleteProject: async (id: number) => {
    return await request.delete({ url: `/project/project/delete?id=` + id })
  },

  /** 批量删除项目 */
  deleteProjectList: async (ids: number[]) => {
    return await request.delete({ url: `/project/project/delete-list?ids=${ids.join(',')}` })
  }
}

/** 项目类型选项：与 Java 端 ProjectTypeEnum 保持一致 */
export const PROJECT_TYPE_OPTIONS = [
  { label: '房建', value: 1 },
  { label: '市政', value: 2 },
  { label: '路桥', value: 3 },
  { label: '装饰', value: 4 },
  { label: '其他', value: 9 }
]

/** 项目状态选项：与 Java 端 ProjectStatusEnum 保持一致（筹备 → 在建 ⇄ 停工 → 竣工） */
export const PROJECT_STATUS_OPTIONS = [
  { label: '筹备', value: 0 },
  { label: '在建', value: 1 },
  { label: '停工', value: 2 },
  { label: '竣工', value: 3 }
]
