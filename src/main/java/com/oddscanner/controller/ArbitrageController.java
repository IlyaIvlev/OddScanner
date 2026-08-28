package com.oddscanner.controller;

import com.oddscanner.service.ArbFinderService;
import com.oddscanner.service.ArbFinderService.ArbitrageOpportunity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/arbitrage")
@RequiredArgsConstructor
@Tag(name = "Арбитражные ситуации", description = "API для поиска вилок между букмекерами")
public class ArbitrageController {

    private final ArbFinderService arbFinderService;

    @GetMapping("/find")
    @Operation(summary = "Найти текущие арбитражные ситуации (вилки)")
    public List<ArbitrageOpportunity> findArbitrages() {
        return arbFinderService.findArbitrages();
    }
}