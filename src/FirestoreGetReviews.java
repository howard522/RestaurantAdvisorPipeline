// 檔名：FirestoreGetReviews.java
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class FirestoreGetReviews {
    // 改成你自己的 Firebase 專案 ID
    private static final String PROJECT_ID = "java2025-91d74";

    /**
     * 列出並印出某餐廳文件底下的所有 reviews 子集合文件
     */
    public static void getReviews(String restaurantId) {
        try {
            String urlString = String.format(
                "https://firestore.googleapis.com/v1/projects/%s/databases/(default)/documents/restaurants/%s/reviews",
                PROJECT_ID, restaurantId
            );
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            if (status != 200) {
                System.err.println("取得評論失敗，HTTP 狀態碼：" + status);
                return;
            }

            // 讀取回應 JSON
            InputStream is = conn.getInputStream();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);

            JsonNode docs = root.get("documents");
            if (docs == null || !docs.isArray() || docs.size() == 0) {
                System.out.println("找不到任何評論。");
                return;
            }

            // 逐筆印出
            for (JsonNode doc : docs) {
                // 解析文件完整路徑最後一段為 reviewId
                String fullName = doc.get("name").asText();
                String[] parts = fullName.split("/");
                String reviewId = parts[parts.length - 1];
                System.out.println("=== Review ID: " + reviewId + " ===");

                JsonNode fields = doc.get("fields");
                if (fields != null) {
                    fields.fields().forEachRemaining(entry -> {
                        String key = entry.getKey();
                        JsonNode valueNode = entry.getValue();
                        // 假設都是 stringValue，可依需要擴充數值、bool 等型別
                        String value = valueNode.has("stringValue")
                            ? valueNode.get("stringValue").asText()
                            : valueNode.toString();
                        System.out.printf("  %s: %s%n", key, value);
                    });
                }
                System.out.println();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 主程式：從命令列讀入 restaurantId，呼叫 getReviews()
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("用法: java FirestoreGetReviews <restaurantId>");
            return;
        }
        getReviews(args[0]);
    }
}
