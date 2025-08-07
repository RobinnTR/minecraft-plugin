package me.yapayzeka;

import org.json.JSONObject;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class YapayZekaKodlayici extends JavaPlugin {

    private String apiKey;
    private String prefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        this.apiKey = getConfig().getString("api-key", "null");
        this.prefix = getConfig().getString("prefix", "§7[§bYapayZeka§7]§r ");
        getLogger().info("API KEY:" + getConfig().getString("api-key"));

        getLogger().info("YapayZekaKodlayici aktif!");
    }

    private String callOpenRouterRaw(String jsonBody) throws Exception {
    HttpURLConnection conn = null;
    try {
        // JSON gövdesini parse et, modeli zorla
        JSONObject json = new JSONObject(jsonBody);
        json.put("model", "openrouter/gpt-4o-mini"); // model sabitlendi
        String forcedJsonBody = json.toString();

        URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);

        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(forcedJsonBody.getBytes(StandardCharsets.UTF_8)); // model zorlanmış JSON gönderiliyor
        }

        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();

        StringBuilder resp = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) resp.append(line);
        }

        getLogger().info("[OpenRouter] HTTP " + status);
        if (status < 200 || status >= 300) {
            throw new RuntimeException("OpenRouter 4xx/5xx: " + resp.toString());
        }
        return resp.toString();
    } finally {
        if (conn != null) conn.disconnect();
    }
}

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (label.equalsIgnoreCase("yapayzeka") && args.length >= 2 && args[0].equalsIgnoreCase("kodla")) {
            String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            sender.sendMessage(prefix + "Açıklama AI’ye gönderiliyor: " + prompt);

            if (apiKey == null || apiKey.equals("null") || apiKey.length() < 10) {
                sender.sendMessage(prefix + "§cConfig.yml içindeki 'api-key' ayarını doldurmalısın.");
                return true;
            }

            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    JsonObject req = new JsonObject();
                    req.addProperty("model", "openai/gpt-3.5-turbo");
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
                    req.addProperty("temperature", 0.7);

                    String respStr = callOpenRouterRaw(req.toString());

                    JsonObject resp = JsonParser.parseString(respStr).getAsJsonObject();
                    if (!resp.has("choices")) throw new RuntimeException("Beklenmeyen yanıt: " + respStr);

                    String aiContent = resp
                            .getAsJsonArray("choices")
                            .get(0).getAsJsonObject()
                            .getAsJsonObject("message")
                            .get("content").getAsString();

                    String[] yasakliIfadeler = new String[]{
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
                        if (aiContent != null && aiContent.contains(ifade)) {
                            sender.sendMessage(prefix + "§cGüvensiz ifade tespit edildi: §e" + ifade);
                            sender.sendMessage(prefix + "§cİşlem iptal edildi.");
                            return;
                        }
                    }

                    String javaKodu = extractBetween(aiContent, "```java", "```");
                    String pluginYml = extractBetween(aiContent, "```yaml", "```");
                    if (javaKodu == null || pluginYml == null) {
                        sender.sendMessage(prefix + "§cAI’den uygun kod alınamadı.");
                        return;
                    }

                    // 1) Sınıf ve paket adını çıkar
                    ParsedInfo parsed;
                    try {
                        parsed = parseClassAndPackage(javaKodu);
                    } catch (Exception ex) {
                        sender.sendMessage(prefix + "§cKaynak analiz hatası: " + ex.getMessage());
                        return;
                    }

                    // 2) Çalışma dizinleri
                    String pluginAdi = "AIPlugin_" + System.currentTimeMillis();
                    Path gen = getDataFolder().toPath().resolve("gen").resolve(pluginAdi);
                    Path src = gen.resolve("src");
                    Path cls = gen.resolve("classes");
                    Files.createDirectories(src);
                    Files.createDirectories(cls);

                    // 3) Kaynağı doğru dosyaya yaz (paket yolunu da oluştur)
                    Path javaPath = parsed.packageName.isEmpty()
                            ? src.resolve(parsed.className + ".java")
                            : src.resolve(parsed.pkgPath()).resolve(parsed.className + ".java");
                    Files.createDirectories(javaPath.getParent());
                    try (BufferedWriter bw = Files.newBufferedWriter(javaPath, StandardCharsets.UTF_8)) {
                        bw.write(javaKodu);
                    }

                    // 4) plugin.yml içinde main'i güncelle/ekle
                    String finalPluginYml = buildPluginYmlWithMain(pluginYml, parsed.mainFqn());

                    // 5) Derleme (Java 8 hedefi)
                    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                    if (compiler == null) {
                        sender.sendMessage(prefix + "§cSunucuda JDK yok (sadece JRE). Derleme yapılamıyor.");
                        return;
                    }
                    try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                        fm.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(cls.toFile()));

                        // Gerekirse classpath eklemek için buraya -classpath verilebilir
                        List<String> options = new ArrayList<>();
                        options.add("-Xlint:none");
                        options.add("-source"); options.add("1.8");
                        options.add("-target"); options.add("1.8");

                        boolean ok = compiler.getTask(null, fm, null, options, null, fm.getJavaFileObjects(javaPath.toFile())).call();
                        if (!ok) {
                            sender.sendMessage(prefix + "§cDerleme başarısız. Kaynak kodu kontrol edin.");
                            return;
                        }
                    }

                    // 6) JAR oluştur: plugin.yml + tüm .class dosyaları (paket hiyerarşisiyle)
                    Path jarOut = Paths.get("plugins", pluginAdi + ".jar");
                    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarOut))) {
                        // plugin.yml
                        jos.putNextEntry(new JarEntry("plugin.yml"));
                        jos.write(finalPluginYml.getBytes(StandardCharsets.UTF_8));
                        jos.closeEntry();

                        // classes altındaki tüm .class dosyaları
                        Files.walk(cls).forEach(p -> {
                            try {
                                if (Files.isRegularFile(p) && p.toString().endsWith(".class")) {
                                    Path rel = cls.relativize(p);
                                    String entryName = rel.toString().replace(java.io.File.separatorChar, '/');
                                    jos.putNextEntry(new JarEntry(entryName));
                                    jos.write(Files.readAllBytes(p));
                                    jos.closeEntry();
                                }
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        });
                    }

                    sender.sendMessage(prefix + "§aPlugin oluşturuldu: §e" + jarOut.getFileName());
                    sender.sendMessage(prefix + "§7PlugMan ile yüklemek için: /plugman load " + pluginAdi);

                } catch (Exception e) {
                    sender.sendMessage(prefix + "§cHata: " + e.getMessage());
                    getLogger().warning("Hata: " + e.getMessage());
                }
            });

            return true;
        }
        return false;
    }

    private String extractBetween(String text, String start, String end) {
        if (text == null) return null;
        int i = text.indexOf(start);
        if (i < 0) return null;
        int j = text.indexOf(end, i + start.length());
        if (j < 0) return null;
        return text.substring(i + start.length(), j).trim();
    }

    // === YARDIMCILAR ===
    private static class ParsedInfo {
        final String className;
        final String packageName; // boş olabilir
        ParsedInfo(String c, String p) { this.className = c; this.packageName = p; }
        String pkgPath() { return packageName.isEmpty() ? "" : packageName.replace('.', '/'); }
        String mainFqn() { return packageName.isEmpty() ? className : packageName + "." + className; }
    }

    private ParsedInfo parseClassAndPackage(String source) {
        java.util.regex.Matcher mc = java.util.regex.Pattern
                .compile("public\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)")
                .matcher(source);
        if (!mc.find()) throw new IllegalArgumentException("Public class bulunamadı");
        String className = mc.group(1);

        java.util.regex.Matcher mp = java.util.regex.Pattern
                .compile("(?m)^package\\s+([a-zA-Z0-9_.]+);")
                .matcher(source);
        String packageName = mp.find() ? mp.group(1) : "";

        // Birden fazla public class varsa durdur
        java.util.regex.Matcher all = java.util.regex.Pattern
                .compile("public\\s+class\\s+[A-Za-z_][A-Za-z0-9_]*")
                .matcher(source);
        int count = 0;
        while (all.find()) count++;
        if (count > 1) throw new IllegalArgumentException("Birden fazla public class bulundu. Tek public class üretin.");

        return new ParsedInfo(className, packageName);
    }

    private String buildPluginYmlWithMain(String pluginYml, String mainFqn) {
        String norm = pluginYml.replace("\r\n", "\n");
        String[] lines = norm.split("\n");
        boolean hasMain = false;
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.toLowerCase().startsWith("main:")) {
                out.append("main: ").append(mainFqn).append("\n");
                hasMain = true;
            } else {
                out.append(line).append("\n");
            }
        }
        if (!hasMain) {
            out.append("main: ").append(mainFqn).append("\n");
        }
        return out.toString();
    }
}
