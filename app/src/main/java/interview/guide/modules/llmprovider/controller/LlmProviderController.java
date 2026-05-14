package interview.guide.modules.llmprovider.controller;

import interview.guide.modules.llmprovider.dto.*;
import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/llm-providers")
@RequiredArgsConstructor
public class LlmProviderController {

    private final LlmProviderConfigService configService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LlmProviderResponse create(@Valid @RequestBody CreateLlmProviderRequest request) {
        return configService.create(request);
    }

    @PutMapping("/{id}")
    public LlmProviderResponse update(@PathVariable String id, @RequestBody UpdateLlmProviderRequest request) {
        return configService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        configService.delete(id);
    }

    @GetMapping
    public List<LlmProviderResponse> listAll() {
        return configService.listAll();
    }

    @PostMapping("/{id}/test")
    public Map<String, String> testConnection(@PathVariable String id) {
        String result = configService.testConnection(id);
        return Map.of("result", result);
    }

    @PutMapping("/defaults")
    public DefaultsResponse updateDefaults(@Valid @RequestBody UpdateDefaultsRequest request) {
        return configService.updateDefaults(request);
    }

    @GetMapping("/defaults")
    public DefaultsResponse getDefaults() {
        return configService.getDefaults();
    }
}
