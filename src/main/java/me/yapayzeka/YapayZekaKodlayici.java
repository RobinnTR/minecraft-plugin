package me.yapayzeka;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

public class YapayZekaKodlayici extends JavaPlugin {

    private String apiKey;
    private String prefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        this.apiKey = getConfig().getString("api-key", "null");
        this.prefix = getConfig().getString("prefix", "§7[§bYapayZeka§7]§r ");

        getCommand("yapayzeka").setExecutor(this);
        getLogger().info("YapayZekaKodlayici 1.8.8 için aktif!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (label.equalsIgnoreCase("yapayzeka") && args.length >= 2 && args[0].equalsIgnoreCase("kodla")) {
            final String prompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            sender.sendMessage(prefix + "Açıklama AI’ye gönderiliyor: " + prompt);

            if (apiKey == null || apiKey.equals("null") || apiKey.length() < 10) {
                sender.sendMessage(prefix + "§cConfig.yml içindeki 'api-key' ayarını doldurmalısın.");
                return true;
            }

            getServer().getScheduler().runTaskAsynchronously(this, new Runnable() {
                public void run() {
                    while (true) {
                        try {
                            JsonObject req = new JsonObject();
                            req.addProperty("model", "openai/gpt-3.5-turbo");

                            JsonArray messages = new JsonArray();

                            JsonObject systemMsg = new JsonObject();
                            systemMsg.addProperty("role", "system");
                            systemMsg.addProperty("content", "You are a Bukkit plugin generator strictly for Minecraft version 1.8.8 using only materials, classes, and methods available in Spigot 1.8.8. All generated code must be fully compatible with Java 8 and must not reference any API, method, or class introduced after Spigot 1.8.8. Return only Java code and plugin.yml as code blocks.");

                            JsonObject userMsg = new JsonObject();
                            userMsg.addProperty("role", "user");
                            userMsg.addProperty("content", prompt);

                            messages.add(systemMsg);
                            messages.add(userMsg);
                            req.add("messages", messages);
                            req.addProperty("temperature", 0.5);

                            String aiResponse = callAPI(req.toString());

                            String javaKodu = extractBetween(aiResponse, "```java", "```");
                            String pluginYml = extractBetween(aiResponse, "```yaml", "```");

                            if (javaKodu == null || pluginYml == null) {
                                sender.sendMessage(prefix + "§eAI’den geçerli kod alınamadı. Tekrar deneniyor...");
                                continue;
                            }

                            ParsedInfo parsed = parseClassAndPackage(javaKodu);
                            String pluginAdi = "AIPlugin_" + System.currentTimeMillis();
                            Path gen = getDataFolder().toPath().resolve("gen").resolve(pluginAdi);
                            Path src = gen.resolve("src");
                            Path cls = gen.resolve("classes");
                            Path res = gen.resolve("resources");
                            Files.createDirectories(src);
                            Files.createDirectories(cls);
                            Files.createDirectories(res);

                            Path javaPath = parsed.packageName.isEmpty()
                                    ? src.resolve(parsed.className + ".java")
                                    : src.resolve(parsed.pkgPath()).resolve(parsed.className + ".java");
                            Files.createDirectories(javaPath.getParent());
                            Files.write(javaPath, javaKodu.getBytes(StandardCharsets.UTF_8));

                            Files.write(res.resolve("plugin.yml"), buildPluginYmlWithMain(pluginYml, parsed.mainFqn()).getBytes(StandardCharsets.UTF_8));

                            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                            if (compiler == null) {
                                sender.sendMessage(prefix + "§cSunucuda JDK yok. Derleme yapılamıyor.");
                                return;
                            }

                            try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                                fm.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(cls.toFile()));
                                List<String> options = Arrays.asList("-Xlint:none", "-source", "1.8", "-target", "1.8");
                                boolean ok = compiler.getTask(null, fm, null, options, null, fm.getJavaFileObjects(javaPath.toFile())).call();
                                if (!ok) {
                                    sender.sendMessage(prefix + "§cKod derlenemedi. AI’ye tekrar soruluyor...");
                                    continue;
                                }
                            }

                            Path jarOut = Paths.get("plugins", pluginAdi + ".jar");
                            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarOut))) {
                                Files.walk(res).filter(Files::isRegularFile).forEach(p -> {
                                    try {
                                        Path rel = res.relativize(p);
                                        String entryName = rel.toString().replace(File.separatorChar, '/');
                                        jos.putNextEntry(new JarEntry(entryName));
                                        jos.write(Files.readAllBytes(p));
                                        jos.closeEntry();
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                });

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

                            sender.sendMessage(prefix + "§aPlugin oluşturuldu: §e" + jarOut.getFileName());
                            break;

                        } catch (Exception e) {
                            sender.sendMessage(prefix + "§cİşlem sırasında hata oluştu. AI’ye tekrar soruluyor...");
                        }
                    }
                }
            });
            return true;
        }
        return false;
    }

    private String callAPI(String jsonBody) throws Exception {
        URL url = new URL("https://openrouter.ai/api/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();

        StringBuilder resp = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) resp.append(line);
        }
        return resp.toString();
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
        if (!mc.find()) throw new IllegalArgumentException("Public class bulunamadı");
        String className = mc.group(1);

        java.util.regex.Matcher mp = java.util.regex.Pattern.compile("(?m)^package\\s+([a-zA-Z0-9_.]+);").matcher(source);
        String packageName = mp.find() ? mp.group(1) : "";

        return new ParsedInfo(className, packageName);
    }

    private String buildPluginYmlWithMain(String pluginYml, String mainFqn) {
        String[] lines = pluginYml.split("\n");
        boolean hasMain = false;
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (line.trim().toLowerCase().startsWith("main:")) {
                out.append("main: ").append(mainFqn).append("\n");
                hasMain = true;
            } else {
                out.append(line).append("\n");
            }
        }
        if (!hasMain) out.append("main: ").append(mainFqn).append("\n");
        return out.toString();
    }
}
