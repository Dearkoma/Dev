package com.devops.platform.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry 全链路追踪配置
 *
 * 集成 Micrometer Tracing Bridge，将 Spring 观测数据桥接到 OpenTelemetry。
 * 支持 OTLP gRPC 导出到 Jaeger / Grafana Tempo / SigNoz 等后端。
 */
@Slf4j
@Configuration
public class TracingConfig {

    @Bean
    @ConditionalOnMissingBean
    public OpenTelemetry openTelemetry() {
        String otlpEndpoint = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "");
        SdkTracerProvider tracerProvider;
        if (!otlpEndpoint.isBlank()) {
            try {
                OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
                        .setEndpoint(otlpEndpoint)
                        .build();
                tracerProvider = SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
                log.info("[OTEL] OTLP gRPC Exporter 已配置: {}", otlpEndpoint);
            } catch (Exception e) {
                log.warn("[OTEL] OTLP Exporter 配置失败，使用本地追踪: {}", e.getMessage());
                tracerProvider = SdkTracerProvider.builder().build();
            }
        } else {
            log.info("[OTEL] 未配置 OTEL_EXPORTER_OTLP_ENDPOINT，使用本地追踪");
            tracerProvider = SdkTracerProvider.builder().build();
        }
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal();
    }
}
