<script setup lang="ts">
import { computed } from 'vue'

import { getEnumLabel, getEnumTagType, type EnumKey } from '@/constants/enums'

const props = withDefaults(
  defineProps<{
    enumKey: EnumKey
    value?: string | null
    size?: 'small' | 'default' | 'large'
    effect?: 'dark' | 'light' | 'plain'
  }>(),
  {
    value: null,
    size: 'small',
    effect: 'light'
  }
)

const label = computed(() => getEnumLabel(props.enumKey, props.value))
const tagType = computed(() => getEnumTagType(props.enumKey, props.value))
</script>

<template>
  <el-tag :type="tagType" :size="size" :effect="effect">{{ label }}</el-tag>
</template>
