# peekaboo(t)

peekaboo(t) is a tiny Spring Boot starter that lets you peek inside your application—health, info, and a few other tidbits—through a simple embedded web UI. Think of it as your app's little "boot peekaboo" moment.

## Features

- **Minimal and easy to integrate**: Just add the starter dependency and you're done
- **Embedded web UI**: Ready out of the box, no extra frontend setup required using Bulma CSS framework
- **Dark/Light theme**: Built-in theme toggle with automatic system preference detection
- **Lightweight**: Tiny footprint, minimal dependencies via webjars
- **Responsive design**: Optimized for mobile, tablet, and desktop
- **Secure by default**: No direct calls to actuator endpoints
- **Customizable**: Configuration options for enabling/disabling and customizing the base path

## Why peekaboo(t)?

Sometimes you just want to see what's going on without opening a dozen endpoints or running curl commands. peekaboo(t) gives you a clean, minimal interface to:

- **Inspect application info**: View your application info from the actuator
- **Check application health**: See health status and component details
- **View environment properties**: Inspect property sources (with sensitive data masked)
- **Monitor JVM**: Check JVM version, vendor, and memory usage
- **See active profiles**: Quickly identify which Spring profiles are active

All embedded, no extra frontend setup required.

## Quick Start

### 1. Add the dependency

Add the peekaboot starter to your Spring Boot application:

**Maven:**
```xml
<dependency>
    <groupId>net.osslabz</groupId>
    <artifactId>peekaboot-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'net.osslabz:peekaboot-spring-boot-starter:0.0.1-SNAPSHOT'
```

### 2. Enable Spring Boot Actuator

peekaboot requires Spring Boot Actuator. If you don't have it already, add:

**Maven:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### 3. Configure Actuator endpoints (optional)

In your `application.properties` or `application.yml`, you can configure actuator endpoints:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

### 4. Access the UI

Start your application and navigate to:

```
http://localhost:8080/peekaboot/
```

The API endpoint is available at:

```
http://localhost:8080/peekaboot/api
```

## User Interface

peekaboot features a modern, responsive UI built with the Bulma CSS framework:

### Dark/Light Theme

- **Automatic detection**: On first visit, the theme automatically matches your system preference
- **Manual toggle**: Click the theme toggle button (🌙/☀️) in the top-right corner to switch between light and dark modes
- **Persistent preference**: Your theme choice is saved in browser localStorage and remembered across sessions
- **Smooth transitions**: Theme changes are animated with smooth color transitions

### Navigation

The UI is organized into three main tabs:

- **Info**: Application information, JVM details, and active Spring profiles
- **Health**: Overall health status and individual component health checks
- **Environment**: Configuration property sources with sensitive values masked

### Responsive Design

The interface automatically adapts to:
- **Mobile devices**: Touch-friendly, app-like layout
- **Tablets**: Optimized spacing and typography
- **Desktop**: Full-featured layout with maximum information density

## Configuration

peekaboot can be configured through your `application.properties` or `application.yml`:

```yaml
peekaboot:
  enabled: true           # Enable or disable peekaboot (default: true)
  basePath: /peekaboot   # Base path for the UI and API (default: /peekaboot)
```

### Disabling peekaboot

To disable peekaboot completely:

```yaml
peekaboot:
  enabled: false
```

### Customizing the base path

To change the base path:

```yaml
peekaboot:
  basePath: /monitoring
```

Then access the UI at `http://localhost:8080/monitoring/`

## Security Considerations

peekaboot aggregates data from Spring Boot Actuator endpoints without directly exposing them. This means:

1. **No direct actuator exposure**: The actuator endpoints themselves don't need to be exposed via HTTP
2. **Sensitive data masking**: Property values containing sensitive keywords (password, secret, token, key, credential) are automatically masked
3. **Filtered property sources**: System properties and environment variables are excluded by default

### Securing peekaboot

If you want to secure the peekaboot UI, you can use Spring Security:

```java
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/peekaboot/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
```

## Architecture

peekaboot is structured as a multi-module Maven project:

```
peekaboot/
├── peekaboot-parent/              # Parent POM
├── peekaboot-core/                # Core backend logic
│   ├── DTOs for API responses
│   ├── Service layer aggregating actuator data
│   └── REST controller
├── peekaboot-frontend/            # Frontend resources
│   ├── HTML (Bulma-based, responsive, tab-based UI)
│   ├── CSS (dark/light theme, minimal custom overrides)
│   ├── JavaScript (theme toggle, async data fetching)
│   └── Webjars (Bulma CSS framework)
├── peekaboot-spring-boot-autoconfigure/  # Auto-configuration
│   ├── Configuration properties
│   ├── Auto-configuration class
│   └── META-INF registration
└── peekaboot-spring-boot-starter/ # Dependency aggregator
```

## Development

### Prerequisites

- Java 17 or higher
- Maven 3.6+
- Spring Boot 3.5.x

### Building from source

```bash
mvn clean install
```

### Running tests

```bash
mvn test
```

## Browser Support

peekaboot supports all major modern browsers:

- Chrome/Edge (latest)
- Firefox (latest)
- Safari (latest)
- Opera (latest)

Note: Internet Explorer 11 is not supported.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Authors

- Raphael Vullriede ([@osslabz](https://github.com/osslabz))

## Links

- [GitHub Repository](https://github.com/osslabz/peekaboot)
- [Issue Tracker](https://github.com/osslabz/peekaboot/issues)
