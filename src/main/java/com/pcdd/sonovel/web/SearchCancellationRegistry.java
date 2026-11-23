package com.pcdd.sonovel.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聚合搜索的取消登记表。
 * <p>
 * 客户端关闭 SSE 连接只是“不再读”，服务端未必立刻写失败（TCP 半关闭时写缓冲还能成功），
 * 因此前端点「停止」时额外发一次取消请求，用 searchId 精确命中正在跑的那次搜索。
 */
public final class SearchCancellationRegistry {

    private static final Map<String, AtomicBoolean> CANCEL_FLAGS = new ConcurrentHashMap<>();

    private SearchCancellationRegistry() {
    }

    /**
     * 登记一次搜索，返回它的取消标志
     */
    public static AtomicBoolean register(String searchId) {
        AtomicBoolean flag = new AtomicBoolean(false);
        CANCEL_FLAGS.put(searchId, flag);
        return flag;
    }

    /**
     * 取消指定搜索，返回是否命中（未命中说明搜索已结束或 searchId 不存在）
     */
    public static boolean cancel(String searchId) {
        AtomicBoolean flag = CANCEL_FLAGS.get(searchId);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }

    public static void unregister(String searchId) {
        CANCEL_FLAGS.remove(searchId);
    }
}
