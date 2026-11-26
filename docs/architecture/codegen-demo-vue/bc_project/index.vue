<template>
  <ContentWrap>
    <!-- 搜索工作栏 -->
    <el-form
      class="-mb-15px"
      :model="queryParams"
      ref="queryFormRef"
      :inline="true"
      label-width="85px"
    >
      <el-form-item label="项目编号" prop="projectNo">
        <el-input
          v-model="queryParams.projectNo"
          placeholder="请输入项目编号"
          clearable
          @keyup.enter="handleQuery"
          class="!w-200px"
        />
      </el-form-item>
      <el-form-item label="项目名称" prop="projectName">
        <el-input
          v-model="queryParams.projectName"
          placeholder="请输入项目名称"
          clearable
          @keyup.enter="handleQuery"
          class="!w-200px"
        />
      </el-form-item>
      <el-form-item label="项目类型" prop="projectType">
        <el-select
          v-model="queryParams.projectType"
          placeholder="请选择项目类型"
          clearable
          class="!w-160px"
        >
          <el-option
            v-for="item in PROJECT_TYPE_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="项目状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="请选择项目状态" clearable class="!w-140px">
          <el-option
            v-for="item in PROJECT_STATUS_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="建设单位" prop="buildUnit">
        <el-input
          v-model="queryParams.buildUnit"
          placeholder="请输入建设单位"
          clearable
          @keyup.enter="handleQuery"
          class="!w-200px"
        />
      </el-form-item>
      <el-form-item label="所属部门" prop="deptId">
        <el-tree-select
          v-model="queryParams.deptId"
          :data="deptTree"
          :props="defaultProps"
          check-strictly
          node-key="id"
          placeholder="请选择所属部门"
          clearable
          class="!w-200px"
        />
      </el-form-item>
      <el-form-item label="创建时间" prop="createTime">
        <el-date-picker
          v-model="queryParams.createTime"
          value-format="YYYY-MM-DD HH:mm:ss"
          type="daterange"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :default-time="[new Date('1 00:00:00'), new Date('1 23:59:59')]"
          class="!w-220px"
        />
      </el-form-item>
      <el-form-item>
        <el-button @click="handleQuery"><Icon icon="ep:search" class="mr-5px" /> 搜索</el-button>
        <el-button @click="resetQuery"><Icon icon="ep:refresh" class="mr-5px" /> 重置</el-button>
        <el-button
          type="primary"
          plain
          @click="openForm('create')"
          v-hasPermi="['project:project:create']"
        >
          <Icon icon="ep:plus" class="mr-5px" /> 新增
        </el-button>
        <el-button
          type="danger"
          plain
          :disabled="isEmpty(checkedIds)"
          @click="handleDeleteBatch"
          v-hasPermi="['project:project:delete']"
        >
          <Icon icon="ep:delete" class="mr-5px" /> 批量删除
        </el-button>
      </el-form-item>
    </el-form>
  </ContentWrap>

  <!-- 列表 -->
  <ContentWrap>
    <el-table
      row-key="id"
      v-loading="loading"
      :data="list"
      :stripe="true"
      :show-overflow-tooltip="true"
      @selection-change="handleRowCheckboxChange"
    >
      <el-table-column type="selection" width="55" />
      <el-table-column label="项目编号" align="center" prop="projectNo" min-width="140px" />
      <el-table-column label="项目名称" align="center" prop="projectName" min-width="180px" />
      <el-table-column label="项目类型" align="center" prop="projectType" min-width="100px">
        <template #default="scope">
          {{ formatOption(PROJECT_TYPE_OPTIONS, scope.row.projectType) }}
        </template>
      </el-table-column>
      <el-table-column label="项目状态" align="center" prop="status" min-width="100px">
        <template #default="scope">
          <el-tag :type="getStatusTagType(scope.row.status)">
            {{ formatOption(PROJECT_STATUS_OPTIONS, scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="建设单位" align="center" prop="buildUnit" min-width="160px" />
      <el-table-column label="合同额（元）" align="center" prop="contractAmount" min-width="140px">
        <template #default="scope">
          {{ formatAmount(scope.row.contractAmount) }}
        </template>
      </el-table-column>
      <el-table-column
        label="计划开工"
        align="center"
        prop="planStartDate"
        :formatter="dateOnlyFormatter"
        width="120px"
      />
      <el-table-column
        label="计划竣工"
        align="center"
        prop="planEndDate"
        :formatter="dateOnlyFormatter"
        width="120px"
      />
      <el-table-column
        label="创建时间"
        align="center"
        prop="createTime"
        :formatter="dateFormatter"
        width="180px"
      />
      <el-table-column label="操作" align="center" min-width="140px">
        <template #default="scope">
          <el-button
            link
            type="primary"
            @click="openForm('update', scope.row.id)"
            v-hasPermi="['project:project:update']"
          >
            编辑
          </el-button>
          <el-button
            link
            type="danger"
            @click="handleDelete(scope.row.id)"
            v-hasPermi="['project:project:delete']"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <!-- 分页 -->
    <Pagination
      :total="total"
      v-model:page="queryParams.pageNo"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />
  </ContentWrap>

  <!-- 表单弹窗：添加/修改 -->
  <ProjectForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import { isEmpty } from '@/utils/is'
import { dateFormatter } from '@/utils/formatTime'
import {
  ProjectApi,
  Project,
  PROJECT_TYPE_OPTIONS,
  PROJECT_STATUS_OPTIONS
} from '@/api/project/project'
import ProjectForm from './ProjectForm.vue'
import * as DeptApi from '@/api/system/dept'

/** 项目台账 */
defineOptions({ name: 'Project' })

const message = useMessage() // 消息弹窗
const { t } = useI18n() // 国际化

const loading = ref(true) // 列表的加载中
const list = ref<Project[]>([]) // 列表的数据
const total = ref(0) // 列表的总数
const queryParams = reactive({
  pageNo: 1,
  pageSize: 10,
  projectNo: undefined,
  projectName: undefined,
  projectType: undefined,
  status: undefined,
  buildUnit: undefined,
  deptId: undefined,
  createTime: []
})
const queryFormRef = ref() // 搜索的表单

/** 部门树（数据权限按部门过滤，spec §5.1） */
const deptTree = ref<any[]>([])
const defaultProps = {
  children: 'children',
  label: 'name',
  value: 'id'
}
const getDeptTree = async () => {
  deptTree.value = await DeptApi.getSimpleDeptList()
}

/** 查询列表 */
const getList = async () => {
  loading.value = true
  try {
    const data = await ProjectApi.getProjectPage(queryParams)
    list.value = data.list
    total.value = data.total
  } finally {
    loading.value = false
  }
}

/** 搜索按钮操作 */
const handleQuery = () => {
  queryParams.pageNo = 1
  getList()
}

/** 重置按钮操作 */
const resetQuery = () => {
  queryFormRef.value.resetFields()
  handleQuery()
}

/** 添加/修改操作 */
const formRef = ref()
const openForm = (type: string, id?: number) => {
  formRef.value.open(type, id)
}

/** 删除按钮操作 */
const handleDelete = async (id: number) => {
  try {
    // 删除的二次确认
    await message.delConfirm()
    // 发起删除
    await ProjectApi.deleteProject(id)
    message.success(t('common.delSuccess'))
    // 刷新列表
    await getList()
  } catch {}
}

/** 批量删除项目 */
const handleDeleteBatch = async () => {
  try {
    // 删除的二次确认
    await message.delConfirm()
    await ProjectApi.deleteProjectList(checkedIds.value)
    checkedIds.value = []
    message.success(t('common.delSuccess'))
    await getList()
  } catch {}
}

const checkedIds = ref<number[]>([])
const handleRowCheckboxChange = (records: Project[]) => {
  checkedIds.value = records.map((item) => item.id!)
}

/** 枚举值 → 中文名（省略未命中时回退到原值） */
const formatOption = (options: { label: string; value: number }[], value?: number) => {
  if (value === null || value === undefined) return ''
  return options.find((item) => item.value === value)?.label ?? String(value)
}

/** 状态标签颜色：筹备灰、在建蓝、停工橙、竣工绿 */
const getStatusTagType = (status?: number) => {
  switch (status) {
    case 1:
      return 'primary'
    case 2:
      return 'warning'
    case 3:
      return 'success'
    default:
      return 'info'
  }
}

/** 金额千分位，保留两位小数 */
const formatAmount = (amount?: number) => {
  if (amount === null || amount === undefined) return ''
  return Number(amount).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/**
 * bc_project 的四个日期列是 date 类型（无时分秒），基座 dateFormatter 会带出 00:00:00，
 * 这里单独给个只到日的格式化器，避免列表里出现无意义的时分秒。
 */
const dateOnlyFormatter = (_row: any, _column: any, cellValue: any) => {
  if (!cellValue) return ''
  return String(cellValue).substring(0, 10)
}

/** 初始化 **/
onMounted(() => {
  getList()
  getDeptTree()
})
</script>
