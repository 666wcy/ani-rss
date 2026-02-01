<template>
  <!-- 移除捐赠提示，所有功能默认解锁 -->
  <div style="display: none;">
  </div>
</template>

<script setup>
import {ref} from "vue";

let myConfig = ref({
  verifyExpirationTime: true  // 始终返回已验证
})

const props = defineProps({
  name: String,
  config: Object
})
</script>
