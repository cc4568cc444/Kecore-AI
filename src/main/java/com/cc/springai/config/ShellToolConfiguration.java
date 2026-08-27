package com.cc.springai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(ShellToolConfiguration.ShellToolProperties.class)
public class ShellToolConfiguration {

    @ConfigurationProperties(prefix = "app.tools.shell")
    public static class ShellToolProperties {
        private Duration commandTimeout = Duration.ofMinutes(15);

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public void setCommandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
        }
    }
}
