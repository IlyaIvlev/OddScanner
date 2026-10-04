package com.oddscanner.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // Оркестратор управляет параллелизмом сам
        // Оставляем 1 поток только для @Scheduled в оркестраторе
        taskRegistrar.setScheduler(Executors.newScheduledThreadPool(1));
    }
}