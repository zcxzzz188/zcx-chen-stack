export {}

// 与 Vite 的 ElementPlusResolver 自动导入保持一致。
declare global {
  const ElMessage: typeof import('element-plus')['ElMessage']
  const ElMessageBox: typeof import('element-plus')['ElMessageBox']
}
