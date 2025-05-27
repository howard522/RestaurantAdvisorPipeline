
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class RestaurantAnalyzer {

    // ---- 可用環境變數覆寫 ----
    private static final String OLLAMA_URL = System.getenv().getOrDefault(
            "OLLAMA_URL", "http://localhost:11434/api/generate");
    private static final String DEFAULT_MODEL = System.getenv().getOrDefault(
            "OLLAMA_MODEL", "gemma3:4b");

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java -jar app.jar <input.json> <output.json>");
            System.exit(1);
        }
        Path inFile = Path.of(args[0]);
        Path outFile = Path.of(args[1]);

        // 1️⃣ 讀取「多筆評論」JSON
        JsonNode root = mapper.readTree(inFile.toFile());
        if (!root.isArray()) {
            throw new IllegalArgumentException("輸入 JSON 需為陣列 (multiple reviews)");
        }

        // 2️⃣ 萃取「評論」欄位，過濾 GUIDED_DINING_FOOD_ASPECT
        StringJoiner sj = new StringJoiner("\n");
        for (JsonNode review : root) {
            JsonNode comment = review.get("評論");
            if (comment == null || comment.isNull()) {
                continue;
            }

            if (comment.isTextual()) {
                sj.add(comment.asText());
            } else if (comment.isArray()) {
                // 若是 ["GUIDED_DINING_FOOD_ASPECT"] 則整條跳過
                if (comment.size() == 1
                        && ("GUIDED_DINING_FOOD_ASPECT".equals(comment.get(0).asText()) || 
                        "GUIDED_DINING_MODE".equals(comment.get(0).asText())) || 
                        "GUIDED_DINING_MEAL_TYPE".equals(comment.get(0).asText())) {
                    continue;
                }
                // 其餘情境：把 array elements 逐一拼成文字
                for (JsonNode n : comment) {
                    if (n.isTextual()) {
                        sj.add(n.asText());
                    }
                }
            }
        }

        String allComments = sj.toString().trim();
        if (allComments.isEmpty()) {
            throw new RuntimeException("沒有可用的文字評論，無法分析。");
        }

        // 3️⃣ 建立 Prompt（指定繁體中文輸出）
        String prompt = """
                你是餐飲評論分析師，請根據下方多則顧客留言串接，
                用「繁體中文」寫一段約 300–350 字的摘要，
                說明：①菜色/飲品特色，②服務優缺點，③店內氛圍，
                最後給 1 條具體經營改善建議。
                僅需純文字，不要標題、不要條列符號。
                顧客留言：
                """ + allComments;

        // 4️⃣ 呼叫 Ollama 產生摘要
        String summary = callOllama(prompt);

        // 5️⃣ 若輸出非中文，執行翻譯後處理
        while (!looksChinese(summary)) {
            System.out.println("⚠️ 模型輸出疑似非中文，啟動翻譯後處理…");
            String translatePrompt = """
                    請將下列內容完整翻譯成「繁體中文」，
                    不要添加註解、不要省略任何句子：
                    """ + summary;
            summary = callOllama(translatePrompt);
        }

        // 6️⃣ 輸出結果 JSON
        Map<String, Object> result = new HashMap<>();
        result.put("analysis_time", OffsetDateTime.now().toString());
        result.put("summary", summary.trim());

        mapper.writeValue(outFile.toFile(), result);
        System.out.println("✓ 完成！結果已寫入 " + outFile.toAbsolutePath());
    }

    // ====== 判斷字串是否主要為中文（至少 20% 漢字） ======
    private static boolean looksChinese(String text) {
        long hanCount = text.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp)
                == Character.UnicodeScript.HAN)
                .count();
        return hanCount >= text.length() * 0.3;
    }

    // ====== 呼叫本地 Ollama API ======
    private static String callOllama(String prompt) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();

        String reqBody = mapper.writeValueAsString(Map.of(
                "model", DEFAULT_MODEL,
                "prompt", prompt,
                "stream", false
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Ollama 調用失敗，狀態碼 "
                    + resp.statusCode() + "，內容：" + resp.body());
        }
        return mapper.readTree(resp.body())
                .path("response")
                .asText()
                .trim();
    }
}
