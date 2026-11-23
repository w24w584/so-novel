package com.pcdd.sonovel;

import com.pcdd.sonovel.model.Rule;
import com.pcdd.sonovel.utils.ShareBookSourceLoader;
import com.pcdd.sonovel.utils.SourceUtils;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 书源 sourceId 稳定性：换用 文件名#url 后，勾选能精确命中且不依赖文件内顺序
 */
class ShareBookSourceIdTest {

    @Test
    void sourceIdsAreStableAndSelectable() {
        List<Map<String, Object>> groups = SourceUtils.getShareSources();
        assertFalse(groups.isEmpty());
        assertEquals("main", groups.get(0).get("file"), "main 分组应排在首位");

        Set<String> ids = new LinkedHashSet<>();
        String sampleId = null;
        String sampleUrl = null;
        for (Map<String, Object> group : groups) {
            String file = (String) group.get("file");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) group.get("sources");
            for (Map<String, Object> s : sources) {
                String id = (String) s.get("sourceId");
                assertTrue(ids.add(id), "sourceId 重复: " + id);
                assertTrue(id.startsWith(file + "#"), "sourceId 前缀错误: " + id);
                if (sampleId == null && !"main".equals(file) && !Boolean.TRUE.equals(s.get("disabled"))) {
                    sampleId = id;
                    sampleUrl = (String) s.get("url");
                }
            }
        }
        System.out.println("分组数=" + groups.size() + "，sourceId 总数=" + ids.size());
        System.out.println("样例 sourceId=" + sampleId);

        assertNotNull(sampleId);
        int base = SourceUtils.getActivatedRules().size();
        List<Rule> selected = ShareBookSourceLoader.loadSelected(Set.of(sampleId), base);
        assertEquals(1, selected.size(), "勾选一条应只解析出一条");
        assertEquals(sampleUrl, selected.get(0).getUrl());
        System.out.println("勾选命中书源=" + selected.get(0).getName());
    }
}

