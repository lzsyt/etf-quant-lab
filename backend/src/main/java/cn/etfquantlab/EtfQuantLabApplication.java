package cn.etfquantlab;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EtfQuantLabApplication {
    /** 数据库存取统一 UTC，交易日边界由业务显式转换为北京时间。 */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(EtfQuantLabApplication.class, args);
    }
}
