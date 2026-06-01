# Logging and Metrics

This document describes the logging and metrics implementation in Tennis Levelr.

## Overview

The application uses a comprehensive logging and monitoring setup:

- **kotlin-logging**: Kotlin-idiomatic logging wrapper
- **Logback**: Backend logging framework with file rotation
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

Logs are written to three destinations:

1. **Console** (stdout)
   - Colored output for better readability
   - Format: `HH:mm:ss.SSS [thread] LEVEL logger - message`

2. **Application Log File** (`logs/tennis-levelr.log`)
   - All log levels
   - Daily rotation
   - Keeps 30 days of history
   - Max total size: 1GB
   - Format: `yyyy-MM-dd HH:mm:ss.SSS [thread] LEVEL logger - message`

3. **Error Log File** (`logs/tennis-levelr-error.log`)
   - ERROR level only
   - Daily rotation
   - Keeps 90 days of history
   - Same format as application log

### Log Files Location

All log files are stored in the `logs/` directory at the project root:

```
logs/
├── tennis-levelr.log              # Current day all logs
├── tennis-levelr.2024-01-15.log   # Previous day logs
├── tennis-levelr-error.log        # Current day errors
└── tennis-levelr-error.2024-01-15.log  # Previous day errors
```

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
- Adjust file rotation policies
- Add custom appenders

**Example: Enable debug logging for your package:**

```xml
<logger name="org.lange.tennis.levelr" level="DEBUG" />
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
  - job_name: 'tennis-levelr'
    static_configs:
      - targets: ['localhost:8080']
    metrics_path: '/metrics'
    scrape_interval: 15s
```

### Integration with Grafana

1. Add Prometheus as a data source in Grafana
2. Import a JVM dashboard (recommended: Dashboard ID 4701)
3. Create custom dashboards for Tennis Levelr specific metrics

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
  "service": "Tennis Levelr API",
  "version": "0.0.1-SNAPSHOT"
}
```

## Performance Considerations

### Logging
- Use lazy evaluation with `logger.info { }` syntax
- Avoid expensive operations in log messages
- Use appropriate log levels (don't log everything at INFO)
- Log files are automatically rotated to prevent disk space issues

### Metrics
- Metrics have minimal overhead (<1% CPU)
- Metrics endpoint is lightweight (no computation, just reporting)
- Consider rate limiting the /metrics endpoint in production if scraped frequently

## Production Recommendations

1. **Set log level to INFO or WARN** in production
   ```xml
   <logger name="org.lange.tennis.levelr" level="INFO" />
   ```

2. **Monitor disk space** for log files
   - Logback will automatically clean up old logs
   - Default retention: 30 days (app logs), 90 days (error logs)

3. **Set up Prometheus + Grafana** for metrics visualization

4. **Configure alerts** for:
   - Error log entries
   - High response times (>1s)
   - Memory usage >80%
   - CPU usage >80%

5. **Consider centralized logging** (ELK Stack, Splunk, etc.) for production

## Troubleshooting

### Logs not appearing in files
- Check that the `logs/` directory exists and is writable
- Verify logback.xml is in `src/main/resources/`
- Check for errors in console output

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
