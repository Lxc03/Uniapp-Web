/**
 * 通过 H5 同源 /api 发请求。浏览器随请求带 Cookie，业务写请求由调用方传入 CSRF 头。
 * HTTP 响应按 statusCode 和 data.code 由调用方分流，断网与超时通过 Promise 拒绝传递。
 *
 * @param {string} path 以 / 开头、去掉 /api 前缀的接口路径
 * @param {object} options uni.request 使用的 method、header 和 data 等选项
 * @returns {Promise<object>} 包含 HTTP 状态和响应体的 uni.request 结果
 */
export function apiRequest(path, options = {}) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: '/api' + path,
      timeout: 10000,
      ...options,
      success: resolve,
      fail: reject,
    })
  })
}
