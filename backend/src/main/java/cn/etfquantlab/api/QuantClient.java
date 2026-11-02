package cn.etfquantlab.api;

import cn.etfquantlab.prediction.AiModelSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QuantClient {
    private static final Logger LOG = LoggerFactory.getLogger(QuantClient.class);
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String token;
    private final AiModelSettings modelSettings;

    /** 内部调用使用专属令牌和回环地址，禁止把密钥放入查询串或日志。 */
    public QuantClient(
            ObjectMapper json,
            @Value("${etf.quant-url}") String baseUrl,
            @Value("${etf.internal-token}") String token,
            AiModelSettings modelSettings) {
        this.json = json;
        this.baseUrl = baseUrl;
        this.token = token;
        this.modelSettings = modelSettings;
        if (token.length() < 32) {
            throw new IllegalStateException("内部服务令牌至少需要32个字符");
        }
    }

    /** 训练与历史抓取允许有限长耗时，任何错误均由上层持久任务记录。 */
    public JsonNode post(String path, Object body) {
        try {
            ObjectNode payload = json.valueToTree(body);
            if (path.equals("/analysis/settings")) {
                payload.set("selections", json.valueToTree(modelSettings.all()));
            } else if (path.startsWith("/analysis/") && path.endsWith("/quote")) {
                // 只在报价时读取当前设置；run必须沿用调用方从报价复制的配置，避免保存设置使在途请求漂移。
                String provider = path.startsWith("/analysis/gpt") ? "openai" : "deepseek";
                JsonNode selection = modelSettings.find(provider);
                if (!payload.hasNonNull("model_settings") && selection != null) {
                    payload.set("model_settings", selection);
                }
            }
            String trace = MDC.get("trace_id");
            if (trace == null) {
                trace = UUID.randomUUID().toString();
            }
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(baseUrl + path))
                            .timeout(Duration.ofSeconds(180))
                            .header("X-Internal-Token", token)
                            .header("Content-Type", "application/json")
                            .header("X-Trace-Id", trace)
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(payload)))
                            .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warn(
                        "内部调用失败 trace_id={} path={} status={}", trace, path, response.statusCode());
                if (response.statusCode() == 422) {
                    JsonNode detail = json.readTree(response.body()).path("detail");
                    if (detail.isTextual() && detail.asText().length() <= 200) {
                        throw new IllegalArgumentException(detail.asText());
                    }
                }
                throw new IllegalArgumentException("分析服务拒绝请求，状态码：" + response.statusCode());
            }
            return json.readTree(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("分析调用被中断");
        } catch (java.io.IOException error) {
            throw new IllegalStateException("分析服务暂不可用");
        }
    }
}
