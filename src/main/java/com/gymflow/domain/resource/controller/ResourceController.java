package com.gymflow.domain.resource.controller;

import com.gymflow.domain.resource.dto.response.ResourceResponse;
import com.gymflow.domain.resource.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    @GetMapping
    public ResponseEntity<Page<ResourceResponse>> getResources(
            @PageableDefault(sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<ResourceResponse> response = resourceService.getResources(pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{resourceId}")
    public ResponseEntity<ResourceResponse> getResourceDetail(@PathVariable Long resourceId) {
        ResourceResponse response = resourceService.getResourceDetail(resourceId);
        return ResponseEntity.ok(response);
    }
}
