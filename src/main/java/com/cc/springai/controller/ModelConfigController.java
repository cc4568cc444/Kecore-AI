package com.cc.springai.controller;

import com.cc.springai.service.ModelConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/models")
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    public ModelConfigController(ModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @GetMapping
    public List<ModelConfigService.ModelConfigView> listModels() {
        return modelConfigService.list();
    }

    @GetMapping("/export")
    public List<ModelConfigService.ModelConfigExportView> exportModels() {
        return modelConfigService.exportAll();
    }

    @PutMapping("/import")
    public List<ModelConfigService.ModelConfigView> importModels(
            @RequestBody List<ModelConfigService.ModelConfigRequest> requests) {
        return modelConfigService.importAll(requests);
    }

    @PostMapping
    public ModelConfigService.ModelConfigView createModel(@RequestBody ModelConfigService.ModelConfigRequest request) {
        return modelConfigService.save(request);
    }

    @PutMapping("/{modelId}")
    public ModelConfigService.ModelConfigView updateModel(@PathVariable String modelId,
                                                          @RequestBody ModelConfigService.ModelConfigRequest request) {
        return modelConfigService.save(new ModelConfigService.ModelConfigRequest(
                modelId,
                request.name(),
                request.provider(),
                request.baseUrl(),
                request.completionsPath(),
                request.apiKey(),
                request.model(),
                request.temperature(),
                request.reasoningEffort(),
                request.thinkingType(),
                request.extraBody(),
                request.enabled()));
    }

    @DeleteMapping("/{modelId}")
    public void deleteModel(@PathVariable String modelId) {
        modelConfigService.delete(modelId);
    }
}
