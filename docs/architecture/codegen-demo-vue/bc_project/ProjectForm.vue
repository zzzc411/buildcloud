<template>
  <Dialog :title="dialogTitle" v-model="dialogVisible" width="760px">
    <el-form
      ref="formRef"
      :model="formData"
      :rules="formRules"
      label-width="110px"
      v-loading="formLoading"
    >
      <el-row>
        <el-col :span="12">
          <el-form-item label="项目编号" prop="projectNo">
            <el-input v-model="formData.projectNo" placeholder="请输入项目编号" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="项目名称" prop="projectName">
            <el-input v-model="formData.projectName" placeholder="请输入项目名称" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="项目类型" prop="projectType">
            <el-select v-model="formData.projectType" placeholder="请选择项目类型" class="w-full">
              <el-option
                v-for="item in PROJECT_TYPE_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="项目状态" prop="status">
            <el-select v-model="formData.status" placeholder="请选择项目状态" class="w-full">
              <el-option
                v-for="item in PROJECT_STATUS_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="建设单位" prop="buildUnit">
            <el-input v-model="formData.buildUnit" placeholder="请输入建设单位（业主）" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="合同额（元）" prop="contractAmount">
            <el-input-number
              v-model="formData.contractAmount"
              :min="0"
              :precision="2"
              :controls="false"
              placeholder="请输入合同额"
              class="w-full"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="省份" prop="provinceName">
            <el-input v-model="formData.provinceName" placeholder="请输入省份名称" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="城市" prop="cityName">
            <el-input v-model="formData.cityName" placeholder="请输入城市名称" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="详细地址" prop="address">
        <el-input v-model="formData.address" placeholder="请输入详细地址" />
      </el-form-item>
      <el-row>
        <el-col :span="12">
          <el-form-item label="计划开工日期" prop="planStartDate">
            <el-date-picker
              v-model="formData.planStartDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="请选择计划开工日期"
              class="w-full"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="计划竣工日期" prop="planEndDate">
            <el-date-picker
              v-model="formData.planEndDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="请选择计划竣工日期"
              class="w-full"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="实际开工日期" prop="actualStartDate">
            <el-date-picker
              v-model="formData.actualStartDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="请选择实际开工日期"
              class="w-full"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="实际竣工日期" prop="actualEndDate">
            <el-date-picker
              v-model="formData.actualEndDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="请选择实际竣工日期"
              class="w-full"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="项目经理" prop="managerUserId">
            <el-select
              v-model="formData.managerUserId"
              placeholder="请选择项目经理"
              clearable
              filterable
              class="w-full"
            >
              <el-option
                v-for="item in userOptions"
                :key="item.id"
                :label="item.nickname"
                :value="item.id"
              />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="所属部门" prop="deptId">
            <el-tree-select
              v-model="formData.deptId"
              :data="deptTree"
              :props="defaultProps"
              check-strictly
              node-key="id"
              placeholder="请选择所属部门"
              class="w-full"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="备注" prop="remark">
        <el-input v-model="formData.remark" type="textarea" :rows="3" placeholder="请输入备注" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="submitForm" type="primary" :disabled="formLoading">确 定</el-button>
      <el-button @click="dialogVisible = false">取 消</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { ProjectApi, Project, PROJECT_TYPE_OPTIONS, PROJECT_STATUS_OPTIONS } from '@/api/project/project'
import * as DeptApi from '@/api/system/dept'
import * as UserApi from '@/api/system/user'

/** 项目的表单 */
defineOptions({ name: 'ProjectForm' })

const { t } = useI18n() // 国际化
const message = useMessage() // 消息弹窗

const dialogVisible = ref(false) // 弹窗的是否展示
const dialogTitle = ref('') // 弹窗的标题
const formLoading = ref(false) // 表单的加载中：1）修改时的数据加载；2）提交的按钮禁用
const formType = ref('') // 表单的类型：create - 新增；update - 修改

const formData = ref({
  id: undefined,
  projectNo: undefined,
  projectName: undefined,
  projectType: undefined,
  status: 0, // 新建默认「筹备」，符合状态机起点
  provinceCode: undefined,
  provinceName: undefined,
  cityCode: undefined,
  cityName: undefined,
  address: undefined,
  buildUnit: undefined,
  contractAmount: undefined,
  planStartDate: undefined,
  planEndDate: undefined,
  actualStartDate: undefined,
  actualEndDate: undefined,
  managerUserId: undefined,
  deptId: undefined,
  remark: undefined
})
const formRef = ref() // 表单 Ref

const formRules = reactive({
  projectNo: [{ required: true, message: '项目编号不能为空', trigger: 'blur' }],
  projectName: [{ required: true, message: '项目名称不能为空', trigger: 'blur' }],
  projectType: [{ required: true, message: '项目类型不能为空', trigger: 'change' }],
  status: [{ required: true, message: '项目状态不能为空', trigger: 'change' }]
})

/** 部门树与用户下拉（项目经理） */
const deptTree = ref<any[]>([])
const userOptions = ref<any[]>([])
const defaultProps = {
  children: 'children',
  label: 'name',
  value: 'id'
}

/** 打开弹窗 */
const open = async (type: string, id?: number) => {
  dialogVisible.value = true
  dialogTitle.value = t('action.' + type)
  formType.value = type
  resetForm()
  // 加载下拉数据
  deptTree.value = await DeptApi.getSimpleDeptList()
  userOptions.value = await UserApi.getSimpleUserList()
  // 修改时，设置回显
  if (id) {
    formLoading.value = true
    try {
      formData.value = await ProjectApi.getProject(id)
    } finally {
      formLoading.value = false
    }
  }
}
defineExpose({ open }) // 提供 open 方法，用于打开弹窗

/** 提交表单 */
const emit = defineEmits(['success']) // 定义 success 事件，用于操作成功后的回调
const submitForm = () => {
  if (!formRef.value) return
  formRef.value.validate(async (valid: boolean) => {
    if (!valid) return
    formLoading.value = true
    try {
      const data = formData.value as unknown as Project
      if (formType.value === 'create') {
        await ProjectApi.createProject(data)
        message.success(t('common.createSuccess'))
      } else {
        await ProjectApi.updateProject(data)
        message.success(t('common.updateSuccess'))
      }
      dialogVisible.value = false
      // 发送操作成功的事件
      emit('success')
    } finally {
      formLoading.value = false
    }
  })
}

/** 重置表单 */
const resetForm = () => {
  formData.value = {
    id: undefined,
    projectNo: undefined,
    projectName: undefined,
    projectType: undefined,
    status: 0,
    provinceCode: undefined,
    provinceName: undefined,
    cityCode: undefined,
    cityName: undefined,
    address: undefined,
    buildUnit: undefined,
    contractAmount: undefined,
    planStartDate: undefined,
    planEndDate: undefined,
    actualStartDate: undefined,
    actualEndDate: undefined,
    managerUserId: undefined,
    deptId: undefined,
    remark: undefined
  }
  formRef.value?.resetFields()
}
</script>
