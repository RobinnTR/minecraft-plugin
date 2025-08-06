package me.yapayzeka;

import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

import javax.tools.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import com.google.gson.*;

public class YapayZekaKodlayici extends JavaPlugin {

    private String apiKey;
    private String prefix;

    @Override
    public void onEnable(){
        // Config kontrol
        saveDefaultConfig();
        reloadConfig();

        this.apiKey = getConfig().getString("api-key", "null");
        this.prefix = getConfig().getString("prefix", "§7[§bYapayZeka§7]§r ");
        getLogger().info("API KEY:" + getConfig().getString("api-key"));

        getLogger().info("YapayZekaKodlayici aktif!");

        getServer().getScheduler().runTaskAsynchronously(this, () -> callOpenRouter(apiKey));
    }

    private void callOpenRouter(String apiKey) {
    HttpURLConnection conn = null;
    try {
        URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);

        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        // İsteğe bağlı:
        // conn.setRequestProperty("HTTP-Referer", "https://example.com");
        // conn.setRequestProperty("X-Title", "Minecraft Plugin Test");

        // Java 8 uyumlu JSON gövdesi (kaçışlara dikkat)
        String body =
            "{"
          + "\"model\":\"openai/gpt-3.5-turbo\","
          + "\"messages\":["
              + "{\"role\":\"system\",\"content\":\"You are a helpful assistant.\"},"
              + "{\"role\":\"user\",\"content\":\"Merhaba! Bana kısaca cevap ver.\"}"
            + "],"
          + "\"temperature\":0.7"
          + "}";

        OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();

        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder resp = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();

        getLogger().info("[OpenRouter] HTTP " + status);
        getLogger().info("[OpenRouter] Response: " + resp.toString());
    } catch (Exception e) {
        getLogger().severe("[OpenRouter] Hata: " + e.getMessage());
    } finally {
        if (conn != null) conn.disconnect();
    }
    }
                                                                     }
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args){
        if(label.equalsIgnoreCase("yapayzeka") && args.length >= 2 && args[0].equalsIgnoreCase("kodla")){
            String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            sender.sendMessage(prefix + "Açıklama AI’ye gönderiliyor: " + prompt);

            if (apiKey == null || apiKey.equals("null") || apiKey.length() < 10) {
                sender.sendMessage(prefix + "§cConfig.yml içindeki 'api-key' ayarını doldurmalısın.");
                return true;
            }

            try {
                // API isteği oluştur
                JsonObject req = new JsonObject();
                req.addProperty("model", "deepseek/deepseek-coder:free");
                JsonArray messages = new JsonArray();

                JsonObject systemMsg = new JsonObject();
                systemMsg.addProperty("role", "system");
                systemMsg.addProperty("content", "You are a Bukkit plugin generator for Minecraft 1.8.8. Return only Java code and plugin.yml as code blocks.");

                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", prompt);

                messages.add(systemMsg);
                messages.add(userMsg);
                req.add("messages", messages);

                // API isteği gönder
                URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);

                try(OutputStream os = conn.getOutputStream()){
                    os.write(req.toString().getBytes());
                }

                // Yanıtı oku
                Reader rd = new InputStreamReader(conn.getInputStream());
                JsonObject resp = JsonParser.parseReader(rd).getAsJsonObject();
                String aiContent = resp
                    .getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
                rd.close();

                // Güvenlik filtresi
                String[] yasakliIfadeler = {
                        "Runtime.getRuntime()",
                        "ProcessBuilder",
                        "System.exit",
                        "File.delete",
                        "Thread.sleep(",
                        "while(true)",
                        "for(;;)",
                        "new URL(",
                        "new Socket("
                };
                for (String ifade : yasakliIfadeler) {
                    if (aiContent.contains(ifade)) {
                        sender.sendMessage(prefix + "§cGüvensiz ifade tespit edildi: §e" + ifade);
                        sender.sendMessage(prefix + "§cİşlem iptal edildi.");
                        return true;
                    }
                }

                // Kod ayıklama
                String javaKodu = extractBetween(aiContent, "```java", "```");
                String pluginYml = extractBetween(aiContent, "```yaml", "```");
                if (javaKodu == null || pluginYml == null) {
                    sender.sendMessage(prefix + "§cAI’den uygun kod alınamadı.");
                    return true;
                }

                // Plugin oluşturma
                String pluginAdi = "AIPlugin_" + System.currentTimeMillis();
                Path gen = getDataFolder().toPath().resolve("gen").resolve(pluginAdi);
                Path src = gen.resolve("src");
                Path cls = gen.resolve("classes");
                Files.createDirectories(src);
                Files.createDirectories(cls);

                Path javaPath = src.resolve("GeneratedPlugin.java");
                Files.write(javaPath, javaKodu.getBytes());
                Files.write(gen.resolve("plugin.yml"), pluginYml.getBytes());

                // Derleme
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
                    fm.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(cls.toFile()));
                    compiler.getTask(null, fm, null, null, null, fm.getJavaFileObjects(javaPath.toFile())).call();
                }

                // .jar oluştur
                Path jarOut = Paths.get("plugins", pluginAdi + ".jar");
                try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarOut))) {
                    jos.putNextEntry(new JarEntry("plugin.yml"));
                    jos.write(pluginYml.getBytes()); jos.closeEntry();

                    jos.putNextEntry(new JarEntry("GeneratedPlugin.class"));
                    jos.write(Files.readAllBytes(cls.resolve("GeneratedPlugin.class")));
                    jos.closeEntry();
                }

                sender.sendMessage(prefix + "§aPlugin oluşturuldu: §e" + jarOut.getFileName());
                sender.sendMessage(prefix + "§7PlugMan ile yüklemek için: /plugman load " + pluginAdi);

            } catch (Exception e) {
                sender.sendMessage(prefix + "§cHata: " + e.getMessage());
                e.printStackTrace();
            }

            return true;
        }
        return false;
    }

    private String extractBetween(String text, String start, String end) {
        int i = text.indexOf(start);
        if(i < 0) return null;
        int j = text.indexOf(end, i + start.length());
        if(j < 0) return null;
        return text.substring(i + start.length(), j).trim();
    }
}
