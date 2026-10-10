package com.devops.platform.controller;

import com.devops.platform.entity.BuildVariable;
import com.devops.platform.service.BuildVariableService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/variables")
@RequiredArgsConstructor
public class BuildVariableController {

    private final BuildVariableService variableService;

    /** 获取项目+流水线的所有变量 */
    @GetMapping
    public List<BuildVariable> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long pipelineId) {
        return variableService.getVariables(projectId, pipelineId);
    }

    /** 解析后的最终变量表（key=value） */
    @GetMapping("/resolved")
    public Map<String, String> resolved(
            @RequestParam Long projectId,
            @RequestParam(required = false) Long pipelineId) {
        return variableService.resolveAll(projectId, pipelineId);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody BuildVariable variable) {
        try {
            return ResponseEntity.ok(variableService.create(variable));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody BuildVariable variable) {
        try {
            return ResponseEntity.ok(variableService.update(id, variable));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        variableService.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
