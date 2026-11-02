package cn.etfquantlab.gold;

import cn.etfquantlab.api.Store;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/** 可追溯CSV的窄格式入口；模板没有样例行情，导入不授予官方质量。 */
@Component
public class GoldCsv {
    public static final String HEADER =
            "series_id,observation_date,value,unit,source,source_url,available_at,"
                    + "availability_precision,fetched_at,revision,quality";
    private final Store store;

    /** 只使用现有JSON序列化，不引入CSV或数据库公共抽象。 */
    public GoldCsv(Store store) {
        this.store = store;
    }

    /** 有界解析RFC风格引号与换行，整批校验后再由领域服务事务写入。 */
    public JsonNode parse(String csv) {
        if (csv == null || csv.length() > 5_000_000) {
            throw new IllegalArgumentException("CSV不能为空且不得超过5MB");
        }
        if (csv.startsWith("\uFEFF")) {
            csv = csv.substring(1);
        }
        List<List<String>> rows = rows(csv);
        List<String> header = Arrays.asList(HEADER.split(","));
        if (rows.isEmpty() || !rows.get(0).equals(header)) {
            throw new IllegalArgumentException("CSV表头须与下载模板完全一致");
        }
        if (rows.size() > 10001) {
            throw new IllegalArgumentException("CSV单批最多10000行");
        }
        ArrayNode observations = store.json.createArrayNode();
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.size() == 1 && row.get(0).isBlank()) {
                continue;
            }
            if (row.size() != header.size()) {
                throw new IllegalArgumentException("CSV第" + (index + 1) + "行列数错误");
            }
            ObjectNode observation = store.json.createObjectNode();
            for (int column = 0; column < header.size(); column++) {
                observation.put(header.get(column), row.get(column).strip());
            }
            if (observation.path("available_at").asText().isBlank()) {
                observation.putNull("available_at");
            }
            observations.add(observation);
        }
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("CSV没有数据行；模板不含真实行情");
        }
        ObjectNode result = store.json.createObjectNode();
        result.set("observations", observations);
        return result;
    }

    /** 引号中的逗号/换行保持原文，未闭合引号或引号后非法字符拒绝整批。 */
    private List<List<String>> rows(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean closed = false;
        for (int index = 0; index < csv.length(); index++) {
            char character = csv.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < csv.length() && csv.charAt(index + 1) == '"') {
                        cell.append('"');
                        index++;
                    } else {
                        quoted = false;
                        closed = true;
                    }
                } else {
                    cell.append(character);
                }
                continue;
            }
            if (character == '"') {
                if (!cell.isEmpty() || closed) {
                    throw new IllegalArgumentException("CSV引号位置不合法");
                }
                quoted = true;
            } else if (character == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                closed = false;
            } else if (character == '\n' || character == '\r') {
                if (character == '\r'
                        && index + 1 < csv.length()
                        && csv.charAt(index + 1) == '\n') {
                    index++;
                }
                row.add(cell.toString());
                rows.add(row);
                row = new ArrayList<>();
                cell.setLength(0);
                closed = false;
            } else {
                if (closed) {
                    throw new IllegalArgumentException("CSV闭合引号后存在非法字符");
                }
                cell.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV引号未闭合");
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
