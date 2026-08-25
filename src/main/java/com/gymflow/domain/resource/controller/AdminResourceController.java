package com.gymflow.domain.resource.controller;

import com.gymflow.domain.resource.dto.request.AdminResourceCreateRequest;
import com.gymflow.domain.resource.dto.request.AdminResourceStatusUpdateRequest;
import com.gymflow.domain.resource.dto.request.AdminResourceUpdateRequest;
import com.gymflow.domain.resource.dto.response.AdminResourceResponse;
import com.gymflow.domain.resource.service.AdminResourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/resources")
@RequiredArgsConstructor
public class AdminResourceController {

    private final AdminResourceService adminResourceService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminResourceResponse> create(@Valid @RequestBody AdminResourceCreateRequest request) {
        AdminResourceResponse response = adminResourceService.createResource(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{resourceId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminResourceResponse> update(
            @PathVariable Long resourceId, @Valid @RequestBody AdminResourceUpdateRequest request) {
        AdminResourceResponse response = adminResourceService.updateResource(resourceId, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{resourceId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AdminResourceResponse> changeStatus(
            @PathVariable Long resourceId, @Valid @RequestBody AdminResourceStatusUpdateRequest request) {
        AdminResourceResponse response = adminResourceService.changeStatus(resourceId, request);
        return ResponseEntity.ok(response);
    }
}
