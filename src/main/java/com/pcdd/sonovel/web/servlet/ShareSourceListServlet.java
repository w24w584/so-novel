package com.pcdd.sonovel.web.servlet;

import com.pcdd.sonovel.utils.SourceUtils;
import com.pcdd.sonovel.web.util.RespUtils;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class ShareSourceListServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        RespUtils.writeJson(resp, SourceUtils.getShareSources());
    }
}