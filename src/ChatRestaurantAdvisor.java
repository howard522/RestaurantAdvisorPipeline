import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class ChatRestaurantAdvisor {
    // Ollama API 設定，可用環境變數覆寫
    private static final String OLLAMA_URL =
        System.getenv().getOrDefault("OLLAMA_URL", "http://localhost:11434/api/generate");
    private static final String DEFAULT_MODEL =
        System.getenv().getOrDefault("OLLAMA_MODEL", "gemma3:4b");
    private static final ObjectMapper mapper =
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java ChatRestaurantAdvisor \"<餐廳特色描述>\"");
            System.exit(1);
        }
        String features = args[0];
        Scanner scanner = new Scanner(System.in);
        List<String> history = new ArrayList<>();

        System.out.println("── 已載入餐廳特色 ──");
        System.out.println(features);
        System.out.println("輸入你的問題，輸入 exit 離開。");

        while (true) {
            System.out.print("> ");
            String question = scanner.nextLine().trim();
            if (question.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(question)) break;

            history.add("營業者：" + question);

            // 組 prompt：system + features + 歷史對話 + 本次提問
            StringBuilder prompt = new StringBuilder();
            prompt.append("你是餐飲經營顧問，根據以下餐廳特色與對話，給出具體經營建議。\n");
            prompt.append("餐廳特色：\n").append(features).append("\n");
            prompt.append("對話記錄：\n");
            for (String turn : history) {
                prompt.append(turn).append("\n");
            }
            prompt.append("AI：");

            String reply = callOllama(prompt.toString());
            // 若回英文，再翻譯
            if (!looksChinese(reply)) {
                reply = callOllama("請把下列內容完整翻成「繁體中文」，不要加任何註解：\n" + reply);
            }

            System.out.println("AI: " + reply.trim());
            history.add("AI：" + reply.trim());
        }

        System.out.println("結束對話。");
        scanner.close();
    }

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
            throw new RuntimeException("Ollama 呼叫失敗：" + resp.statusCode() + " / " + resp.body());
        }
        return mapper.readTree(resp.body()).path("response").asText();
    }

    private static boolean looksChinese(String text) {
        long hanCount = text.codePoints()
            .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
            .count();
        return hanCount >= text.length() * 0.3;
    }
}
