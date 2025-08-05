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

    // OpenRouter API KEY'inizi buraya yapıştırın
    private static final String OPENROUTER_API_KEY = "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";

    @Override
    public void onEnable(){
        getLogger().info("YapayZekaKodlayici aktif!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args){
        if(label.equalsIgnoreCase("yapayzeka") && args.length >= 2 && args[0].equalsIgnoreCase("kodla")){
            String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            sender.sendMessage("➤ Açıklama AI’ye gönderiliyor: " + prompt);

            try {
                // API isteği oluştur
                JsonObject req = new JsonObject();
                req.addProperty("model", "deepseek/deepseek-coder:free");
                JsonArray messages = new JsonArray();

                JsonObject systemMsg = new JsonObject();
                systemMsg.addProperty("role", "system");
                systemMsg.addProperty("content", "You are a Bukkit plugin generator for Minecraft 1.8.8. Provide ONLY Java code and plugin.yml.");

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
                conn.setRequestProperty("Authorization", "Bearer " + OPENROUTER_API_KEY);

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

                // Yanıt içinden Java ve YAML kodu ayıkla
                String javaKodu = extractBetween(aiContent, "```java", "```");
                String pluginYml = extractBetween(aiContent, "```yaml", "```");
                if (javaKodu == null || pluginYml == null) {
                    sender.sendMessage("§cAI’den uygun kod alınamadı.");
                    return true;
                }

                // Plugin dizinleri oluştur
                String pluginAdi = "AIPlugin_" + System.currentTimeMillis();
                Path gen = getDataFolder().toPath().resolve("gen").resolve(pluginAdi);
                Path src = gen.resolve("src");
                Path cls = gen.resolve("classes");
                Files.createDirectories(src);
                Files.createDirectories(cls);

                // Java dosyasını yaz
                Path javaPath = src.resolve("GeneratedPlugin.java");
                Files.write(javaPath, javaKodu.getBytes());

                // plugin.yml dosyasını yaz
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

                sender.sendMessage("§aPlugin oluşturuldu: " + jarOut.getFileName());
                sender.sendMessage("§7PlugMan ile yüklemek için: /plugman load " + pluginAdi);
            }
            catch(Exception e){
                sender.sendMessage("§cHata: " + e.getMessage());
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
