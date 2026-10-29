import java.net.HttpURLConnection;
import java.net.URI;

/** Small JRE-only HTTP liveness probe; no curl/wget installation in runtime images. */
public final class Healthcheck {
    public static void main(String[] args) {
        try {
            String port = System.getenv().getOrDefault("SERVER_PORT", "8080");
            String path = System.getenv().getOrDefault("HEALTHCHECK_PATH", "/healthz");
            var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            int status = connection.getResponseCode();
            connection.disconnect();
            System.exit(status == 200 ? 0 : 1);
        } catch (Exception e) {
            System.err.println("HTTP health probe failed: " + e.getMessage());
            System.exit(1);
        }
    }
}
