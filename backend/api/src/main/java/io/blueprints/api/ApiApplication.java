package io.blueprints.api;

import io.blueprints.analysis.Analyzer;
import io.blueprints.analysis.llm.ClaudeAdvisor;
import io.blueprints.analysis.llm.OptimizationAdvisor;
import io.blueprints.analysis.Decomposer;
import io.blueprints.analysis.FixEngine;
import io.blueprints.core.graph.DefaultGraphBuilder;
import io.blueprints.core.graph.GraphBuilder;
import io.blueprints.core.model.CodeGenerator;
import io.blueprints.core.model.JavaProjectParser;
import io.blueprints.core.ops.DefaultGraphOperations;
import io.blueprints.core.ops.GraphOperations;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }

    @Bean
    JavaProjectParser javaProjectParser() {
        return new JavaProjectParser();
    }

    @Bean
    CodeGenerator codeGenerator() {
        return new CodeGenerator();
    }

    @Bean
    GraphBuilder graphBuilder() {
        return new DefaultGraphBuilder();
    }

    @Bean
    GraphOperations graphOperations() {
        return new DefaultGraphOperations();
    }

    @Bean
    Analyzer analyzer() {
        return Analyzer.withDefaultRules();
    }

    @Bean
    FixEngine fixEngine() {
        return new FixEngine();
    }

    @Bean
    Decomposer decomposer() {
        return new Decomposer();
    }

    /** LLM-советчик (роль 2d): ключ только из окружения ANTHROPIC_API_KEY; без ключа выключен. */
    @Bean
    OptimizationAdvisor optimizationAdvisor() {
        return ClaudeAdvisor.fromEnvironment();
    }
}
