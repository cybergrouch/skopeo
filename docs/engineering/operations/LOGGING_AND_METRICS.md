# Logging and Metrics

This document describes the logging and metrics implementation in Skopeo.

## Overview

The application uses a comprehensive logging and monitoring setup:

- **kotlin-logging**: Kotlin-idiomatic logging wrapper
- **Logback**: Backend logging framework (console/stdout output)
- **Ktor CallLogging**: Automatic HTTP request/response logging
- **Micrometer + Prometheus**: Performance metrics collection

## Logging

### Log Levels

The application uses the following log levels:

- **ERROR**: Critical errors that need immediate attention
- **WARN**: Warning messages for potentially harmful situations
- **INFO**: General informational messages (default level)
- **DEBUG**: Detailed information for debugging purposes
- **TRACE**: Very detailed diagnostic information

### Log Output

Logs are written to the **console (stdout)** only, in the format
`yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL logger - message`.

This is intentional: the application is deployed as a container, and container
platforms (Cloud Run, ECS, Kubernetes) collect stdout and ship it to their
logging backends. File appenders inside a container are invisible to the
platform and lose data when the container is replaced.

### Using Logging in Code

```kotlin
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class MyClass {
    fun myFunction() {
        logger.info { "This is an info message" }
        logger.debug { "Debug information: $details" }
        logger.error(exception) { "Error occurred while processing" }

        // Lazy evaluation (only evaluated if log level is enabled)
        logger.debug { "Expensive calculation: ${expensiveOperation()}" }
    }
}
```

### Request Logging

All HTTP requests and responses are automatically logged with the following information:

- HTTP method (GET, POST, etc.)
- Request URI
- Response status code
- User-Agent header

**Example log entry:**
```
15:23:45.123 [eventLoopGroupProxy-4-1] INFO  i.k.s.p.calllogging.CallLogging - GET /health - Status: 200 OK - User-Agent: curl/7.64.1
```

### Configuration

Logging is configured in `src/main/resources/logback.xml`. You can:

- Change log levels per package
- Modify log format patterns
- Add custom appenders

**Example: Enable debug logging for your package:**

```xml
<logger name="org.skopeo" level="DEBUG" />
```

## Metrics

### Overview

The application exposes Prometheus-compatible metrics that can be scraped by monitoring systems.

### Metrics Endpoint

**URL:** `http://localhost:8080/metrics`

**Format:** Prometheus text format

### Available Metrics

The application automatically tracks:

#### JVM Metrics
- `jvm_memory_used_bytes` - Memory usage
- `jvm_memory_max_bytes` - Maximum memory
- `jvm_gc_pause_seconds` - Garbage collection pauses
- `jvm_threads_live` - Number of live threads
- `jvm_classes_loaded` - Number of loaded classes

#### HTTP Metrics
- `http_server_requests_seconds_count` - Total number of requests
- `http_server_requests_seconds_sum` - Total time spent processing requests
- `http_server_requests_seconds_max` - Maximum request duration
- Request counts by endpoint, method, and status code

#### System Metrics
- `process_cpu_usage` - CPU usage
- `process_uptime_seconds` - Application uptime
- `system_cpu_count` - Number of CPU cores

### Viewing Metrics

**View in browser:**
```
http://localhost:8080/metrics
```

**View with curl:**
```bash
curl http://localhost:8080/metrics
```

**Example output:**
```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 1.6777216E7
jvm_memory_used_bytes{area="heap",id="G1 Old Gen",} 1.234567E7

# HELP http_server_requests_seconds
# TYPE http_server_requests_seconds summary
http_server_requests_seconds_count{method="GET",status="200",uri="/health",} 42.0
http_server_requests_seconds_sum{method="GET",status="200",uri="/health",} 0.156
```

### Integration with Prometheus

To scrape these metrics with Prometheus, add to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'skopeo'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/metrics'
    scrape_interval: 15s
```

### Integration with Grafana

1. Add Prometheus as a data source in Grafana
2. Import a JVM dashboard (recommended: Dashboard ID 4701)
3. Create custom dashboards for Skopeo specific metrics

### Custom Metrics

To add custom metrics, inject the MeterRegistry:

```kotlin
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Counter

fun Application.configureRouting() {
    val meterRegistry = plugin(MicrometerMetrics).registry
    val rankingCalculations = Counter.builder("tennis.ranking.calculations")
        .description("Number of ranking calculations performed")
        .register(meterRegistry)

    routing {
        post("/calculate-ranking") {
            rankingCalculations.increment()
            // ... calculate ranking
        }
    }
}
```

## Health Check

The `/health` endpoint returns application health status:

**URL:** `http://localhost:8080/health`

**Response:**
```json
{
  "status": "UP",
  "service": "Skopeo API",
  "version": "0.0.1-SNAPSHOT"
}
```

## Performance Considerations

### Logging
- Use lazy evaluation with `logger.info { }` syntax
- Avoid expensive operations in log messages
- Use appropriate log levels (don't log everything at INFO)

### Metrics
- Metrics have minimal overhead (<1% CPU)
- Metrics endpoint is lightweight (no computation, just reporting)
- Consider rate limiting the /metrics endpoint in production if scraped frequently

## Production Recommendations

1. **Set log level to INFO or WARN** in production
   ```xml
   <logger name="org.skopeo" level="INFO" />
   ```

2. **Rely on the platform's log collection** (Cloud Logging, CloudWatch)
   - Logs go to stdout; retention and search are handled by the platform

3. **Set up Prometheus + Grafana** for metrics visualization

4. **Configure alerts** for:
   - Error log entries
   - High response times (>1s)
   - Memory usage >80%
   - CPU usage >80%

5. **Consider centralized logging** (ELK Stack, Splunk, etc.) for production

## Troubleshooting

### Logs not appearing
- Verify logback.xml is in `src/main/resources/`
- Check for logback configuration errors at the top of console output

### Metrics endpoint returns empty
- Ensure Micrometer dependencies are included
- Check that the application has processed at least one request
- Verify the /metrics route is configured

### Too much logging
- Increase log level in logback.xml
- Reduce Ktor framework logging: `<logger name="io.ktor" level="WARN" />`
- Disable CallLogging for specific endpoints if needed

## References

- [kotlin-logging](https://github.com/oshai/kotlin-logging)
- [Logback Documentation](https://logback.qos.ch/manual/)
- [Ktor CallLogging](https://ktor.io/docs/call-logging.html)
- [Micrometer Documentation](https://micrometer.io/docs)
- [Prometheus Documentation](https://prometheus.io/docs/)
