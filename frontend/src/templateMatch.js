/**
 * 模板语言匹配工具（前端）。
 *
 * 供「从模板导入」使用：把模板的语言标注与当前项目语言做匹配，决定下拉分组与过滤。
 * 约定：模板 (language, category) 二者取其一作为语言标注；'Generic'/通用/未标注 一律视为不限语言。
 * 注意 category 里的 'Generic' 是「通用」而非某个语言，必须显式排除，否则会与语言名做子串比较而误判。
 */

/** 模板的语言标注：优先 language，回落 category；不限语言时返回 '' */
export function templateLanguageOf(t) {
  const raw = String(t?.language || t?.category || '').trim()
  if (!raw) return ''
  return /^(generic|通用|common)$/i.test(raw) ? '' : raw
}

/** 模板是否适配某语言（模板语言为空 = 不限，处处可用；项目语言未知时不过滤） */
export function templateFitsLanguage(t, lang) {
  const tLang = templateLanguageOf(t)
  if (!tLang) return true
  const a = tLang.toLowerCase()
  const b = String(lang || '').trim().toLowerCase()
  if (!b) return true
  return a === b || a.includes(b) || b.includes(a)
}
