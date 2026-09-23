package com.srtp.server.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class PythonExecutor {

    private static final Logger log = LoggerFactory.getLogger(PythonExecutor.class);
    private static final String SCRIPT = "algorithm_bridge.py";
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Execute a Python TSP algorithm and return the JSON result.
     *
     * @param pythonExe   Python executable path (e.g. "python" or "py")
     * @param scriptDir   Directory containing algorithm_bridge.py
     * @param inputJson   JSON input with algorithm parameters
     * @return JSON result from Python
     */
    public static JsonNode execute(String pythonExe, String scriptDir, String inputJson) throws Exception {
        String scriptPath = scriptDir + File.separator + SCRIPT;
        ProcessBuilder pb = new ProcessBuilder(pythonExe, scriptPath);
        pb.directory(new File(scriptDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Write JSON input to stdin
        try (OutputStream os = process.getOutputStream()) {
            os.write(inputJson.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // Read output
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(300, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Python算法执行超时（300秒）");
        }

        int exitCode = process.exitValue();
        String result = output.toString().trim();

        if (exitCode != 0) {
            log.error("Python exited with code {}: {}", exitCode, result);
            throw new RuntimeException("算法执行失败: " + result);
        }

        // Try to find the last JSON object in the output (in case of debug prints before it)
        String jsonStr = extractLastJson(result);
        return mapper.readTree(jsonStr);
    }

    private static String extractLastJson(String output) {
        // Find the last JSON object (from { to })
        int lastOpen = output.lastIndexOf('{');
        int lastClose = output.lastIndexOf('}');
        if (lastOpen >= 0 && lastClose > lastOpen) {
            return output.substring(lastOpen, lastClose + 1);
        }
        return output;
    }
}
