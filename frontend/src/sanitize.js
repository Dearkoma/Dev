/**
 * XSS 防护 — 基于 DOMPurify 的 HTML 净化
 *
 * 所有用户输入在渲染为 HTML 前必须通过此函数净化。
 * 用法: import { sanitize } from '../sanitize'
 *
 * 注意: DOMPurify 需要在 index.html 中通过 CDN 加载,
 * 或通过 npm install dompurify 安装后 import。
 * 这里提供两种使用方式的兼容层。
 */

let purify = null

// 尝试从全局 (CDN) 或 ES module 加载
function getPurify() {
  if (purify) return purify
  if (typeof window !== 'undefined' && window.DOMPurify) {
    purify = window.DOMPurify
  }
  return purify
}

/**
 * 净化 HTML 字符串，移除 XSS 攻击向量
 * @param {string} dirty - 未净化的 HTML
 * @param {object} options - DOMPurify 配置选项
 * @returns {string} 安全的 HTML
 */
export function sanitize(dirty, options = {}) {
  if (!dirty) return ''
  const purifier = getPurify()
  if (purifier) {
    return purifier.sanitize(dirty, {
      ALLOWED_TAGS: ['b', 'i', 'em', 'strong', 'a', 'p', 'br', 'ul', 'ol', 'li',
        'code', 'pre', 'h1', 'h2', 'h3', 'h4', 'span', 'div'],
      ALLOWED_ATTR: ['href', 'target', 'rel', 'class'],
      ...options
    })
  }
  // 降级: 基本 XSS 防护（转义 HTML 特殊字符）
  return escapeHtml(dirty)
}

/**
 * 净化纯文本（移除所有 HTML 标签）
 * @param {string} text
 * @returns {string}
 */
export function sanitizeText(text) {
  if (!text) return ''
  const purifier = getPurify()
  if (purifier) {
    return purifier.sanitize(text, { ALLOWED_TAGS: [] })
  }
  return escapeHtml(text)
}

/**
 * HTML 实体编码
 */
function escapeHtml(str) {
  const div = document.createElement('div')
  div.appendChild(document.createTextNode(str))
  return div.innerHTML
}

export default sanitize
