/**
 * 项目「构建能力」判定工具（前端）。
 *
 * 能力的 id / 顺序 / 依赖关系与后端 ToolchainService.CapabilityDef 保持一致：
 * 后端通过 /api/toolchain/meta 下发 capabilities（含 tools、requires），前端一律以元数据驱动，
 * 本文件只做「取值 + 依赖判定」这类通用逻辑，避免各处重复实现。
 */

/** 能力执行顺序（同时是流水线阶段顺序） */
export const CAPABILITY_ORDER = ['BUILD', 'TEST', 'QUALITY', 'SAST', 'DOCKER', 'IMAGE_SCAN', 'K8S']

/** 能力 id → 中文名（元数据不可用时的兜底文案） */
export const CAPABILITY_LABEL = {
  BUILD: '编译构建',
  TEST: '单元测试',
  QUALITY: '质量门禁',
  SAST: '代码安全扫描',
  DOCKER: '镜像构建',
  IMAGE_SCAN: '镜像安全扫描',
  K8S: 'K8s 部署',
}

/** 兜底依赖表（元数据不可用时使用；正常情况由后端 requires 驱动） */
const FALLBACK_REQUIRES = {
  IMAGE_SCAN: ['DOCKER'],
  K8S: ['DOCKER'],
}

/** "BUILD,TEST" 或 ['BUILD','TEST'] → ['BUILD','TEST'] */
export function parseCapabilities(raw) {
  if (Array.isArray(raw)) {
    return raw.map(s => String(s).trim()).filter(Boolean)
  }
  return String(raw || '').split(',').map(s => s.trim()).filter(Boolean)
}

/**
 * 判断项目是否启用了某项能力。
 * capabilities 为空（历史项目 / 未配置）时视为全部启用，保持改造前行为。
 */
export function hasCapability(raw, id) {
  const caps = parseCapabilities(raw)
  return caps.length === 0 ? true : caps.includes(id)
}

/** 某项能力依赖的能力 id 列表（优先取元数据，缺省回落兜底表） */
export function requiresOf(def, capId) {
  if (def && Array.isArray(def.requires)) return def.requires
  return FALLBACK_REQUIRES[capId] || []
}

/**
 * 剔除依赖未满足的能力（如未勾选「镜像构建」则「镜像安全扫描」「K8s 部署」不成立）。
 * 依赖总是指向 CAPABILITY_ORDER 中排在前面的能力，故按顺序单遍即可收敛。
 */
export function pruneCapabilities(defs, caps) {
  const set = new Set(parseCapabilities(caps))
  CAPABILITY_ORDER.forEach(id => {
    if (!set.has(id)) return
    const def = (defs || []).find(c => c.id === id)
    if (requiresOf(def, id).some(r => !set.has(r))) set.delete(id)
  })
  return CAPABILITY_ORDER.filter(id => set.has(id))
}

/** 某能力当前缺失的前置能力（用于提示"需先勾选 XX"）；为空表示可用 */
export function missingRequires(def, capId, caps) {
  const set = new Set(parseCapabilities(caps))
  return requiresOf(def, capId).filter(r => !set.has(r))
}

/** 能力 id → 展示名 */
export function capabilityLabel(def, capId) {
  return def?.label || CAPABILITY_LABEL[capId] || capId
}

/** 把已启用的能力拼成 "编译构建 + 单元测试 + …"（流水线预览文案用） */
export function describeCapabilities(caps, defs) {
  return CAPABILITY_ORDER
    .filter(id => parseCapabilities(caps).includes(id))
    .map(id => capabilityLabel((defs || []).find(c => c.id === id), id))
    .join(' + ')
}
