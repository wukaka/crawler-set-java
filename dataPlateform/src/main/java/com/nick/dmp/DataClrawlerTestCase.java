package com.nick.dmp;

import cn.com.nbd.dmp.miner.MinerApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gargoylesoftware.htmlunit.*;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.util.WebConnectionWrapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(classes = MinerApplication.class)
public class DataClrawlerTestCase {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class FeedItem implements Serializable {

        private static final long serialVersionUID = -8173505435717652249L;

        private String itemId;

        private String title;

        private Integer createTime;

        private Integer reads;

        private Integer comments;

        private String source;

    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate redisTemplate;

    private BoundHashOperations hashOperations;

    @PostConstruct
    private void init() {
        hashOperations = redisTemplate.boundHashOps("domain:miner:feed");
    }


    @Test
    public void crawlerTest() {

        HashMap<String, String> params = Maps.newHashMap();

        params.put("5431132532", "5431132532");
        params.put("5724101387", "5724101387");
        params.put("4383969189", "4383969189");
        params.put("52857496566", "1553678319342593");
        params.put("3672765166", "3235349539");

        for (Map.Entry<String, String> item : params.entrySet()) {
            download(item.getKey(), item.getValue());
        }
    }

    public void download(String userId, String mId) {
        log.info("头条任务开始....................................{}", DateFormatUtils.ISO_8601_EXTENDED_DATETIME_TIME_ZONE_FORMAT.format(new Date()));
        WebClient webClient = null;
        try {
            final AtomicBoolean inBoundry = new AtomicBoolean(true);
            final AtomicInteger maxHotTime = new AtomicInteger(0);
            webClient = getClient();

            new WebConnectionWrapper(webClient) {
                @Override
                public WebResponse getResponse(WebRequest request) throws IOException {
                    WebResponse response = super.getResponse(request);
                    if (request.getUrl().toExternalForm().contains("/c/user/article")) {
                        String str = IOUtils.toString(response.getContentAsStream(), "utf-8");
                        HashMap responseJson = objectMapper.readValue(str, HashMap.class);
                        Boolean hasMore = (boolean) responseJson.getOrDefault("has_more", false);
                        if (!hasMore) {
                            log.info("头条——————————————数据为空,请求为{}", request.getUrl());
                            return response;
                        }
                        HashMap next = (HashMap) responseJson.getOrDefault("next", Maps.newHashMap());
                        maxHotTime.set((int) next.getOrDefault("max_behot_time", 0L));
                    }
                    return response;
                }
            };

            String url = String.format("https://www.toutiao.com/c/user/%s/#mid=%s", userId, mId);
            WebRequest webRequest = new WebRequest(new URL(url));
            webRequest.setCharset(Charsets.UTF_8);
            webRequest.setAdditionalHeader("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/76.0.3809.100 Safari/537.36");

            HtmlPage page = webClient.getPage(webRequest);
            webClient.waitForBackgroundJavaScript(30000);

            while (inBoundry.get()) {
                // 2019-02-01 end
                if (1548950400 > maxHotTime.get()) {
                    break;
                }

                log.info(String.format("close time: %d", maxHotTime.get()));

                ScriptResult ascpResult = page.executeJavaScript("ascp.getHoney();");
                ScriptResult signatureResult = page.executeJavaScript("TAC.sign(userInfo.id + \"\" + " + maxHotTime.get() + ")");
                Thread.sleep(200);
                String resultStr = objectMapper.writeValueAsString(ascpResult.getJavaScriptResult());
                HashMap data = objectMapper.readValue(resultStr, HashMap.class);

                String as = (String) data.getOrDefault("as", "");
                String cp = (String) data.getOrDefault("cp", "");
                String signature = signatureResult.getJavaScriptResult().toString();

                String getScript = "http({\n" +
                        "                url: \"/c/user/article/\",\n" +
                        "                method: \"get\",\n" +
                        "                data: {page_type: \"1\", user_id: " + userId + ", max_behot_time: " + maxHotTime.get() + ", count: 20, as: \"" + as + "\", cp: \"" + cp + "\", _signature: \"" + signature + "\"},\n" +
                        "                type: \"json\",\n" +
                        "                success: function(e) {\n" +
                        "                    \n" +
                        "                },\n" +
                        "                complete: function() {\n" +
                        "                   \n" +
                        "                }\n" +
                        "            })\n";
                page.executeJavaScript(getScript);

                Thread.sleep((new Random().nextInt(5) + 5) * 1000);
            }
            log.info("结束页码:...............{}", maxHotTime.get());
            log.info("头条任务结束....................................{}", DateFormatUtils.ISO_8601_EXTENDED_DATETIME_TIME_ZONE_FORMAT.format(new Date()));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (webClient != null) {
                webClient.close();
            }
        }
    }

    public static WebClient getClient() {
        final WebClient webClient = new WebClient(BrowserVersion.CHROME);
        webClient.getOptions().setJavaScriptEnabled(true);
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.getOptions().setThrowExceptionOnFailingStatusCode(false);
        webClient.getOptions().setPrintContentOnFailingStatusCode(false);
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        webClient.setCssErrorHandler(new SilentCssErrorHandler());
        webClient.getOptions().setDownloadImages(false);
        webClient.getOptions().setJavaScriptEnabled(true);
        webClient.getOptions().setUseInsecureSSL(true);
        webClient.getOptions().setCssEnabled(false);
        webClient.getCookieManager().setCookiesEnabled(true);
        webClient.setJavaScriptTimeout(30000);
        webClient.waitForBackgroundJavaScriptStartingBefore(30000);
        return webClient;
    }

}
