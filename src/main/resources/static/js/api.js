const Api = {

  /**
   * 通用 SSE 读取器：返回 { cancel, cancelled, stream(onEvent) }
   * fetch 流可以读到 HTTP 状态码（EventSource 读不到，409 之类只能显示成“断开”）
   */
  _readSse(r) {
    const reader = r.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let cancelled = false
    return {
      cancel: () => {
        cancelled = true
        return reader.cancel().catch(() => {})
      },
      get cancelled() {
        return cancelled
      },
      stream: async (onEvent) => {
        let buffer = ''
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          let idx
          while ((idx = buffer.indexOf('\n\n')) >= 0) {
            const chunk = buffer.slice(0, idx)
            buffer = buffer.slice(idx + 2)
            const dataLine = chunk.split('\n').find(l => l.startsWith('data: '))
            if (!dataLine) continue
            try {
              onEvent && onEvent(JSON.parse(dataLine.slice(6)))
            } catch (_) { /* 忽略无法解析的分帧 */ }
          }
        }
      }
    }
  },

  getConfig() {
    return fetch('/config').then(r => r.json())
  },

  getLocalBooks() {
    return fetch('/local-books').then(r => r.json())
  },

  search(keyword, shareSourceIds = [], searchLimit = '', mainSourceIds, precision) {
    // 每次搜索带一个 id：点「停止」时用它通知服务端，避免只断开连接而后台继续跑
    const searchId = (self.crypto && self.crypto.randomUUID)
      ? self.crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2)}`
    const body = { kw: keyword, shareSourceIds, searchId }
    if (searchLimit !== '') body.searchLimit = searchLimit
    if (mainSourceIds !== undefined) body.mainSourceIds = mainSourceIds
    if (precision) body.precision = true
    return fetch('/search/aggregated', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(r => {
      if (!r.ok || !r.body) throw new Error('搜索请求失败，请检查服务是否正常')
      const sse = Api._readSse(r)
      return {
        cancel: () => {
          try {
            fetch(`/search/aggregated/cancel?id=${encodeURIComponent(searchId)}`, {
              method: 'POST',
              keepalive: true
            }).catch(() => {})
          } catch (_) { /* 通知失败也不影响本地停止 */ }
          return sse.cancel()
        },
        stream: async ({ onPage, onProgress, onDone, onError }) => {
          let gotData = false
          const handleEvent = (json) => {
            gotData = true
            if (json.type === 'stream_page') {
              onPage && onPage(json.results || [])
            } else if (json.type === 'stream_progress') {
              onProgress && onProgress(json.done || 0, json.total || 0, json.found || 0, json.failed || 0)
            } else if (json.type === 'stream_done') {
              onDone && onDone(json.results || [])
            } else if (json.type === 'stream_error') {
              onError && onError(new Error(json.message || '搜索失败'))
            }
          }
          try {
            await sse.stream(handleEvent)
            if (!gotData && !sse.cancelled) onError && onError(new Error('没有返回搜索结果'))
          } catch (e) {
            // 用户主动取消时不上报错误
            if (!sse.cancelled) onError && onError(e)
          }
        }
      }
    })
  },

  getShareSources() {
    return fetch('/share-sources').then(r => r.json())
  },

  /** 手动重读书源：POST + SSE，能拿到 409 之类错误的具体文案 */
  reloadShareSources() {
    return fetch('/share-sources/reload', { method: 'POST' }).then(r => {
      if (!r.ok) {
        return r.text().then(text => {
          let message = '读取额外书源失败'
          try {
            const body = JSON.parse(text)
            if (body && body.message) message = body.message
          } catch (_) { /* 非 JSON 响应，用默认文案 */ }
          throw new Error(message)
        })
      }
      if (!r.body) throw new Error('读取额外书源失败')
      return Api._readSse(r)
    })
  },

  downloadBook(params) {
    return fetch(`/book-fetch?${params.toString()}`)
  },

  deleteBook(filename) {
    return fetch(`/book-delete?filename=${encodeURIComponent(filename)}`).then(r => r.json())
  },

  getSuggestions(kw) {
    return fetch(`/suggestion?kw=${encodeURIComponent(kw)}`).then(r => r.json())
  },

  getSources() {
    return fetch('/sources').then(r => r.json())
  },

  checkSources() {
    return fetch('/sources/check').then(r => r.json())
  },

}
