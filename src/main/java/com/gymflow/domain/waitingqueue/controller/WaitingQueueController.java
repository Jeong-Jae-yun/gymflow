package com.gymflow.domain.waitingqueue.controller;

import com.gymflow.domain.waitingqueue.dto.request.WaitingQueueCreateRequest;
import com.gymflow.domain.waitingqueue.dto.response.WaitingQueueResponse;
import com.gymflow.domain.waitingqueue.service.WaitingQueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/waiting-queues")
@RequiredArgsConstructor
public class WaitingQueueController {

    private final WaitingQueueService waitingQueueService;

    @PostMapping
    public ResponseEntity<WaitingQueueResponse> register(@Valid @RequestBody WaitingQueueCreateRequest request) {
        WaitingQueueResponse response = waitingQueueService.registerWaitingQueue(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<WaitingQueueResponse>> getMyWaitingQueues(
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        Page<WaitingQueueResponse> response = waitingQueueService.getMyWaitingQueues(pageable);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{waitingQueueId}")
    public ResponseEntity<Void> cancel(@PathVariable Long waitingQueueId) {
        waitingQueueService.cancelWaitingQueue(waitingQueueId);
        return ResponseEntity.noContent().build();
    }
}
