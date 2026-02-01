<template>
  <el-form label-width="auto">
    <el-form-item label="测试登录">
      <div style="width: 100%;">
        <el-button
            @click="testLogin"
            :loading="testLoginLoading"
            type="success"
        >
          测试登录（复用缓存）
        </el-button>
        <el-text class="mx-1" size="small" style="display: block; margin-top: 8px;">
          测试是否能成功登录，会复用缓存的 token
        </el-text>
      </div>
    </el-form-item>
    <el-form-item label="测试磁力链接">
      <div style="width: 100%;">
        <el-input
            v-model="testMagnet"
            placeholder="magnet:?xt=urn:btih:..."
            type="textarea"
            :rows="3"
        />
        <div style="margin-top: 8px; display: flex; gap: 8px;">
          <el-button
              @click="testOfflineDownload"
              :loading="testLoading"
              type="primary"
          >
            测试提交离线下载
          </el-button>
        </div>
        <el-text class="mx-1" size="small" style="display: block; margin-top: 8px;">
          输入一个磁力链接，测试是否能成功提交到 123 网盘离线下载
        </el-text>
      </div>
    </el-form-item>
  </el-form>
</template>

<script setup>
import {ref} from "vue";
import api from "@/js/api.js";
import {ElMessage} from "element-plus";

const props = defineProps(['config'])

const testMagnet = ref('magnet:?xt=urn:btih:ZOCMZQIPFFW7OLLMIC5HUB6BPCSDEOQU')
const testLoading = ref(false)
const testLoginLoading = ref(false)

const testLogin = () => {
  testLoginLoading.value = true
  api.post("api/pan123TestLogin", {
    config: props.config
  })
      .then(res => {
        ElMessage.success(res.message || '登录成功')
      })
      .catch(err => {
        ElMessage.error(err.message || '登录失败')
      })
      .finally(() => {
        testLoginLoading.value = false
      })
}

const testOfflineDownload = () => {
  if (!testMagnet.value || !testMagnet.value.startsWith('magnet:')) {
    ElMessage.error('请输入有效的磁力链接')
    return
  }

  testLoading.value = true
  api.post("api/pan123TestOfflineDownload", {
    config: props.config,
    magnet: testMagnet.value
  })
      .then(res => {
        ElMessage.success(res.message || '测试成功')
      })
      .catch(err => {
        ElMessage.error(err.message || '测试失败')
      })
      .finally(() => {
        testLoading.value = false
      })
}
</script>

<style scoped>
</style>
