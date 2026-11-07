package com.tj.skyrim.steam;

import static org.junit.Assert.assertFalse;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import org.junit.Assume;
import org.junit.Test;

/** Parses a real PICS appinfo dump: APPINFO_BIN=/path/appinfo.bin. */
public class VdfTest {
    @Test
    public void parsePicsDump() throws Exception {
        String path = System.getenv("APPINFO_BIN");
        Assume.assumeTrue(path != null && new File(path).isFile());
        byte[] data = Files.readAllBytes(new File(path).toPath());
        Map<String, Object> root = KeyValues.parse(data);
        Map<String, Object> app = KeyValues.map(root.get("appinfo"));
        if (app.isEmpty()) {
            app = root;
        }
        Map<String, Object> depots = KeyValues.map(app.get("depots"));
        assertFalse("no depots parsed", depots.isEmpty());
        System.out.println("depots: " + depots.size());
        for (Map.Entry<String, Object> entry : depots.entrySet()) {
            if (!entry.getKey().matches("\\d+")) {
                continue;
            }
            Map<String, Object> depot = KeyValues.map(entry.getValue());
            Map<String, Object> pub = KeyValues.map(
                    KeyValues.map(depot.get("manifests")).get("public"));
            Map<String, Object> config = KeyValues.map(depot.get("config"));
            System.out.println(entry.getKey()
                    + " gid=" + KeyValues.number(pub.get("gid"))
                    + " os=" + KeyValues.string(config.get("oslist"))
                    + " lang=" + KeyValues.string(config.get("language")));
        }
    }
}
