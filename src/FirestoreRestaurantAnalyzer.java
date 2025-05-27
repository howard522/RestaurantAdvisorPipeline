// 檔名：FirestoreRestaurantAnalyzer.java
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.StringJoiner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 從 Firestore 取出餐廳 reviews → 呼叫 Ollama 產生 300–350 字繁中摘要。
 * args:
 *   0 = restaurantId   (必填)
 *   1 = output.json    (選填, 若給定則把結果寫檔)
 */
public class FirestoreRestaurantAnalyzer {

    // === 請改成你的 Firebase 專案 ID ===
    private static final String PROJECT_ID = "java2025-91d74";

    // ---- 可被環境變數覆寫 ----
    private static final String OLLAMA_URL = System.getenv()
            .getOrDefault("OLLAMA_URL", "http://localhost:11434/api/generate");
    private static final String DEFAULT_MODEL = System.getenv()
            .getOrDefault("OLLAMA_MODEL", "gemma3:4b");

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java FirestoreRestaurantAnalyzer <restaurantId> [output.json]");
            System.exit(1);
        }
        String restaurantId = args[0];
        Path outFile = args.length >= 2 ? Path.of(args[1]) : null;

        // 1️⃣ 讀取 Firestore reviews 子集合
        JsonNode docs = fetchReviews(restaurantId);
        if (docs == null || !docs.isArray() || docs.size() == 0) {
            System.err.println("❌ 找不到任何評論，無法分析。");
            return;
        }

        // 2️⃣ 萃取「評論」欄位文字，同步排除由 Guided Dining 標籤產生的假評論
        StringJoiner sj = new StringJoiner("\n");
        for (JsonNode doc : docs) {
            JsonNode fields = doc.get("fields");
            if (fields == null) continue;
            JsonNode comment = fields.get("comment");      // ← 依你的欄位名稱調整
            if (comment == null || comment.isNull()) continue;

            if (comment.has("stringValue")) {
                sj.add(comment.get("stringValue").asText());
            } else if (comment.has("arrayValue")) {
                JsonNode arr = comment.get("arrayValue").get("values");
                if (arr == null || !arr.isArray()) continue;
                // 跳過僅含 GUIDED_DINING_* 標籤的陣列
                if (arr.size() == 1) {
                    String v = arr.get(0).path("stringValue").asText();
                    if (v.startsWith("GUIDED_DINING_")) continue;
                }
                for (JsonNode n : arr) {
                    if (n.has("stringValue"))
                        sj.add(n.get("stringValue").asText());
                }
            }
        }

        String allComments = sj.toString().trim();
        if (allComments.isEmpty()) {
            System.err.println("❌ 沒有可用的文字評論，無法分析。");
            return;
        }

        // 3️⃣ 建立 Prompt 與呼叫 Ollama
        String prompt = """
            你是餐飲評論分析師，請根據下方多則顧客留言，
            用「繁體中文」寫一段約 300–350 字的摘要，
            說明：①菜色/飲品特色，②服務優缺點，③店內氛圍，
            最後給 1 條具體經營改善建議。
            僅需純文字，不要標題、不要條列符號。
            顧客留言：
            """ + allComments;

        String summary = callOllama(prompt);

        // 若模型誤回英文，再翻譯一次
        if (!looksChinese(summary)) {
            summary = callOllama("""
                請把下列內容完整翻成「繁體中文」，不要加任何註解：
                """ + summary);
        }

        // 4️⃣ 輸出
        System.out.println("====== 特色文字摘要 ======\n");
        System.out.println(summary.trim());

        if (outFile != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("analysis_time", OffsetDateTime.now().toString());
            result.put("summary", summary.trim());
            mapper.writeValue(outFile.toFile(), result);
            System.out.println("\n✓ 已寫入 " + outFile.toAbsolutePath());
        }
    }

    // ------------ Firestore 讀取 ------------
    private static JsonNode fetchReviews(String restaurantId) throws Exception {
        String url = String.format(
            "https://firestore.googleapis.com/v1/projects/%s/databases/(default)/documents/"
          + "restaurants/%s/reviews",
            PROJECT_ID, restaurantId);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("Firestore 讀取失敗，HTTP "
                    + conn.getResponseCode());
        }

        try (InputStream is = conn.getInputStream()) {
            JsonNode root = mapper.readTree(is);
            return root.get("documents");
        }
    }

    // ------------ Ollama 呼叫 ------------
    private static String callOllama(String prompt) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String body = mapper.writeValueAsString(Map.of(
            "model", DEFAULT_MODEL,
            "prompt", prompt,
            "stream", false
        ));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Ollama 失敗 (" + resp.statusCode() + "): " + resp.body());
        }
        return mapper.readTree(resp.body()).path("response").asText().trim();
    }

    // ------------ 中文檢查：至少 30% 漢字 ------------
    private static boolean looksChinese(String text) {
        long han = text.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                .count();
        return han >= text.length() * 0.3;
    }
}
