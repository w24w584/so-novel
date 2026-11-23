package com.pcdd.sonovel.web.servlet;

import com.pcdd.sonovel.web.SearchCancellationRegistry;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 取消正在进行的聚合搜索：前端点「停止」时调用，避免仅断开 SSE 而服务端继续跑完上千个书源。
 */
public class SearchCancelServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        String searchId = req.getParameter("id");
        boolean hit = searchId != null && !searchId.isBlank()
                && SearchCancellationRegistry.cancel(searchId);
        RespUtils.writeJson(resp, java.util.Map.of("cancelled", hit));
    }
}
