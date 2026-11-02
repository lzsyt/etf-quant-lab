package cn.etfquantlab.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    /** 业务输入错误仅输出受控消息，避免异常栈和连接信息泄露。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    /** 内部依赖失败不返回原始异常文本，其中可能带有请求或配置。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> failure(Exception error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(
                        Map.of(
                                "error",
                                "操作未完成，请查看任务状态或服务日志",
                                "type",
                                error.getClass().getSimpleName()));
    }
}
