const Api = {

  getConfig() {
    return fetch('/config').then(r => r.json())
  },

  getLocalBooks() {
    return fetch('/local-books').then(r => r.json())
  },

  search(keyword, shareSourceIds = [], searchLimit = '', mainSourceIds, precision) {
    const body = { kw: keyword, shareSourceIds }
    if (searchLimit !== '') body.searchLimit = searchLimit
    if (mainSourceIds !== undefined) body.mainSourceIds = mainSourceIds
    if (precision) body.precision = true
    return fetch('/search/aggregated', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(r => {
      if (!r.ok || !r.body) throw new Error('搜索请求失败，请检查服务是否正常')
      const reader = r.body.getReader()
      const decoder = new TextDecoder('utf-8')
      return {
        cancel: () => reader.cancel(),
        stream: async ({ onPage, onProgress, onDone, onError }) => {
          let buffer = ''
          let gotData = false
          const handleEvent = (json) => {
            gotData = true
            if (json.type === 'stream_page') {
              onPage && onPage(json.results || [])
            } else if (json.type === 'stream_progress') {
              onProgress && onProgress(json.done || 0, json.total || 0, json.found || 0)
            } else if (json.type === 'stream_done') {
              onDone && onDone(json.results || [])
            } else if (json.type === 'stream_error') {
              onError && onError(new Error(json.message || '搜索失败'))
            }
          }
          try {
            while (true) {
              const { done, value } = await reader.read()
              if (done) break
              buffer += decoder.decode(value, { stream: true })
              let idx
              while ((idx = buffer.indexOf('\n\n')) >= 0) {
                const chunk = buffer.slice(0, idx)
                buffer = buffer.slice(idx + 2)
                const dataLine = chunk.split('\n').find(l => l.startsWith('data: '))
                if (dataLine) {
                  try {
                    handleEvent(JSON.parse(dataLine.slice(6)))
                  } catch (_) { /* 忽略无法解析的分帧 */ }
                }
              }
            }
            if (!gotData) onError && onError(new Error('没有返回搜索结果'))
          } catch (e) {
            onError && onError(e)
          }
        }
      }
    })
  },

  getShareSources() {
    return fetch('/share-sources').then(r => r.json())
  },

  reloadShareSources() {
    return new EventSource('/share-sources/reload')
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