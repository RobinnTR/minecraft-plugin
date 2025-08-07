package me.yapayzeka;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
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
        this.prefix = getConfig().getString("prefix", "Â§7[Â§bYapayZekaÂ§7]Â§r ");
        getLogger().info("API KEY:" + getConfig().getString("api-key"));

        getLogger().info("YapayZekaKodlayici aktif!");
    }

    private String callOpenRouterRaw(String jsonBody) throws Exception {
        HttpURLConnection conn = null;
        try {
            JSONObject json = new JSONObject(jsonBody);
            json.put("model", "z-ai/glm-4.5-air:free");
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
                os.write(forcedJsonBody.getBytes(StandardCharsets.UTF_8));
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
            final String prompt = org.apache.commons.lang3.StringUtils.join(Arrays.copyOfRange(args, 1, args.length), " ");
            sender.sendMessage(prefix + "AÃ§Ä±klama AIâ€™ye gÃ¶nderiliyor: " + prompt);

            if (apiKey == null || apiKey.equals("null") || apiKey.length() < 10) {
                sender.sendMessage(prefix + "Â§cConfig.yml iÃ§indeki 'api-key' ayarÄ±nÄ± doldurmalÄ±sÄ±n.");
                return true;
            }

            getServer().getScheduler().runTaskAsynchronously(this, new Runnable() {
                public void run() {
                    boolean success = false;
                    int tryCount = 0;
                    while (!success && tryCount < 500) {
                        tryCount++;
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

                            if (!resp.has("choices")) throw new RuntimeException("Beklenmeyen yanÄ±t: " + respStr);

                            String aiContent = resp.getAsJsonArray("choices").get(0).getAsJsonObject()
                                    .getAsJsonObject("message").get("content").getAsString();

                            String[] yasakliIfadeler = new String[]{"Runtime.getRuntime()", "ProcessBuilder", "System.exit", "File.delete", "Thread.sleep(", "while(true)", "for(;;)", "new URL(", "new Socket("};
                            for (int i = 0; i < yasakliIfadeler.length; i++) {
                                if (aiContent != null && aiContent.contains(yasakliIfadeler[i])) {
                                    sender.sendMessage(prefix + "Â§cGÃ¼vensiz ifade tespit edildi: Â§e" + yasakliIfadeler[i]);
                                    sender.sendMessage(prefix + "Â§cÄ°ÅŸlem iptal edildi.");
                                    return;
                                }
                            }

                            String javaKodu = extractBetween(aiContent, "```java", "```");
                            String pluginYml = extractBetween(aiContent, "```yaml", "```");

                            if (javaKodu == null || pluginYml == null) {
                                sender.sendMessage(prefix + "Â§cAIâ€™den uygun kod alÄ±namadÄ±. Yapay zekaya eksik Ã§Ä±ktÄ± verdiÄŸi bildirilecek.");
                                getLogger().warning("Ä°stenilen eklenti yapay zekanÄ±n hatasÄ± sebebiyle kodlanamadÄ±. Yapay zekaya kod eksikliÄŸi bildirildi ve sorun giderilmeye Ã§alÄ±ÅŸÄ±lÄ±yor.");
                                continue;
                            }

                            ParsedInfo parsed = parseClassAndPackage(javaKodu);

                            String pluginAdi = "AIPlugin_" + System.currentTimeMillis();
                            Path gen = getDataFolder().toPath().resolve("gen").resolve(pluginAdi);
                            Path src = gen.resolve("src");
                            Path cls = gen.resolve("classes");
                            Files.createDirectories(src);
                            Files.createDirectories(cls);

                            Path javaPath = parsed.packageName.isEmpty()
                                    ? src.resolve(parsed.className + ".java")
                                    : src.resolve(parsed.pkgPath()).resolve(parsed.className + ".java");
                            Files.createDirectories(javaPath.getParent());
                            Files.write(javaPath, javaKodu.getBytes(StandardCharsets.UTF_8));

                            String finalPluginYml = buildPluginYmlWithMain(pluginYml, parsed.mainFqn());

                            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                            if (compiler == null) {
                                sender.sendMessage(prefix + "Â§cSunucuda JDK yok. Derleme yapÄ±lamÄ±yor.");
                                return;
                            }
                            try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                                fm.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(cls.toFile()));
                                List<String> options = Arrays.asList("-Xlint:none", "-source", "1.8", "-target", "1.8");
                                boolean ok = compiler.getTask(null, fm, null, options, null, fm.getJavaFileObjects(javaPath.toFile())).call();
                                if (!ok) {
                                    sender.sendMessage(prefix + "Â§cDerleme hatasÄ± oluÅŸtu. Yapay zekaya bildiriliyor...");
                                    getLogger().warning("Ä°stenilen eklenti yapay zekanÄ±n hatasÄ± sebebiyle kodlanamadÄ±. Yapay zekaya derleme hatasÄ± bildirildi ve Ã§Ã¶zÃ¼m aranÄ±yor.");
                                    continue;
                                }
                            }

                            Path jarOut = Paths.get("plugins", pluginAdi + ".jar");
                            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarOut))) {
                                jos.putNextEntry(new JarEntry("plugin.yml"));
                                jos.write(finalPluginYml.getBytes(StandardCharsets.UTF_8));
                                jos.closeEntry();

                                Files.walk(cls).filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class"))
                                        .forEach(p -> {
                                            try {
                                                Path rel = cls.relativize(p);
                                                String entryName = rel.toString().replace(File.separatorChar, '/');
                                                jos.putNextEntry(new JarEntry(entryName));
                                                jos.write(Files.readAllBytes(p));
                                                jos.closeEntry();
                                            } catch (IOException e) {
                                                throw new RuntimeException(e);
                                            }
                                        });
                            }

                            sender.sendMessage(prefix + "Â§aPlugin oluÅŸturuldu: Â§e" + jarOut.getFileName());
                            sender.sendMessage(prefix + "Â§7PlugMan ile yÃ¼klemek iÃ§in: /plugman load " + pluginAdi);
                            success = true;
                        } catch (Exception e) {
                            sender.sendMessage(prefix + "Â§cÄ°ÅŸlem sÄ±rasÄ±nda hata oluÅŸtu. Yapay zekaya bildiriliyor...");
                            getLogger().warning("Ä°stenilen eklenti yapay zekanÄ±n hatasÄ± sebebiyle kodlanamadÄ±. Hata bildiriliyor: " + e.getMessage());
                        }
                    }
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

    private static class ParsedInfo {
        final String className;
        final String packageName;
        ParsedInfo(String c, String p) {
            this.className = c;
            this.packageName = p;
        }
        String pkgPath() {
            return packageName.isEmpty() ? "" : packageName.replace('.', '/');
        }
        String mainFqn() {
            return packageName.isEmpty() ? className : packageName + "." + className;
        }
    }

    private ParsedInfo parseClassAndPackage(String source) {
        java.util.regex.Matcher mc = java.util.regex.Pattern.compile("public\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(source);
        if (!mc.find()) throw new IllegalArgumentException("Public class bulunamadÄ±");
        String className = mc.group(1);

        java.util.regex.Matcher mp = java.util.regex.Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+);").matcher(source);
        String packageName = mp.find() ? mp.group(1) : "";

        java.util.regex.Matcher all = java.util.regex.Pattern.compile("public\\s+class\\s+[A-Za-z_][A-Za-z0-9_]*").matcher(source);
        int count = 0;
        while (all.find()) count++;
        if (count > 1) throw new IllegalArgumentException("Birden fazla public class bulundu. Tek public class Ã¼retin.");

        return new ParsedInfo(className, packageName);
    }

    private String buildPluginYmlWithMain(String pluginYml, String mainFqn) {
        String norm = pluginYml.replace("\r\n", "\n");
        String[] lines = norm.split("\n");
        boolean hasMain = false;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.toLowerCase().startsWith("main:")) {
                out.append("main: ").append(mainFqn).append("\n");
                hasMain = true;
            } else {
                out.append(lines[i]).append("\n");
            }
        }
        if (!hasMain) {
            out.append("main: ").append(mainFqn).append("\n");
        }
        return out.toString();
    }
}
